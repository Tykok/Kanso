package dev.kanso.api

import dev.kanso.auth.CurrentUser
import dev.kanso.sync.notion.NotionPeople
import dev.kanso.sync.notion.PeopleView
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * The workspace's members, next to the Kanso accounts they might be.
 *
 * The read is open to anyone who can see the app — matching identities is not a
 * secret — but reconfiguring them is not: writing this table decides whose work a
 * Notion edit lands on, which is the same instance-wide stake connecting Notion at
 * all carries, so it is guarded the same way.
 */
@RestController
@RequestMapping("/api/notion/people")
class NotionPeopleController(private val people: NotionPeople, private val currentUser: CurrentUser) {

	@GetMapping fun view(): PeopleView = people.view()

	@PutMapping
	fun link(@RequestBody assignments: Map<String, UUID?>): PeopleView {
		people.link(currentUser.require(), assignments)
		return people.view()
	}
}
