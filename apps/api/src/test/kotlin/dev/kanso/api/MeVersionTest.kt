package dev.kanso.api

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.info.BuildProperties
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The version has to come from the build, not from a constant somebody edits. If the
 * build stops generating it, this fails rather than quietly reporting a stale string.
 */
class MeVersionTest : PostgresTest() {

	@Autowired
	lateinit var build: BuildProperties

	@Test
	fun `the build stamps a version the API can report`() {
		// BuildProperties.getVersion() is @Nullable (JSpecify), so a null-safe read
		// is required even though the property is only ever absent if buildInfo()
		// never ran — which is exactly the case this test exists to catch.
		assertTrue(!build.version.isNullOrBlank(), "the build must generate a version")
		assertFalse(build.version == "unknown", "a placeholder is not a version")
	}
}
