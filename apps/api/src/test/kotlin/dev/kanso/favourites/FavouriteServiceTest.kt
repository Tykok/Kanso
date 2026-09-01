package dev.kanso.favourites

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.db.Users
import dev.kanso.docs.DocService
import dev.kanso.domain.DispositionChoice
import dev.kanso.domain.DispositionPlan
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.ProjectStatus
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.NotFoundException
import dev.kanso.service.ProjectService
import dev.kanso.service.SavedViewService
import dev.kanso.service.TeamService
import dev.kanso.service.ViewGroupBy
import dev.kanso.service.ViewSortBy
import dev.kanso.trash.TrashKind
import dev.kanso.trash.TrashService
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Favourites, end to end, through the service the controller calls.
 *
 * Every assertion here is about a row the sidebar would have to draw. That is the whole
 * frame of the feature: a favourite is not a fact somebody looks up, it is a line at the
 * top of a column on every screen, so "the pin survived" is never enough — the question
 * is always whether the pin is *drawable*, and what happens to it when the thing behind
 * it is archived, thrown away or destroyed.
 */
@Transactional
class FavouriteServiceTest : PostgresTest() {

	@Autowired lateinit var favourites: FavouriteService
	@Autowired lateinit var rows: FavouriteRepository
	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var projects: ProjectService
	@Autowired lateinit var views: SavedViewService
	@Autowired lateinit var docs: DocService
	@Autowired lateinit var trash: TrashService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: org.springframework.security.crypto.password.PasswordEncoder

	private fun person(name: String, role: InstanceRole = InstanceRole.ADMIN): User =
		users.createLocalUser(
			email = "fav-${UUID.randomUUID()}@kanso.test",
			displayName = name,
			passwordHash = encoder.hash("correct-horse-battery"),
			role = role,
		)

	private val tykok: User by lazy { person("Tykok") }
	private val somebodyElse: User by lazy { person("Somebody Else") }

	private fun key() = "F${UUID.randomUUID().toString().take(4).uppercase()}"

	private fun newTeam(name: String = "Core") = teams.create(tykok, name, key(), null)

	private fun newProject(name: String, teamId: UUID?) = projects.create(
		name = name,
		status = ProjectStatus.PLANNED,
		start = null,
		end = null,
		leadUserId = null,
		teamId = teamId,
		docIds = emptyList(),
	).project

	private fun newView(teamId: UUID, name: String) = views.create(
		actor = tykok,
		teamId = teamId,
		name = name,
		shared = true,
		filters = mapOf("statusNot" to listOf("done")),
		groupBy = ViewGroupBy.STATUS,
		sortBy = ViewSortBy.PRIORITY,
	)

	private fun newDoc(teamId: UUID, title: String) =
		docs.createPage(tykok, teamId, null, title, null).page

	private fun labels(user: User) = favourites.list(user.id).map { it.label }

	@Test
	fun `pinning and un-pinning a project is a round trip`() {
		val team = newTeam()
		val project = newProject("Composer", team.id)

		favourites.add(tykok.id, FavouriteKind.PROJECT, project.id)

		val pinned = favourites.list(tykok.id).single()
		assertEquals(FavouriteKind.PROJECT, pinned.kind)
		assertEquals(project.id, pinned.id)
		assertEquals("Composer", pinned.label, "the sidebar draws a name, not an id")

		favourites.remove(tykok.id, FavouriteKind.PROJECT, project.id)

		assertTrue(favourites.list(tykok.id).isEmpty())
	}

	@Test
	fun `all four kinds are pinned and all four come back drawn`() {
		val team = newTeam("Core")
		val project = newProject("Composer", team.id)
		val view = newView(team.id, "Sync debt")
		val doc = newDoc(team.id, "Architecture")

		favourites.add(tykok.id, FavouriteKind.TEAM, team.id)
		favourites.add(tykok.id, FavouriteKind.PROJECT, project.id)
		favourites.add(tykok.id, FavouriteKind.VIEW, view.id)
		favourites.add(tykok.id, FavouriteKind.DOC, doc.id)

		assertEquals(
			listOf("Core", "Composer", "Sync debt", "Architecture"),
			labels(tykok),
			"four kinds, one list, and every row carries a label somebody would recognise",
		)
	}

	@Test
	fun `pinning the same thing twice is one row, not two and not an error`() {
		val team = newTeam()

		favourites.add(tykok.id, FavouriteKind.TEAM, team.id)
		favourites.add(tykok.id, FavouriteKind.TEAM, team.id)

		assertEquals(1, favourites.list(tykok.id).size, "the gesture is a toggle; two tabs press it twice")
	}

	@Test
	fun `un-pinning something that was never pinned is not an error`() {
		val team = newTeam()

		favourites.remove(tykok.id, FavouriteKind.TEAM, team.id)

		assertTrue(favourites.list(tykok.id).isEmpty())
	}

	@Test
	fun `one person's favourites are invisible to another`() {
		val team = newTeam("Core")
		val mine = newProject("Mine", team.id)
		val theirs = newProject("Theirs", team.id)

		favourites.add(tykok.id, FavouriteKind.PROJECT, mine.id)
		favourites.add(somebodyElse.id, FavouriteKind.PROJECT, theirs.id)

		assertEquals(listOf("Mine"), labels(tykok))
		assertEquals(listOf("Theirs"), labels(somebodyElse))

		// And un-pinning is just as private: two sidebars, two decisions.
		favourites.remove(tykok.id, FavouriteKind.PROJECT, mine.id)

		assertTrue(favourites.list(tykok.id).isEmpty())
		assertEquals(listOf("Theirs"), labels(somebodyElse), "nobody else's pin moved")
	}

	@Test
	fun `the same thing pinned by two people is two rows, one each`() {
		val team = newTeam("Core")

		favourites.add(tykok.id, FavouriteKind.TEAM, team.id)
		favourites.add(somebodyElse.id, FavouriteKind.TEAM, team.id)

		// `favourites_team_uniq` is keyed on `(user_id, team_id)`, so this is not the
		// duplicate the idempotence test is about — it is two people liking one team.
		assertEquals(listOf("Core"), labels(tykok))
		assertEquals(listOf("Core"), labels(somebodyElse))
	}

	@Test
	fun `favourites come back in the order they were made, and a new one goes last`() {
		val team = newTeam("Core")
		val first = newProject("Aaa first", team.id)
		val second = newProject("Zzz second", team.id)
		val third = newProject("Mmm third", team.id)

		favourites.add(tykok.id, FavouriteKind.PROJECT, first.id)
		favourites.add(tykok.id, FavouriteKind.PROJECT, second.id)
		favourites.add(tykok.id, FavouriteKind.PROJECT, third.id)

		assertEquals(
			listOf("Aaa first", "Zzz second", "Mmm third"),
			labels(tykok),
			"insertion order, not alphabetical — a pinned list that re-sorts itself is a list nobody can point at",
		)

		// Un-pinning the middle one must not disturb the two around it.
		favourites.remove(tykok.id, FavouriteKind.PROJECT, second.id)
		favourites.add(tykok.id, FavouriteKind.PROJECT, second.id)

		assertEquals(
			listOf("Aaa first", "Mmm third", "Zzz second"),
			labels(tykok),
			"re-pinning is a new pin and goes to the end; nothing above it moved",
		)
	}

	@Test
	fun `a deleted project leaves no row at all`() {
		val team = newTeam("Core")
		val project = newProject("Composer", team.id)
		favourites.add(tykok.id, FavouriteKind.PROJECT, project.id)

		projects.delete(tykok, project.id, DispositionPlan(counts = projects.contents(project.id).direct))

		assertTrue(
			favourites.list(tykok.id).isEmpty(),
			"the pin is gone from the sidebar because `V22`'s cascade removed the row, not because a read skipped it",
		)
		assertTrue(
			rows.findByUser(tykok.id).isEmpty(),
			"and it is gone from the table too — a favourite must never outlive its target",
		)
	}

	@Test
	fun `a deleted account takes its own pins with it and nobody else's`() {
		val team = newTeam("Core")
		favourites.add(tykok.id, FavouriteKind.TEAM, team.id)
		favourites.add(somebodyElse.id, FavouriteKind.TEAM, team.id)

		// Straight at the row: there is no "delete a user" service, and what is being
		// asserted is `favourites.user_id`'s own cascade rather than anything Kotlin does.
		Users.deleteWhere { Users.id eq somebodyElse.id }

		assertEquals(listOf("Core"), labels(tykok))
		assertTrue(rows.findByUser(somebodyElse.id).isEmpty())
	}

	@Test
	fun `an archived team is still sent, flagged, for the sidebar's toggle to decide`() {
		val core = newTeam("Core")
		val mobile = newTeam("Mobile")
		favourites.add(tykok.id, FavouriteKind.TEAM, core.id)
		favourites.add(tykok.id, FavouriteKind.TEAM, mobile.id)

		teams.archive(tykok, mobile.id, DispositionPlan())

		val pinned = favourites.list(tykok.id)
		assertEquals(listOf("Core", "Mobile"), pinned.map { it.label }, "the pin was never spent")
		assertEquals(
			listOf(false, true),
			pinned.map { it.archived },
			"which of the two the column draws is `Show archived`, and that toggle is client state",
		)
	}

	@Test
	fun `an archived project is flagged too, and un-archiving clears the flag`() {
		val team = newTeam("Core")
		val project = newProject("Composer", team.id)
		favourites.add(tykok.id, FavouriteKind.PROJECT, project.id)

		projects.archive(tykok, project.id, DispositionPlan(tickets = DispositionChoice.TAKE))
		assertTrue(favourites.list(tykok.id).single().archived)

		projects.unarchive(tykok, project.id)
		assertTrue(!favourites.list(tykok.id).single().archived, "nothing had to be re-pinned")
	}

	@Test
	fun `a trashed saved view stops being drawn and the pin waits for the restore`() {
		val team = newTeam("Core")
		val view = newView(team.id, "Sync debt")
		favourites.add(tykok.id, FavouriteKind.VIEW, view.id)

		views.delete(tykok, view.id)

		assertTrue(favourites.list(tykok.id).isEmpty(), "a view on a thirty-day countdown is not a sidebar row")
		assertEquals(1, rows.findByUser(tykok.id).size, "but the pin itself is still there")

		// Through `TrashService`, because the entry is its to remove — `Trash.kt` states the
		// split, and `SavedViewService.restoreFromTrash` is only the permission half of it.
		trash.restore(tykok, TrashKind.VIEW, view.id)

		assertEquals(listOf("Sync debt"), labels(tykok), "restoring the view restores the pin with it")
	}

	@Test
	fun `a trashed document behaves the same way, and purging it takes the pin for good`() {
		val team = newTeam("Core")
		val doc = newDoc(team.id, "Architecture")
		favourites.add(tykok.id, FavouriteKind.DOC, doc.id)

		docs.deletePage(tykok, doc.id)
		assertTrue(favourites.list(tykok.id).isEmpty())
		assertEquals(1, rows.findByUser(tykok.id).size)

		docs.purgePage(tykok, doc.id)

		assertTrue(
			rows.findByUser(tykok.id).isEmpty(),
			"the countdown ran out, the row went, and the cascade took the pin — no read had to skip anything",
		)
	}

	@Test
	fun `something that is not there cannot be pinned, and says so as a 404`() {
		assertFailsWith<NotFoundException> {
			favourites.add(tykok.id, FavouriteKind.TEAM, UUID.randomUUID())
		}
	}

	@Test
	fun `something in the trash cannot be pinned either`() {
		val team = newTeam("Core")
		val view = newView(team.id, "Sync debt")
		views.delete(tykok, view.id)

		assertFailsWith<NotFoundException> {
			favourites.add(tykok.id, FavouriteKind.VIEW, view.id)
		}
	}

	@Test
	fun `an archived team can still be pinned, because it can still be seen`() {
		val team = newTeam("Mobile")
		teams.archive(tykok, team.id, DispositionPlan())

		favourites.add(tykok.id, FavouriteKind.TEAM, team.id)

		assertEquals(listOf("Mobile"), labels(tykok))
	}

	/**
	 * The last two are their own tests on purpose: a constraint violation aborts the
	 * transaction, so nothing can be read after one — the rule `EstimateTest` already
	 * records.
	 *
	 * Together they are the closed vocabulary, checked from the side Kotlin cannot reach.
	 * `FavouriteKind` and [Favourites.target] make a two-target row unwritable from here;
	 * an importer, a migration or a psql session is the writer these are about.
	 */
	@Test
	fun `a row naming two things at once is refused by the database`() {
		val team = newTeam("Core")
		val project = newProject("Composer", team.id)

		val error = assertFailsWith<Exception> {
			Favourites.insert {
				it[id] = UUID.randomUUID()
				it[userId] = tykok.id
				it[teamId] = team.id
				it[projectId] = project.id
				it[createdAt] = OffsetDateTime.now()
			}
		}
		assertTrue(
			error.toString().contains("favourites_one_target_chk"),
			"which column is filled *is* the kind, so two filled columns is two kinds: $error",
		)
	}

	@Test
	fun `a row naming nothing at all is refused by the database`() {
		val error = assertFailsWith<Exception> {
			Favourites.insert {
				it[id] = UUID.randomUUID()
				it[userId] = tykok.id
				it[createdAt] = OffsetDateTime.now()
			}
		}
		assertTrue(
			error.toString().contains("favourites_one_target_chk"),
			"a favourite of nothing is a row the sidebar could only draw as a blank line: $error",
		)
	}
}
