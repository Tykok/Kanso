package dev.kanso.docs

import dev.kanso.PostgresTest
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.repo.UserRepository
import dev.kanso.service.BlockLockedException
import dev.kanso.service.TeamService
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `KAN-25`'s lock: who gets the block, who is refused, and what happens to a laptop
 * closed mid-paragraph.
 *
 * Two people throughout, because one person can never observe this feature. `admin` and
 * `other` are both members of the same team so that every refusal here is about the lock
 * and not about `TicketAccess` — a test where the second person simply cannot write would
 * pass with the lock removed.
 *
 * The expiry tests reach [DocBlockLockRepository] rather than [DocBlockLockService], and
 * that is the one deliberate layer violation in this file: the service reads the TTL from
 * `KansoProperties`, so testing a lapse through it would mean a thirty-second sleep or a
 * second Spring context with an overridden property. The repository takes the duration as
 * an argument, which is what lets a claim be given two milliseconds to live and then
 * watched to die.
 */
@Transactional
class DocBlockLockTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var documents: DocService
	@Autowired lateinit var blocks: DocBlockService
	@Autowired lateinit var locks: DocBlockLockService
	@Autowired lateinit var lockRows: DocBlockLockRepository
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun person(name: String): User = users.createLocalUser(
		email = "lock-${UUID.randomUUID()}@kanso.test",
		displayName = name,
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.ADMIN,
	)

	private val admin: User by lazy { person("Élie") }
	private val other: User by lazy { person("Marie") }

	private fun newTeam() = teams.create(
		admin,
		"Team ${UUID.randomUUID().toString().take(4)}",
		"K${UUID.randomUUID().toString().take(4).uppercase()}",
		null,
	)

	private fun paragraph(text: String) = mapOf<String, Any?>("text" to text)

	/** A page with one paragraph, and both people able to write in its team. */
	private fun onePage(): Pair<DocPage, DocBlock> {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Mirror architecture", null).page
		val block = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("one"), null)
		return page to block
	}

	// --- taking it -----------------------------------------------------------

	@Test
	fun `the first person to ask gets the block`() {
		val (_, block) = onePage()

		val lock = locks.take(admin, block.id)

		assertEquals(admin.id, lock.userId)
		assertTrue(lock.expiresAt.isAfter(lock.takenAt), "a lock that expires before it began is not a lock")
	}

	@Test
	fun `the second person is refused, and told who has it and when it frees`() {
		val (_, block) = onePage()
		locks.take(admin, block.id)

		val refusal = assertFailsWith<BlockLockedException> { locks.take(other, block.id) }

		// The two facts the refusal exists to carry. A 409 that says only "conflict" is the
		// silent-refusal bug `useReportError` was written for.
		assertEquals("Élie", refusal.holder)
		assertTrue(refusal.freesAt.isAfter(java.time.OffsetDateTime.now()), "it has to free itself")
		assertTrue(refusal.message!!.contains("Élie"), "the message a client prints as-is names them too")
	}

	@Test
	fun `renewing is the same call and does not hand the block over`() {
		val (_, block) = onePage()
		val first = locks.take(admin, block.id)

		val renewed = locks.take(admin, block.id)

		assertEquals(admin.id, renewed.userId)
		// `taken_at` is when the claim began and a renewal must not restate it — the screen
		// reads "held for four minutes" off it.
		assertEquals(first.takenAt, renewed.takenAt)
		assertTrue(
			!renewed.expiresAt.isBefore(first.expiresAt),
			"a renewal moves the window forward, never back",
		)
	}

	// --- the guard on a write ------------------------------------------------

	@Test
	fun `somebody else's live lock refuses an edit to that block`() {
		val (_, block) = onePage()
		locks.take(admin, block.id)

		val refusal = assertFailsWith<BlockLockedException> {
			blocks.updateBlock(other, block.id, paragraph("overwritten"))
		}

		assertEquals("Élie", refusal.holder)
		// And the write did not land. Proving the refusal without this would pass against a
		// guard that threw *after* writing.
		assertEquals("one", documents.page(block.pageId).blocks.single().content["text"])
	}

	@Test
	fun `an unlocked block still takes a write from anybody who may write there`() {
		val (_, block) = onePage()

		// No lock taken at all — the case every caller that is not a browser is in: the MCP
		// tools, an API token's script, the Notion importer. Requiring a lock would have
		// turned an advisory into a protocol none of them speaks.
		blocks.updateBlock(other, block.id, paragraph("from a script"))

		assertEquals("from a script", documents.page(block.pageId).blocks.single().content["text"])
	}

	@Test
	fun `holding the block does not stop the holder editing it`() {
		val (_, block) = onePage()
		locks.take(admin, block.id)

		blocks.updateBlock(admin, block.id, paragraph("still mine"))

		assertEquals("still mine", documents.page(block.pageId).blocks.single().content["text"])
	}

	@Test
	fun `a reorder is refused while anybody else holds any block on the page`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Mirror architecture", null).page
		val first = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("one"), null)
		val second = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("two"), null)
		locks.take(admin, second.id)

		// `first` is not locked, and moving it is still refused: `setOrder` rewrites every
		// position on the page, so the reorder collides with the claim on `second`.
		val refusal = assertFailsWith<BlockLockedException> { blocks.moveBlock(other, first.id, 1) }

		assertEquals("Élie", refusal.holder)
		assertEquals(
			listOf("one", "two"),
			documents.page(page.id).blocks.map { it.content["text"] },
			"and nothing moved",
		)
	}

	@Test
	fun `adding a block is never refused, because that is what writing together is`() {
		val (page, block) = onePage()
		locks.take(admin, block.id)

		blocks.addBlock(other, page.id, DocBlockKind.PARAGRAPH, paragraph("mine too"), null)

		assertEquals(
			listOf("one", "mine too"),
			documents.page(page.id).blocks.map { it.content["text"] },
		)
	}

	@Test
	fun `deleting a block somebody is typing in is refused`() {
		val (_, block) = onePage()
		locks.take(admin, block.id)

		assertFailsWith<BlockLockedException> { blocks.deleteBlock(other, block.id) }

		assertEquals(1, documents.page(block.pageId).blocks.size)
	}

	// --- the laptop that closed ----------------------------------------------

	@Test
	fun `a lock nobody renews stops being a lock, with nothing having run`() {
		val (_, block) = onePage()

		assertNotNull(lockRows.take(block.id, admin.id, Duration.ofMillis(2)))
		assertNotNull(lockRows.liveFor(block.id), "it is a lock while the window is open")

		Thread.sleep(20)

		// No sweeper ran, no heartbeat was missed, and the row is still there — it simply
		// is not a lock any more. That is the whole of `V40`'s expiry argument.
		assertNull(lockRows.liveFor(block.id))
	}

	@Test
	fun `an expired lock is taken by the next person to ask`() {
		val (_, block) = onePage()
		lockRows.take(block.id, admin.id, Duration.ofMillis(2))
		Thread.sleep(20)

		val taken = lockRows.take(block.id, other.id, Duration.ofSeconds(30))

		assertNotNull(taken)
		assertEquals(other.id, taken.userId)
		// And the page now says Marie holds it, by name, out of the one read the screen makes.
		assertEquals("Marie", lockRows.liveForPage(block.pageId).single().displayName)
	}

	@Test
	fun `an expired lock no longer refuses a write`() {
		val (_, block) = onePage()
		lockRows.take(block.id, admin.id, Duration.ofMillis(2))
		Thread.sleep(20)

		blocks.updateBlock(other, block.id, paragraph("the lid was closed"))

		assertEquals("the lid was closed", documents.page(block.pageId).blocks.single().content["text"])
	}

	// --- letting go ----------------------------------------------------------

	@Test
	fun `releasing frees the block immediately`() {
		val (_, block) = onePage()
		locks.take(admin, block.id)

		locks.release(admin, block.id)

		assertNull(lockRows.liveFor(block.id))
		blocks.updateBlock(other, block.id, paragraph("mine now"))
		assertEquals("mine now", documents.page(block.pageId).blocks.single().content["text"])
	}

	@Test
	fun `a late release does not free the new holder's block`() {
		val (_, block) = onePage()
		lockRows.take(block.id, admin.id, Duration.ofMillis(2))
		Thread.sleep(20)
		locks.take(other, block.id)

		// Élie's tab wakes up and blurs. The claim it is releasing is not the one on the row.
		locks.release(admin, block.id)

		val still = lockRows.liveFor(block.id)
		assertNotNull(still, "Marie's lock survives somebody else's late blur")
		assertEquals(other.id, still.userId)
	}

	@Test
	fun `releasing a lock nobody holds is not an error`() {
		val (_, block) = onePage()
		locks.release(admin, block.id)
	}

	// --- what the page read says ---------------------------------------------

	@Test
	fun `the page read carries the holder onto the block, and nothing onto the others`() {
		val team = newTeam()
		val page = documents.createPage(admin, team.id, null, "Mirror architecture", null).page
		val held = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("held"), null)
		val free = blocks.addBlock(admin, page.id, DocBlockKind.PARAGRAPH, paragraph("free"), null)
		locks.take(admin, held.id)

		val detail = documents.page(page.id)

		assertEquals("Élie", detail.locks[held.id]?.displayName)
		assertNull(detail.locks[free.id], "an unheld block has no holder, not an expired one")
	}

	@Test
	fun `taking a lock does not count as editing the page`() {
		val (page, block) = onePage()
		val before = documents.page(page.id).page

		locks.take(admin, block.id)
		locks.release(admin, block.id)

		val after = documents.page(page.id).page
		// `V38`'s rule, applied to the gesture that would otherwise break it: resting a
		// caret in a paragraph would float this page to the top of screen 22's "recently
		// changed" list for having been *read*.
		assertEquals(before.updatedAt, after.updatedAt)
		assertEquals(before.editedById, after.editedById)
	}
}
