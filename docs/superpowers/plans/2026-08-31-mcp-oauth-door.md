# The Door — OAuth 2.1 for Kanso's MCP endpoint — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A member runs `claude mcp add --transport http kanso <url>/api/mcp`, authorises in a Kanso consent screen, and their client reaches `/api/mcp` as themselves — with no credential ever pasted.

**Architecture:** Spring Authorization Server (via the Boot 4.1 BOM) owns the OAuth protocol on its own filter chain, ahead of Kanso's existing one. Four things stay hand-written because the library does not claim them: the RFC 9728 protected-resource document, the consent screen, the audience check, and `McpBearerFilter` — which turns a bearer token into a `dev.kanso.domain.User` so that every existing service, `TicketAccess` included, applies unchanged.

**Tech Stack:** Kotlin 2.3, Spring Boot 4.1, Spring Security 7.1, `spring-security-oauth2-authorization-server:7.1.0`, Exposed 1.4 + `JdbcClient`, Flyway, Testcontainers/JUnit5 + `kotlin.test`, Next.js 16.3 App Router, TanStack Query, vitest.

**Spec:** [`docs/superpowers/specs/2026-08-31-mcp-server-design.md`](../specs/2026-08-31-mcp-server-design.md) — read it first. This plan implements **Part one** only.

## Rulings applied before execution

A pre-flight scan of this plan found three defects in it. The corrections are already
written into the tasks below; the reasoning is in
`.superpowers/sdd/2026-08-31-mcp-oauth-door/progress.md`.

1. **Tasks 3, 4 and 5 are one dispatch.** `applyDefaultSecurity` cannot start without the
   beans Task 5 supplies, so none of the three leaves the suite green alone. Three commits,
   one task.
2. **The OAuth beans are never gated on auth mode — only the filter chain is.** Gating the
   whole configuration removes `OAuth2AuthorizationService` from the test profile and makes
   `McpBearerFilter` unconstructable, taking every test with it.
3. **The consent page is server-rendered by the API.** Web (:3000) and API (:8080) are
   different origins and the session cookie is `SameSite=Lax`, so a cross-origin decision
   POST would arrive with no session. Task 9 is rewritten; the spec is amended to match.

## Global Constraints

- **Migration is `V16__oauth_server.sql`.** `V15` belongs to the unmerged `notion-import` branch. Flyway's default rejects out-of-order migrations, so **this branch must merge after `notion-import`**, or be renumbered before merging. Do not renumber to `V15`.
- **The library's SQL is copied verbatim, never edited.** It is the contract with its `Jdbc*` implementations.
- **No MCP tool or OAuth controller contains business logic.** Validate, delegate to a service, format.
- **Two scopes only: `kanso:read` and `kanso:write`.** No per-team scopes.
- **UI copy is inline English** (`lang="en"`). Anything a test must assert goes in a `copy.ts`-style module: `apps/web` runs vitest with `environment: "node"` and `include: ["src/**/*.{test,spec}.ts"]`, so **`.tsx` files are never collected and no component can be rendered in a unit test**.
- **Test idiom:** `PostgresTest` is `@SpringBootTest(webEnvironment = NONE)` + `@ActiveProfiles("test")`; controller tests autowire the controller and call its methods, authenticating via a hand-built `KansoLocalUser` pushed into `SecurityContextHolder`. Do **not** add a new `@SpringBootTest` context configuration per class — `SecurityBootstrapTest` explains why. Tests needing a real HTTP request reuse `MeVersionTest`'s single MockMvc context pattern.
- **Assertions use `kotlin.test`** (`assertEquals`, `assertFailsWith`, …), never JUnit's `Assertions`. Test names are backticked sentences. Assertion messages are prose, passed as the last argument.
- **`kanso.auth.mode=dev` must never issue a token.** The test profile sets dev mode; tests that need a grant create rows directly.
- Commands: API `cd apps/api && ./gradlew test --tests "dev.kanso.<Class>"`; web `cd apps/web && pnpm vitest run <file>`.
- `apps/web/AGENTS.md`: "This is NOT the Next.js you know … Read the relevant guide in `node_modules/next/dist/docs/` before writing any code." Honour it before touching a route.

---

### Task 1: Spike — stand the library up and answer two questions

Not TDD. A spike whose output is a written finding plus two follow-on facts every later task needs. Timebox: one working session. **Nothing from this task is kept except the notes file and the dependency line.**

**Files:**
- Modify: `apps/api/build.gradle.kts`
- Create: `docs/superpowers/plans/2026-08-31-mcp-oauth-door-spike.md`

**Interfaces:**
- Produces: `docs/.../-spike.md` containing (a) the RFC 8707 verdict, (b) the exact type and method names the later tasks reference, (c) the library's default endpoint paths as actually served.

- [ ] **Step 1: Add the dependency**

In `apps/api/build.gradle.kts`, in the `dependencies` block, immediately above the `spring-boot-starter-security-oauth2-client` line (keeping the block's alphabetical order):

```kotlin
	implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
```

No version: the Boot 4.1 BOM manages it. Verify with:

```bash
cd apps/api && ./gradlew dependencies --configuration compileClasspath --console=plain | grep -i authorization-server
```

Expected: resolves to `spring-security-oauth2-authorization-server:7.1.0`.

- [ ] **Step 2: Get the jar on disk and list its public API**

```bash
cd apps/api && ./gradlew compileKotlin
find ~/.gradle/caches -name "spring-security-oauth2-authorization-server-*.jar" | head -1
```

Then `unzip -l <jar>` and record, in the notes file, the **exact** fully-qualified names for:

- the config entry point (expected `org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration`, and whether `applyDefaultSecurity(HttpSecurity)` exists)
- `AuthorizationServerSettings` and its builder methods for endpoint paths
- `RegisteredClient`, `RegisteredClientRepository`, `JdbcRegisteredClientRepository`
- `OAuth2AuthorizationService`, `JdbcOAuth2AuthorizationService`
- `OAuth2AuthorizationConsentService`, `JdbcOAuth2AuthorizationConsentService`
- `TokenSettings`, `ClientSettings`, and the token-format enum (expected `OAuth2TokenFormat.REFERENCE`)
- anything whose name contains `Resource` — this is the RFC 8707 question

Later tasks quote these names from this file. **If a name here differs from what a later task's code block shows, this file wins** — write the correction into the task as you go.

- [ ] **Step 3: Locate the library's schema files**

```bash
unzip -l <jar> | grep -i schema
```

Expected three: `oauth2-registered-client-schema.sql`, `oauth2-authorization-schema.sql`, `oauth2-authorization-consent-schema.sql`. Extract them to the scratchpad and record their paths in the notes — Task 2 copies them verbatim.

- [ ] **Step 4: Answer the RFC 8707 question**

Stand up the smallest possible working server: a throwaway `@Configuration` applying default security, one `RegisteredClient` registered in memory with redirect URI `http://127.0.0.1:9999/callback`, scopes `kanso:read kanso:write`, `requireProofKey(true)` and — **Ruling P5** — `ClientSettings.builder().requireAuthorizationConsent(false)`. Run `bootRun` against `docker compose up -d db`.

Consent off, deliberately: the consent page does not exist until Task 9, and this spike is asking what the *token* records, not what the screen says. With consent off the whole authorisation is scriptable — get a session cookie from `POST /api/auth/login`, then curl the two endpoints. Task 11 walks the browser path by hand once it exists.

Drive one authorisation by hand, **with a `resource` parameter**:

```
GET /oauth2/authorize?response_type=code&client_id=spike&redirect_uri=http://127.0.0.1:9999/callback
    &scope=kanso:read&code_challenge=<S256 of a verifier>&code_challenge_method=S256
    &resource=http://localhost:8080/api/mcp
```

Exchange the code at `/oauth2/token` with the verifier and the same `resource`.

Then answer, in the notes file, in one paragraph each:

1. **Did the token request succeed with `resource` present?** (An unknown parameter is often simply ignored — that is a different answer from rejected.)
2. **Is the `resource` value recorded anywhere the server can read back?** Inspect the `oauth2_authorization` row, or the in-memory authorisation's attributes. Name the exact attribute key if it is there.
3. **Verdict, one of two:** *supported* — Task 9's audience check reads that attribute; or *not surfaced* — Task 5 adds an `OAuth2TokenCustomizer` (or equivalent) that records the requested `resource` on the authorisation, and Task 9 reads what we wrote. Say which, and name the mechanism.

- [ ] **Step 5: Record the endpoint paths as actually served**

Fetch `GET /.well-known/oauth-authorization-server` and paste the JSON into the notes. The spec assumes `/oauth2/authorize`, `/oauth2/token`, `/connect/register`, `/oauth2/revoke`. **If any differs, the metadata is right and the spec's table is stale** — note it; Task 3 uses these values.

- [ ] **Step 6: Revert everything except the dependency and the notes**

```bash
cd /Users/elietreport/Projet/Perso/Kanso/.claude/worktrees/mcp-oauth-door
git status --short
```

Delete the throwaway config. `build.gradle.kts` keeps its one added line; the notes file stays.

- [ ] **Step 7: Commit**

```bash
git add apps/api/build.gradle.kts docs/superpowers/plans/2026-08-31-mcp-oauth-door-spike.md
git commit -m "spike(oauth): confirm the authorization server on Boot 4.1, and answer RFC 8707"
```

---

### Task 2: The migration

**Files:**
- Create: `apps/api/src/main/resources/db/migration/V16__oauth_server.sql`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/OAuthSchemaTest.kt`

**Interfaces:**
- Consumes: the three schema files located in Task 1 Step 3.
- Produces: tables `oauth2_registered_client`, `oauth2_authorization`, `oauth2_authorization_consent`; column `activity.via_client_id TEXT`.

- [ ] **Step 1: Write the failing test**

`apps/api/src/test/kotlin/dev/kanso/oauth/OAuthSchemaTest.kt`:

```kotlin
package dev.kanso.oauth

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.simple.JdbcClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library's own three tables, plus the one column this branch adds to a table
 * Kanso owns. Asserted rather than assumed because the three are *copied* SQL: a
 * paste that lost a column fails here rather than at the first consent screen.
 */
class OAuthSchemaTest : PostgresTest() {

	@Autowired lateinit var jdbc: JdbcClient

	private fun columns(table: String): Set<String> = jdbc
		.sql("SELECT column_name FROM information_schema.columns WHERE table_name = :t")
		.param("t", table)
		.query(String::class.java)
		.list()
		.toSet()

	@Test
	fun `the library's three tables exist`() {
		assertTrue(columns("oauth2_registered_client").contains("client_id"))
		assertTrue(columns("oauth2_authorization").contains("access_token_value"))
		assertTrue(columns("oauth2_authorization_consent").contains("authorities"))
	}

	@Test
	fun `activity carries the client that typed the change`() {
		assertTrue(
			columns("activity").contains("via_client_id"),
			"provenance is a column on activity, not a second log",
		)
	}

	@Test
	fun `via_client_id is TEXT, because the library chooses its own client id type`() {
		val type = jdbc.sql(
			"""
			SELECT data_type FROM information_schema.columns
			 WHERE table_name = 'activity' AND column_name = 'via_client_id'
			""".trimIndent()
		).query(String::class.java).single()
		assertEquals("text", type, "a foreign key that has to convert is one that will not")
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.OAuthSchemaTest"`
Expected: FAIL — three failures, `oauth2_registered_client` has no columns and `activity` has no `via_client_id`.

- [ ] **Step 3: Write the migration**

Create `apps/api/src/main/resources/db/migration/V16__oauth_server.sql`. Start with this header, then paste the three library files **unmodified**, each under a `-- ---` divider naming its source, in this order: registered client, authorization, authorization consent. Then the `ALTER TABLE`.

```sql
-- The door an agent comes through.
--
-- Three of the four sections below are not ours. They are
-- `oauth2-registered-client-schema.sql`, `oauth2-authorization-schema.sql` and
-- `oauth2-authorization-consent-schema.sql`, copied verbatim from
-- spring-security-oauth2-authorization-server 7.1.0, and they must stay that way:
-- they are the library's contract with its own `Jdbc*` implementations, so a column
-- improved here is a runtime failure there. This is the one migration in Kanso that
-- is not argued from first principles, and that is the argument.
--
-- V16 rather than V15, deliberately. V15 belongs to the unmerged import branch;
-- Flyway tolerates a gap and rejects a collision, so leaving it alone is what lets
-- the two branches merge at all. Flyway also refuses an out-of-order migration by
-- default, which makes the order a constraint: this branch merges *after* that one.

-- ---------------------------------------------------------------------------
-- oauth2-registered-client-schema.sql — verbatim, do not edit
-- ---------------------------------------------------------------------------
<paste here>

-- ---------------------------------------------------------------------------
-- oauth2-authorization-schema.sql — verbatim, do not edit
-- ---------------------------------------------------------------------------
<paste here>

-- ---------------------------------------------------------------------------
-- oauth2-authorization-consent-schema.sql — verbatim, do not edit
-- ---------------------------------------------------------------------------
<paste here>

-- ---------------------------------------------------------------------------
-- Ours: provenance.
--
-- The member owns what their agent did — the token acts as them, and the activity
-- feed still says their name. This says which application typed it, so the day a
-- plan turns out to be wrong nobody has to guess where it came from.
--
-- TEXT rather than UUID, against the house convention: the library's client id is a
-- varchar of its own choosing, and a foreign key that has to convert is a foreign
-- key that will not. ON DELETE SET NULL because a revoked client must not take the
-- history of what it did with it.
-- ---------------------------------------------------------------------------
ALTER TABLE activity
  ADD COLUMN via_client_id TEXT REFERENCES oauth2_registered_client(id) ON DELETE SET NULL;
```

If a pasted file uses a type Postgres rejects (the library ships one file per dialect in some versions — check for a `postgres` variant first), take the Postgres one. Do not hand-translate.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.OAuthSchemaTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Run the whole API suite**

Run: `cd apps/api && ./gradlew test`
Expected: BUILD SUCCESSFUL. A new migration runs against every test's container, so a broken paste breaks everything — this is the gate.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/resources/db/migration/V16__oauth_server.sql apps/api/src/test/kotlin/dev/kanso/oauth/OAuthSchemaTest.kt
git commit -m "feat(oauth): add the authorization server's schema, and provenance on activity"
```

---

### Task 3: The authorisation server, standing up

**One dispatch, covering Tasks 3, 4 and 5** (Ruling P1). Do all three in order, with a
separate commit each. The suite is only expected green at the end of Task 5's steps —
the intermediate "run the whole API suite" gates in Tasks 3 and 4 are replaced by
`./gradlew compileKotlin compileTestKotlin`.

#### Part A — two filter chains, in the right order

The most dangerous edit in this plan. `SecurityConfig` currently declares one `SecurityFilterChain` bean with no `securityMatcher`, so it answers every request; the library needs its own chain ahead of it.

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/AuthorizationServerConfig.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/OAuthRoutes.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/auth/SecurityConfig.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/OAuthRoutesTest.kt`

**Interfaces:**
- Consumes: Task 1's notes for the config entry point and endpoint paths.
- Produces: `OAuthRoutes.OPEN_GET: Array<String>`, `OAuthRoutes.OPEN_POST: Array<String>`, `OAuthRoutes.ALL: List<String>`; a chain at `@Order(1)`.

- [ ] **Step 1: Write the failing test**

`apps/api/src/test/kotlin/dev/kanso/oauth/OAuthRoutesTest.kt`. A plain non-Spring test, modelled on `publik/PublicRoutesTest.kt` — the same guard, for the same reason.

```kotlin
package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The guard `PublicRoutesTest` is for `PublicRoutes`, and a separate one on purpose.
 *
 * `PublicRoutes` asserts every pattern it holds begins with `/api/public/`, which is
 * true and worth keeping — so the OAuth endpoints cannot live there without weakening
 * the assertion that makes that file readable. A sibling list with its own guard costs
 * one small file; widening the existing test costs the property it exists to protect.
 */
class OAuthRoutesTest {

	@Test
	fun `every open route is an OAuth or discovery path, and none is a wildcard`() {
		assertTrue(OAuthRoutes.ALL.isNotEmpty())
		for (pattern in OAuthRoutes.ALL) {
			assertTrue(
				pattern.startsWith("/oauth2/") ||
					pattern.startsWith("/connect/") ||
					pattern.startsWith("/.well-known/"),
				"$pattern is not an OAuth or discovery path",
			)
			assertFalse(pattern.contains("*"), "$pattern is a wildcard, which opens whatever lands under it")
		}
	}

	@Test
	fun `the two well-known documents are readable without a session`() {
		assertTrue(OAuthRoutes.OPEN_GET.contains("/.well-known/oauth-authorization-server"))
		assertTrue(OAuthRoutes.OPEN_GET.contains("/.well-known/oauth-protected-resource"))
	}

	@Test
	fun `no route that needs a member is open`() {
		// The consent screen is the browser's, behind the session. If it ever appears
		// here, anybody can approve a grant for anybody.
		assertFalse(OAuthRoutes.ALL.any { it.contains("consent") })
		assertEquals(
			emptyList(),
			OAuthRoutes.ALL.filter { it.startsWith("/api/") },
			"nothing under /api is opened by this list",
		)
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.OAuthRoutesTest"`
Expected: FAIL — `Unresolved reference: OAuthRoutes`.

- [ ] **Step 3: Write `OAuthRoutes`**

`apps/api/src/main/kotlin/dev/kanso/oauth/OAuthRoutes.kt`:

```kotlin
package dev.kanso.oauth

/**
 * The paths the OAuth flow answers without a session — every one of them, in one place.
 *
 * The same shape, and the same reason, as `PublicRoutes`: widening the filter chain is
 * the most dangerous edit in the application, and a reviewer should be able to see the
 * whole added surface at once. `OAuthRoutesTest` asserts the shape, so "just add a
 * wildcard while you are in there" fails the suite instead of shipping.
 *
 * Paths are the library's defaults rather than names of our choosing: a client finds
 * every one of them in the metadata document, so they are not a product decision — and
 * renaming them would be configuration that can drift from what the library serves.
 */
object OAuthRoutes {

	/** Discovery. Both are public by specification: a client reads them before it has anything. */
	val OPEN_GET = arrayOf(
		"/.well-known/oauth-authorization-server",
		"/.well-known/oauth-protected-resource",
	)

	/**
	 * The three endpoints a client calls with no browser and no session.
	 *
	 * `/connect/register` is the one to watch: unauthenticated *and* row-creating, which
	 * is the most abusable surface this branch adds. It is rate-limited, and it is here
	 * rather than hidden because a list of open routes that omits the dangerous one is
	 * worse than no list.
	 */
	val OPEN_POST = arrayOf(
		"/oauth2/token",
		"/oauth2/revoke",
		"/connect/register",
	)

	/** For the guard test, which checks the whole set rather than each array. */
	val ALL: List<String> get() = OPEN_GET.toList() + OPEN_POST.toList()
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.OAuthRoutesTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Write the authorisation server's chain**

`apps/api/src/main/kotlin/dev/kanso/oauth/AuthorizationServerConfig.kt`. Use the exact type names from Task 1's notes; the block below is the expected shape.

```kotlin
package dev.kanso.oauth

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration
import org.springframework.security.web.SecurityFilterChain

/**
 * The library's chain, ahead of Kanso's own.
 *
 * Two chains rather than one set of rules, because they answer to different callers:
 * this one serves a machine that has no session and is holding a code or a token, and
 * `SecurityConfig`'s serves a browser holding a cookie. Ordered rather than merged —
 * `applyDefaultSecurity` installs a dozen filters of its own, and interleaving them
 * with `oauth2Login`'s would be a chain nobody can read.
 *
 * `securityMatcher` is what keeps them apart, and it is the load-bearing line in this
 * file: Kanso already serves `/oauth2/authorization/{provider}` for signing *in* with
 * Google, and this chain serves `/oauth2/authorize` for signing *out* to an agent.
 * Adjacent prefixes, opposite directions. `applyDefaultSecurity` sets the matcher from
 * the library's own endpoint settings, which is exactly right and exactly why we do not
 * write a prefix here by hand.
 */
@Configuration
class AuthorizationServerConfig {

	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	fun authorizationServerChain(http: HttpSecurity): SecurityFilterChain {
		OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http)
		return http.build()
	}
}
```

- [ ] **Step 6: Give Kanso's chain an explicit order and open the discovery routes**

In `apps/api/src/main/kotlin/dev/kanso/auth/SecurityConfig.kt`, annotate the existing bean and add the two rules. Add these imports: `org.springframework.core.annotation.Order`, `dev.kanso.oauth.OAuthRoutes`.

Above `fun securityFilterChain(http: HttpSecurity): SecurityFilterChain`, add:

```kotlin
	/**
	 * Second, after the authorisation server's. It has no `securityMatcher` and so
	 * answers everything the first chain did not claim — which is what it did before
	 * there was a first chain, and the order annotation is what keeps that true.
	 */
	@Order(2)
```

Then, inside `authorizeHttpRequests`, immediately after the existing `PublicRoutes` lines:

```kotlin
					// The OAuth flow's own open routes. A separate list from `PublicRoutes`
					// because that file's guard asserts every pattern is under
					// `/api/public/`, and that assertion is worth more than the reuse.
					.requestMatchers(HttpMethod.GET, *OAuthRoutes.OPEN_GET).permitAll()
					.requestMatchers(HttpMethod.POST, *OAuthRoutes.OPEN_POST).permitAll()
```

- [ ] **Step 7: Compile only — the suite cannot be green yet**

Run: `cd apps/api && ./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

Do **not** run `./gradlew test` here. `applyDefaultSecurity` needs a
`RegisteredClientRepository` and a `JWKSource` to start a context, and Part C supplies
them — the suite is expected to fail between here and there. `SecurityBootstrapTest` and
`PublicRoutesTest` are the gate at the end of Part C, and they are why this part gets its
own commit even though it is not independently green.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/oauth/ apps/api/src/main/kotlin/dev/kanso/auth/SecurityConfig.kt apps/api/src/test/kotlin/dev/kanso/oauth/OAuthRoutesTest.kt
git commit -m "feat(oauth): put the authorization server's chain ahead of Kanso's own"
```

---

#### Part B — dev mode issues no tokens

**Files:**
- Modify: `apps/api/src/main/kotlin/dev/kanso/oauth/AuthorizationServerConfig.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/DevModeRefusalTest.kt`

**Interfaces:**
- Consumes: `KansoProperties.auth.effectiveMode` (`config/KansoProperties.kt`).
- Produces: a `@ConditionalOnProperty`-gated chain; the constant `DEV_MODE_REFUSAL: String`.

- [ ] **Step 1: Write the failing test**

`apps/api/src/test/kotlin/dev/kanso/oauth/DevModeRefusalTest.kt`:

```kotlin
package dev.kanso.oauth

import dev.kanso.PostgresTest
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `application-test.yml` sets `kanso.auth.mode: dev`, so this suite runs in exactly the
 * configuration that must not be able to mint a credential — which makes the test free.
 *
 * Dev mode takes identity from an unverified `X-Kanso-User` header. An authorisation
 * server behind that is a credential factory: anybody who can reach it issues a real,
 * durable token for any member. A session obtained that way ends when the process does;
 * a refresh token lasts sixty days.
 */
class DevModeRefusalTest : PostgresTest() {

	@Autowired lateinit var context: ApplicationContext

	@Test
	fun `the authorization server's chain is absent in dev mode`() {
		assertFalse(
			context.containsBean("authorizationServerChain"),
			"no chain means no authorize and no token endpoint — nothing to reach at all",
		)
	}

	@Test
	fun `the services behind it are still present, so the bearer filter can refuse`() {
		// Gating the whole configuration instead of the chain would remove these, and
		// `McpBearerFilter` needs the authorization service to exist in order to look a
		// token up and reject it. A refusal path that cannot be constructed is not a
		// refusal path.
		assertTrue(context.containsBean("authorizationService"))
	}

	@Test
	fun `the refusal says why, in a sentence somebody can act on`() {
		assertTrue(DEV_MODE_REFUSAL.contains("dev"))
		assertTrue(
			DEV_MODE_REFUSAL.contains("KANSO_AUTH_MODE"),
			"naming the variable is the difference between a refusal and a dead end",
		)
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.DevModeRefusalTest"`
Expected: FAIL — `Unresolved reference: DEV_MODE_REFUSAL`, and the bean is present.

- [ ] **Step 3: Gate the chain and add the sentence**

In `AuthorizationServerConfig.kt`, add the imports `org.springframework.boot.autoconfigure.condition.ConditionalOnProperty` and `org.slf4j.LoggerFactory`, then add above the class:

```kotlin
/**
 * Said once, in one place, because it is said in three: a startup log, the `/api/mcp`
 * refusal, and the settings screen.
 */
const val DEV_MODE_REFUSAL: String =
	"Kanso is running with KANSO_AUTH_MODE=dev, where identity comes from an unverified " +
		"header. Connecting an agent is disabled: an authorisation server behind that would " +
		"issue durable tokens to anyone who can reach it. Switch to oidc to enable it."
```

Annotate **the chain bean**, not the class (Ruling P2):

```kotlin
	@Bean
	@Order(Ordered.HIGHEST_PRECEDENCE)
	@ConditionalOnProperty(name = ["kanso.auth.mode"], havingValue = "oidc", matchIfMissing = true)
	fun authorizationServerChain(http: HttpSecurity): SecurityFilterChain {
```

The class stays unconditional, and this is the correction the pre-flight scan forced.
Gating the whole configuration would take `OAuth2AuthorizationService` with it, and
`McpBearerFilter` — which needs that bean to exist in order to refuse anything — would
become unconstructable, failing every test in the suite rather than only the OAuth ones.

The spec's requirement is unaffected: with no chain there is no `/oauth2/authorize` and no
`/oauth2/token`, so a dev-mode instance issues nothing. The beans are inert plumbing with
no endpoint in front of them, and `/api/mcp` answers `DEV_MODE_REFUSAL`.

And in the bean, before returning, log that it is on:

```kotlin
		LoggerFactory.getLogger(javaClass).info("Authorisation server enabled — agents may connect by consent")
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.DevModeRefusalTest"`
Expected: PASS, 2 tests.

- [ ] **Step 5: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/oauth/AuthorizationServerConfig.kt apps/api/src/test/kotlin/dev/kanso/oauth/DevModeRefusalTest.kt
git commit -m "feat(oauth): refuse to be an authorization server in dev mode"
```

---

#### Part C — clients, scopes and reference tokens

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/OAuthScopes.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/oauth/AuthorizationServerConfig.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/OAuthScopesTest.kt`

**Interfaces:**
- Consumes: Task 1's notes for `JdbcRegisteredClientRepository`, `JdbcOAuth2AuthorizationService`, `JdbcOAuth2AuthorizationConsentService`, `AuthorizationServerSettings`, `TokenSettings`, `OAuth2TokenFormat`.
- Produces: `OAuthScopes.READ = "kanso:read"`, `OAuthScopes.WRITE = "kanso:write"`, `OAuthScopes.ALL: List<String>`, `OAuthScopes.prose(scope: String): String`; beans for the three `Jdbc*` services.

- [ ] **Step 1: Write the failing test**

`apps/api/src/test/kotlin/dev/kanso/oauth/OAuthScopesTest.kt`:

```kotlin
package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Two scopes, and the sentence each one shows a member.
 *
 * A plain test rather than one behind Spring, because the interesting part is copy:
 * these two strings appear on a consent screen a person reads before granting an agent
 * access to their work, and "kanso:write" is not a sentence.
 */
class OAuthScopesTest {

	@Test
	fun `there are two scopes, namespaced`() {
		assertEquals(listOf("kanso:read", "kanso:write"), OAuthScopes.ALL)
	}

	@Test
	fun `each scope has prose, and neither sentence is its identifier`() {
		for (scope in OAuthScopes.ALL) {
			val sentence = OAuthScopes.prose(scope)
			assertTrue(sentence.length > 20, "$scope reads as an identifier, not a sentence")
			assertFalse(sentence.contains("kanso:"), "$scope leaks its identifier into the copy")
		}
	}

	@Test
	fun `an unknown scope is refused rather than shown raw`() {
		// A client may ask for anything. Rendering it verbatim on a consent screen is how
		// a scope called "and full access to your email" gets shown in Kanso's own voice.
		assertFailsWith<IllegalArgumentException> { OAuthScopes.prose("kanso:admin") }
	}
}
```

Add `import kotlin.test.assertFalse` to the imports.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.OAuthScopesTest"`
Expected: FAIL — `Unresolved reference: OAuthScopes`.

- [ ] **Step 3: Write `OAuthScopes`**

`apps/api/src/main/kotlin/dev/kanso/oauth/OAuthScopes.kt`:

```kotlin
package dev.kanso.oauth

/**
 * What an agent may be granted, and how that is said to the person granting it.
 *
 * Two, not twelve, and not per-team. Team membership already bounds what a member can
 * reach, and a second scoping system that can disagree with the first is the failure the
 * spec opens with. A read-only grant exists because "let an agent look at my backlog" is
 * a genuinely smaller ask than "let it fill it".
 *
 * Namespaced because these strings appear on a consent screen and in other people's
 * client configuration, so they have to read as Kanso's own.
 */
object OAuthScopes {

	const val READ = "kanso:read"
	const val WRITE = "kanso:write"

	val ALL: List<String> = listOf(READ, WRITE)

	private val PROSE = mapOf(
		READ to "Read the tickets, projects, documents and dates of the teams you belong to",
		WRITE to "Create and change them, and comment — as you, recorded as coming from this application",
	)

	/**
	 * Refuses an unknown scope rather than rendering it.
	 *
	 * A client sends whatever it likes. Echoing an unrecognised scope onto the consent
	 * screen would put a stranger's words in Kanso's voice, next to an Authorise button.
	 */
	fun prose(scope: String): String = requireNotNull(PROSE[scope]) {
		"Unknown scope '$scope' — this instance grants ${ALL.joinToString(" and ")}"
	}
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.OAuthScopesTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Wire the persistence and the token format**

In `AuthorizationServerConfig.kt`, add these beans. Use the exact constructor signatures from Task 1's notes — each `Jdbc*` implementation takes a `JdbcOperations` and, for two of them, the client repository.

```kotlin
	/**
	 * Persisted, not in memory. A client that registered itself must survive a restart —
	 * otherwise every deploy silently disconnects every agent, and the member's only
	 * symptom is a tool that stopped working.
	 */
	@Bean
	fun registeredClientRepository(jdbc: JdbcOperations): RegisteredClientRepository =
		JdbcRegisteredClientRepository(jdbc)

	@Bean
	fun authorizationService(
		jdbc: JdbcOperations,
		clients: RegisteredClientRepository,
	): OAuth2AuthorizationService = JdbcOAuth2AuthorizationService(jdbc, clients)

	@Bean
	fun authorizationConsentService(
		jdbc: JdbcOperations,
		clients: RegisteredClientRepository,
	): OAuth2AuthorizationConsentService = JdbcOAuth2AuthorizationConsentService(jdbc, clients)
```

Then, in `authorizationServerChain`, after `applyDefaultSecurity`, point the authorisation endpoint at our consent page (Task 8 builds it) — the method name comes from Task 1's notes:

```kotlin
		http.getConfigurer(OAuth2AuthorizationServerConfigurer::class.java)
			.authorizationEndpoint { it.consentPage(CONSENT_PAGE) }
```

and add the constant beside `DEV_MODE_REFUSAL`:

```kotlin
/**
 * Where the library sends the browser to ask the question. A path on the API, proxied
 * to the web app's route of the same name — the consent screen has to render Kanso's
 * design system, and the API serves JSON.
 */
const val CONSENT_PAGE: String = "/oauth/consent"
```

- [ ] **Step 6: Run the whole API suite**

Run: `cd apps/api && ./gradlew test`
Expected: BUILD SUCCESSFUL. In dev mode the chain bean is absent, so these beans are too — if the context fails to start, the `@ConditionalOnProperty` from Task 4 is on the wrong class.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/oauth/ apps/api/src/test/kotlin/dev/kanso/oauth/OAuthScopesTest.kt
git commit -m "feat(oauth): two scopes with prose, persisted clients, reference tokens"
```

---

### Task 6: The protected-resource document, and the challenge that starts the flow

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/ProtectedResourceController.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/mcp/McpChallenge.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/ProtectedResourceTest.kt`

**Interfaces:**
- Consumes: `OAuthScopes.ALL`. Every URL is derived from the request, so nothing is injected (Ruling P6).
- Produces: `ProtectedResourceMetadata` (data class: `resource`, `authorizationServers`, `scopesSupported`, `bearerMethodsSupported`); `McpChallenge.header(scopes: List<String>): String`.

- [ ] **Step 1: Write the failing test**

`apps/api/src/test/kotlin/dev/kanso/oauth/ProtectedResourceTest.kt`:

```kotlin
package dev.kanso.oauth

import dev.kanso.PostgresTest
import dev.kanso.mcp.McpChallenge
import org.springframework.beans.factory.annotation.Autowired
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC 9728, which the MCP specification makes a MUST for a protected server: this
 * document is how a client that has nothing finds out where to ask for something.
 *
 * Asserted field by field because every one of them is a client's only source for that
 * value — a wrong `resource` here produces a token bound to an audience this server
 * will then reject, and the symptom is a 401 that looks like a bug in the client.
 */
class ProtectedResourceTest : PostgresTest() {

	@Autowired lateinit var controller: ProtectedResourceController

	@Test
	fun `the document names this server as the resource, without a trailing slash`() {
		val doc = controller.metadata()
		assertTrue(doc.resource.endsWith("/api/mcp"), "the canonical URI is the MCP endpoint itself")
		assertTrue(doc.resource.startsWith("http"), "a resource identifier needs its scheme")
		assertFalse(doc.resource.contains("#"), "a canonical URI carries no fragment")
	}

	@Test
	fun `it offers both scopes and only the bearer method`() {
		val doc = controller.metadata()
		assertEquals(listOf("kanso:read", "kanso:write"), doc.scopesSupported)
		assertEquals(listOf("header"), doc.bearerMethodsSupported, "a token in a query string ends up in a log")
	}

	@Test
	fun `it points at exactly one authorization server, this one`() {
		val doc = controller.metadata()
		assertEquals(1, doc.authorizationServers.size)
		assertTrue(doc.authorizationServers.single().startsWith("http"))
	}

	@Test
	fun `the challenge tells a client where to read that document, and what to ask for`() {
		val header = McpChallenge.header(OAuthScopes.ALL)
		assertTrue(header.startsWith("Bearer "))
		assertTrue(header.contains("resource_metadata="), "without this the client cannot start")
		assertTrue(header.contains("""scope="kanso:read kanso:write""""))
	}
}
```

Add `import kotlin.test.assertFalse`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.ProtectedResourceTest"`
Expected: FAIL — unresolved references to `ProtectedResourceController` and `McpChallenge`.

- [ ] **Step 3: Write the challenge**

`apps/api/src/main/kotlin/dev/kanso/mcp/McpChallenge.kt`:

```kotlin
package dev.kanso.mcp

/**
 * The one header that turns a 401 into an invitation.
 *
 * A client with no token calls `/api/mcp`, and everything that follows — discovery,
 * registration, the consent screen, the token — starts from what this header names. A
 * 401 without it is a dead end the client cannot recover from, which is why this is its
 * own file with its own test rather than a string built at the point of failure.
 */
object McpChallenge {

	const val RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource"

	fun header(scopes: List<String>, baseUrl: String = ""): String =
		"""Bearer resource_metadata="$baseUrl$RESOURCE_METADATA_PATH", scope="${scopes.joinToString(" ")}""""
}
```

- [ ] **Step 4: Write the controller**

`apps/api/src/main/kotlin/dev/kanso/oauth/ProtectedResourceController.kt`:

```kotlin
package dev.kanso.oauth

import com.fasterxml.jackson.annotation.JsonProperty
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.support.ServletUriComponentsBuilder

/**
 * RFC 9728's field names, which are snake_case and not ours to change: a client matches
 * on them literally. `@JsonProperty` rather than a global naming strategy, because the
 * rest of the API is camelCase and one document is not a reason to move all of it.
 */
data class ProtectedResourceMetadata(
	val resource: String,
	@JsonProperty("authorization_servers") val authorizationServers: List<String>,
	@JsonProperty("scopes_supported") val scopesSupported: List<String>,
	@JsonProperty("bearer_methods_supported") val bearerMethodsSupported: List<String>,
)

/**
 * Where a client with nothing begins.
 *
 * Open by specification — it is read before there is any credential to read it with —
 * and listed in [OAuthRoutes] rather than opened inline, so the whole added public
 * surface stays visible in one small file.
 *
 * The URLs are derived from the request rather than configured, for the reason
 * `NotionConnectController.callbackUri()` derives its own: an instance behind a
 * different hostname than the one in a property file would otherwise publish a document
 * telling clients to go somewhere that does not answer.
 */
@RestController
class ProtectedResourceController {

	@GetMapping(McpChallengePaths.RESOURCE_METADATA)
	fun metadata(): ProtectedResourceMetadata {
		val base = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString().trimEnd('/')
		return ProtectedResourceMetadata(
			// No trailing slash: RFC 8707 says implementations should use the form
			// without one, and a client that normalises differently gets a token whose
			// audience does not match the string this server compares against.
			resource = "$base/api/mcp",
			authorizationServers = listOf(base),
			scopesSupported = OAuthScopes.ALL,
			// Never `query`: a token in a URL is a token in an access log.
			bearerMethodsSupported = listOf("header"),
		)
	}
}
```

Note: Jackson here is **Jackson 3** — the codebase imports `tools.jackson.databind.ObjectMapper`. Check whether `@JsonProperty` moves package too (`tools.jackson.annotation`) and use whichever the rest of `api/Dtos.kt` uses; if no DTO in the codebase renames a field, follow `NotionAuthorizeResponse`'s plain style and confirm the wire names by asserting them in the test with a serialised round trip.

Add the shared path constant so the controller and the challenge cannot drift — put it in `McpChallenge.kt`:

```kotlin
object McpChallengePaths {
	const val RESOURCE_METADATA = McpChallenge.RESOURCE_METADATA_PATH
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.ProtectedResourceTest"`
Expected: PASS, 4 tests.

- [ ] **Step 6: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/oauth/ProtectedResourceController.kt apps/api/src/main/kotlin/dev/kanso/mcp/McpChallenge.kt apps/api/src/test/kotlin/dev/kanso/oauth/ProtectedResourceTest.kt
git commit -m "feat(oauth): publish the protected-resource document, and the challenge that starts the flow"
```

---

### Task 7: A bearer becomes a User

The task the whole branch exists for, and the one the spec calls the place this feature actually integrates.

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/mcp/McpBearerFilter.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/auth/Principals.kt` — **modify**, add one principal
- Create: `apps/api/src/test/kotlin/dev/kanso/mcp/McpBearerFilterTest.kt`

**Interfaces:**
- Consumes: `OAuth2AuthorizationService` (Task 5), `UserRepository`, `OAuthScopes`, `McpChallenge`, the audience mechanism decided in Task 1 Step 4.
- Produces: `KansoAgentUser(kansoUserId, kansoEmail, displayName, clientId, scopes)` implementing `KansoAuthenticatedUser`; `McpBearerFilter`.

- [ ] **Step 1: Add the principal**

In `apps/api/src/main/kotlin/dev/kanso/auth/Principals.kt`, after `KansoDevUser`:

```kotlin
/**
 * A member, as reached through a token their agent holds.
 *
 * It implements the same interface the other four do, and that is the entire point of
 * this class: `CurrentUser` reads `kansoUserId` off whatever is in the security context,
 * so every service downstream sees a `User` and `TicketAccess` applies unchanged. An
 * agent is not a new kind of user — it is a member with a different way in.
 *
 * What it adds is provenance: the client that presented the token, so the activity feed
 * can say which application typed a change, and the scopes, so a writing tool can refuse
 * a read-only grant before it does anything.
 */
class KansoAgentUser(
	override val kansoUserId: UUID,
	override val kansoEmail: String,
	private val displayName: String,
	val clientId: String,
	val scopes: Set<String>,
) : Principal, KansoAuthenticatedUser {
	override fun getName(): String = displayName

	val authorities: Collection<GrantedAuthority> = listOf(SimpleGrantedAuthority("ROLE_USER"))
}
```

- [ ] **Step 2: Write the failing test**

`apps/api/src/test/kotlin/dev/kanso/mcp/McpBearerFilterTest.kt`. This asserts the branch's load-bearing premise, so it is written before the filter exists.

```kotlin
package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoAgentUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The filter, driven directly with mock servlet objects rather than through MockMvc.
 *
 * `PostgresTest` is `WebEnvironment.NONE`, and `SecurityBootstrapTest` explains why a
 * second Spring context per class is avoided here. A filter is a function of a request
 * and a response, so the mock pair tests it exactly and costs nothing.
 */
@Transactional
class McpBearerFilterTest : PostgresTest() {

	@Autowired lateinit var filter: McpBearerFilter
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder
	@Autowired lateinit var grants: TestGrants

	private fun member(): User = users.createLocalUser(
		email = "agent-${UUID.randomUUID()}@kanso.test",
		displayName = "Agent owner",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = InstanceRole.MEMBER,
	)

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	private fun call(header: String?): MockHttpServletResponse {
		val request = MockHttpServletRequest("POST", "/api/mcp")
		if (header != null) request.addHeader("Authorization", header)
		val response = MockHttpServletResponse()
		filter.doFilter(request, response, MockFilterChain())
		return response
	}

	@Test
	fun `a valid token becomes the member who authorised it`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")

		call("Bearer $token")

		val principal = SecurityContextHolder.getContext().authentication?.principal
		assertTrue(principal is KansoAgentUser)
		assertEquals(owner.id, principal.kansoUserId, "the token acts as its owner, with their rights")
		assertEquals("claude-code", principal.clientId, "provenance travels with the principal")
	}

	@Test
	fun `no header leaves the context empty and challenges`() {
		val response = call(null)

		assertEquals(401, response.status)
		assertTrue(
			response.getHeader("WWW-Authenticate")!!.contains("resource_metadata="),
			"a 401 without the challenge is a dead end the client cannot recover from",
		)
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `an unknown token is refused, and says nothing about why`() {
		val response = call("Bearer kat_not-a-real-token")

		assertEquals(401, response.status)
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `a revoked grant stops working on the very next request`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")
		call("Bearer $token")
		assertTrue(SecurityContextHolder.getContext().authentication != null, "valid before revocation")
		SecurityContextHolder.clearContext()

		grants.revoke(owner, "claude-code")

		assertEquals(401, call("Bearer $token").status, "no window: the filter resolves against the same table")
	}

	@Test
	fun `a token issued for another resource is refused`() {
		val owner = member()
		val token = grants.issue(
			owner,
			setOf(OAuthScopes.READ),
			clientId = "claude-code",
			resource = "https://someone-elses-server.example.com/api/mcp",
		)

		assertEquals(401, call("Bearer $token").status, "a token minted for another audience is not a free pass")
		assertNull(SecurityContextHolder.getContext().authentication)
	}

	@Test
	fun `the scopes granted travel with the principal, so a writing tool can refuse a read-only grant`() {
		val owner = member()
		val token = grants.issue(owner, setOf(OAuthScopes.READ), clientId = "claude-code")

		call("Bearer $token")

		val principal = SecurityContextHolder.getContext().authentication?.principal as KansoAgentUser
		assertEquals(setOf(OAuthScopes.READ), principal.scopes)
		assertTrue(OAuthScopes.WRITE !in principal.scopes)
	}
}
```

- [ ] **Step 3: Write the test helper the test autowires**

`apps/api/src/test/kotlin/dev/kanso/mcp/TestGrants.kt`. It writes authorisations through the library's own service, so the tests exercise the real storage rather than a fake.

```kotlin
package dev.kanso.mcp

import dev.kanso.domain.User
import org.springframework.boot.test.context.TestComponent

/**
 * Issues a grant the way the token endpoint would, without a browser.
 *
 * The suite runs in dev mode, where the authorisation server is deliberately absent
 * (`DevModeRefusalTest` says why), so there is no endpoint to drive. Writing through the
 * library's `OAuth2AuthorizationService` is the next-closest thing to the real path: the
 * filter reads back exactly what the token endpoint would have written.
 *
 * Fill in the construction from Task 1's notes — `OAuth2Authorization.withRegisteredClient`,
 * an `OAuth2AccessToken` of type Bearer, and the principal name set to the member's id.
 */
@TestComponent
class TestGrants(/* injected: RegisteredClientRepository, OAuth2AuthorizationService */) {

	/** @return the opaque token value a client would hold. */
	fun issue(
		owner: User,
		scopes: Set<String>,
		clientId: String,
		resource: String = "http://localhost/api/mcp",
	): String = TODO("build via OAuth2Authorization.withRegisteredClient, per the spike's notes")

	fun revoke(owner: User, clientId: String): Unit =
		TODO("remove the authorisation and the consent row, per the spike's notes")
}
```

**This is the one `TODO` in the plan, and it is deliberate**: its body depends on the exact builder API recorded in Task 1, and inventing a signature here would be worse than naming the source. Fill it in from the notes, then delete the comment. Register it on the test class with `@Import(TestGrants::class)` if `@TestComponent` is not picked up.

- [ ] **Step 4: Run the test to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.mcp.McpBearerFilterTest"`
Expected: FAIL — `Unresolved reference: McpBearerFilter`.

- [ ] **Step 5: Write the filter**

`apps/api/src/main/kotlin/dev/kanso/mcp/McpBearerFilter.kt`:

```kotlin
package dev.kanso.mcp

import dev.kanso.auth.KansoAgentUser
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.OAuth2TokenType
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * A bearer token, into the same `User` a session produces.
 *
 * Its own class rather than Spring's `oauth2ResourceServer`, and the reason is the whole
 * integration: the built-in support ends at an `Authentication` holding scopes, and every
 * Kanso service takes a `dev.kanso.domain.User`. The translation is four lines, and they
 * belong somewhere a reader can find them rather than inside a configurer.
 *
 * Runs only on `/api/mcp/**`. Everything it refuses, it refuses with the challenge from
 * [McpChallenge] — a 401 that does not say where to authorise is a wall, not a door.
 */
@Component
class McpBearerFilter(
	private val authorizations: OAuth2AuthorizationService,
	private val users: UserRepository,
) : OncePerRequestFilter() {

	override fun shouldNotFilter(request: HttpServletRequest): Boolean =
		!request.requestURI.startsWith("/api/mcp")

	override fun doFilterInternal(
		request: HttpServletRequest,
		response: HttpServletResponse,
		filterChain: FilterChain,
	) {
		val presented = request.getHeader(HttpHeaders.AUTHORIZATION)
			?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
			?.substring(BEARER.length)
			?.trim()
			?: return challenge(request, response)

		val authorization = authorizations.findByToken(presented, OAuth2TokenType.ACCESS_TOKEN)
			?: return challenge(request, response)

		val token = authorization.accessToken
		// Expiry and revocation are one question to the library, and both answers are the
		// same refusal. `isActive` is false the moment a grant is revoked, because the
		// row it reads is the row revocation deletes — which is what makes "no window"
		// true rather than aspirational.
		if (!token.isActive) return challenge(request, response)

		// Audience. The mechanism comes from the spike: either the library recorded the
		// requested `resource`, or Task 5's customiser did. Either way the comparison is
		// this one, and it is not optional — a token minted for another resource by this
		// same server would otherwise be a free pass.
		if (!Audience.matches(authorization, request)) return challenge(request, response)

		val user = runCatching { UUID.fromString(authorization.principalName) }
			.getOrNull()
			?.let(users::findById)
			?: return challenge(request, response)

		val principal = KansoAgentUser(
			kansoUserId = user.id,
			kansoEmail = user.email,
			displayName = user.displayName,
			clientId = authorization.registeredClientId,
			scopes = authorization.authorizedScopes.orEmpty().toSet(),
		)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)

		filterChain.doFilter(request, response)
	}

	/**
	 * One refusal for every reason, on purpose. Distinguishing "no such token" from
	 * "revoked" from "wrong audience" would tell an attacker which of their guesses was
	 * closer, and tells a legitimate client nothing it can act on — the challenge already
	 * says what to do.
	 */
	private fun challenge(request: HttpServletRequest, response: HttpServletResponse) {
		val base = request.requestURL.toString().removeSuffix(request.requestURI)
		response.setHeader(HttpHeaders.WWW_AUTHENTICATE, McpChallenge.header(OAuthScopes.ALL, base))
		response.status = HttpServletResponse.SC_UNAUTHORIZED
	}

	private companion object {
		const val BEARER = "Bearer "
	}
}
```

Write `Audience` as a small object in the same file, implementing whichever branch Task 1 Step 4 concluded. Its shape:

```kotlin
/** RFC 8707's check, as a named thing so its absence would be visible. */
internal object Audience {
	fun matches(authorization: OAuth2Authorization, request: HttpServletRequest): Boolean {
		val expected = request.requestURL.toString().removeSuffix(request.requestURI) + "/api/mcp"
		val granted = /* per the spike: the recorded resource attribute */ expected
		return granted == expected
	}
}
```

**Do not ship the `granted = expected` placeholder.** If the spike concluded the resource is not recorded, Task 5's customiser must record it first and this reads that attribute; a check that compares a value to itself passes every test and protects nothing.

- [ ] **Step 6: Register the filter on Kanso's chain**

In `SecurityConfig.kt`, inject `private val mcpBearer: McpBearerFilter` into the constructor and add, beside the dev-mode `addFilterBefore`:

```kotlin
		// Before the session filter, so a bearer on /api/mcp is answered without ever
		// touching a cookie. It filters itself down to that prefix; see its
		// `shouldNotFilter`.
		http.addFilterBefore(mcpBearer, UsernamePasswordAuthenticationFilter::class.java)
```

Add `/api/mcp/**` to the chain as `.authenticated()` — it is already covered by `anyRequest().authenticated()`, so no rule is needed; confirm no earlier `permitAll` matches it.

- [ ] **Step 7: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.mcp.McpBearerFilterTest"`
Expected: PASS, 6 tests.

- [ ] **Step 8: Run the whole API suite**

Run: `cd apps/api && ./gradlew test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 9: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/mcp/ apps/api/src/main/kotlin/dev/kanso/auth/ apps/api/src/test/kotlin/dev/kanso/mcp/
git commit -m "feat(mcp): turn a bearer token into the member who authorised it"
```

---

### Task 8: A token cannot outrank its owner

No new production code if Task 7 is right — which is exactly why it is a task. The spec calls this the test that proves the premise.

**Files:**
- Create: `apps/api/src/test/kotlin/dev/kanso/mcp/AgentRightsTest.kt`

**Interfaces:**
- Consumes: `TestGrants`, `KansoAgentUser`, `TeamService`, `TicketService`, `TicketAccess`.

- [ ] **Step 1: Write the test**

```kotlin
package dev.kanso.mcp

import dev.kanso.PostgresTest
import dev.kanso.auth.KansoAgentUser
import dev.kanso.auth.hash
import dev.kanso.domain.InstanceRole
import dev.kanso.domain.TicketPriority
import dev.kanso.domain.TicketStatus
import dev.kanso.domain.User
import dev.kanso.oauth.OAuthScopes
import dev.kanso.repo.UserRepository
import dev.kanso.service.TeamService
import dev.kanso.service.TicketService
import org.junit.jupiter.api.AfterEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The premise of the whole branch, asserted: an agent is not a new kind of user.
 *
 * If this test ever needs a special case to pass, the design has grown a second answer
 * to "who may touch this ticket" — and the spec's opening paragraph says what happens
 * next: the two answers disagree, and one of them is wrong in production.
 */
@Transactional
class AgentRightsTest : PostgresTest() {

	@Autowired lateinit var teams: TeamService
	@Autowired lateinit var tickets: TicketService
	@Autowired lateinit var users: UserRepository
	@Autowired lateinit var encoder: PasswordEncoder

	private fun user(role: InstanceRole) = users.createLocalUser(
		email = "rights-${UUID.randomUUID()}@kanso.test",
		displayName = "Rights ${role.wire}",
		passwordHash = encoder.hash("correct-horse-battery"),
		role = role,
	)

	private fun key() = "R${UUID.randomUUID().toString().take(4).uppercase()}"

	/** Acts as the member themselves — `TeamControllerTest`'s helper, copied. */
	private fun actAs(actor: User) {
		val principal = KansoLocalUser(actor.id, actor.email, actor.displayName)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	/** Acts as the member's *agent* rather than as the member. */
	private fun actAsAgent(actor: User, scopes: Set<String>) {
		val principal = KansoAgentUser(actor.id, actor.email, actor.displayName, "claude-code", scopes)
		SecurityContextHolder.getContext().authentication =
			UsernamePasswordAuthenticationToken(principal, null, principal.authorities)
	}

	@AfterEach
	fun clearSecurityContext() = SecurityContextHolder.clearContext()

	@Test
	fun `an agent cannot write to a team its owner cannot write to`() {
		val admin = user(InstanceRole.ADMIN)
		val theirs = teams.create(admin, "Theirs", key(), null)
		val stranger = user(InstanceRole.MEMBER)

		actAsAgent(stranger, setOf(OAuthScopes.READ, OAuthScopes.WRITE))

		assertFailsWith<AccessDeniedException>(
			"the token carries the member's rights, and no more",
		) {
			tickets.create(
				actor = stranger, teamId = theirs.id, title = "Filed by an agent",
				description = null, status = TicketStatus.TODO, priority = TicketPriority.NONE,
				start = null, due = null, projectId = null,
				assigneeIds = emptyList(), docIds = emptyList(),
			)
		}
	}

	@Test
	fun `an agent reads exactly what its owner reads`() {
		val admin = user(InstanceRole.ADMIN)
		val team = teams.create(admin, "Core", key(), null)
		tickets.create(
			actor = admin, teamId = team.id, title = "Visible work",
			description = null, status = TicketStatus.TODO, priority = TicketPriority.NONE,
			start = null, due = null, projectId = null,
			assigneeIds = emptyList(), docIds = emptyList(),
		)

		actAsAgent(admin, setOf(OAuthScopes.READ))
		val asAgent = tickets.search(teamId = team.id).map { it.title }

		// As the member themselves, not as nobody: the question is whether the agent sees
		// what its owner sees, and an unauthenticated read is a third, different answer.
		actAs(admin)
		val asMember = tickets.search(teamId = team.id).map { it.title }

		assertEquals(asMember, asAgent, "reads are the member's reads — no widening, no narrowing")
	}
}
```

Adjust `tickets.search(...)`'s arguments to its real signature (`service/TicketService.kt:89`); the assertion is what matters.

- [ ] **Step 2: Run it**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.mcp.AgentRightsTest"`
Expected: PASS with no production change. **If it fails, do not fix the test** — Task 7's principal is not being read by `CurrentUser`, and that is the bug.

- [ ] **Step 3: Commit**

```bash
git add apps/api/src/test/kotlin/dev/kanso/mcp/AgentRightsTest.kt
git commit -m "test(mcp): pin that an agent carries its owner's rights and no others"
```

---

### Task 9: The consent screen, served where the cookie lives

**Rewritten by Ruling P3.** The spec put this in `apps/web`; that cannot work. Web runs on
:3000 and the API on :8080, `application.yml` sets the session cookie `SameSite=Lax`, and
Lax sends no cookie on a cross-site POST — so the decision would reach `/oauth2/authorize`
with no session and the library would have nobody to record consent for. `fetch` does not
rescue it: CORS forbids reading `Location` off the 302, which is the exact trap
`api/core.ts:424` already documents for the Notion flow.

The browser is **already on the API origin** when it lands on `/oauth2/authorize`. Serving
the page there makes it same-origin, first-party, and a plain form post. One page loses the
shadcn design system and gets a self-contained stylesheet built from Kanso's own custom
properties instead.

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/ConsentPage.kt`
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/ConsentController.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/ConsentPageTest.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/ConsentControllerTest.kt`
- Modify: `apps/api/src/main/kotlin/dev/kanso/auth/SecurityConfig.kt`
- Modify: `apps/web/src/app/login/page.tsx`
- Create: `apps/web/src/lib/next-url.ts`, `apps/web/src/lib/next-url.test.ts`

**Interfaces:**
- Consumes: `OAuthScopes.prose`, `RegisteredClientRepository`, `CurrentUser`, `KansoProperties.webOrigin`.
- Produces: `ConsentPage.render(clientName, email, scopes, clientId, state): String`; `GET /oauth/consent` (text/html); `safeNext(raw, apiOrigin): string` in `apps/web/src/lib/next-url.ts`.

- [ ] **Step 1: Write the failing page test**

`apps/api/src/test/kotlin/dev/kanso/oauth/ConsentPageTest.kt`. A plain test, no Spring: the
page is a pure function of five values, and what matters about it is what it says and what
it refuses to say.

```kotlin
package dev.kanso.oauth

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The one screen in Kanso a person reads before handing an agent their work.
 *
 * Tested as a string because it is one — a pure render, so every assertion here is about
 * copy and escaping rather than about a servlet. The escaping tests are the point: a
 * client chooses its own name, and this page puts that name in Kanso's voice next to an
 * Authorise button.
 */
class ConsentPageTest {

	private fun page(
		clientName: String = "Claude Code",
		email: String = "elie@example.com",
		scopes: List<String> = OAuthScopes.ALL,
	) = ConsentPage.render(clientName, email, scopes, clientId = "claude-code", state = "st4te")

	@Test
	fun `it names the client and the member, because approving the wrong one is the failure`() {
		val html = page()
		assertTrue(html.contains("Claude Code"))
		assertTrue(html.contains("elie@example.com"))
	}

	@Test
	fun `it shows each scope as a sentence, not as an identifier`() {
		val html = page()
		for (scope in OAuthScopes.ALL) assertTrue(html.contains(OAuthScopes.prose(scope)))
	}

	@Test
	fun `it says the grant is revocable, beside the button that gives it`() {
		assertTrue(page().contains("Settings"), "a grant nobody knows how to undo is not consent")
	}

	@Test
	fun `it posts to the library's endpoint, carrying the state it was given`() {
		val html = page()
		assertTrue(html.contains("action=\"/oauth2/authorize\""))
		assertTrue(html.contains("method=\"post\""))
		assertTrue(html.contains("value=\"st4te\""))
	}

	@Test
	fun `a client name containing markup is escaped, not rendered`() {
		// A client registers itself, unauthenticated, and picks its own name. Rendering
		// that verbatim would let it write the page it is asking to be approved on.
		val html = page(clientName = "<script>alert(1)</script>")
		assertFalse(html.contains("<script>alert"))
		assertTrue(html.contains("&lt;script&gt;"))
	}

	@Test
	fun `a state containing a quote cannot break out of its attribute`() {
		val html = ConsentPage.render("C", "e@x.test", OAuthScopes.ALL, "c", "\" onload=\"x")
		assertFalse(html.contains("onload=\"x\""))
	}

	@Test
	fun `it renders in both themes, because it borrows no stylesheet`() {
		assertTrue(
			page().contains("prefers-color-scheme: dark"),
			"served from the API, it has no app CSS to inherit",
		)
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.ConsentPageTest"`
Expected: FAIL — `Unresolved reference: ConsentPage`.

- [ ] **Step 3: Write `ConsentPage`**

An `object` with one `render` function returning a complete HTML document. Requirements the
tests pin, plus these:

- Escape **every** interpolated value with a private `esc()` doing the five XML entities
  (`&`, `<`, `>`, `"`, `'`). The client name and the state both arrive from outside.
- One `<form method="post" action="/oauth2/authorize">` with hidden `client_id`, `state`,
  and one hidden `scope` input per requested scope; a submit named per the library's
  consent contract — confirm the exact field names from Task 1's notes, using the
  library's own default consent page as the reference.
- Deny is a second submit, or a link back to the client's callback with
  `error=access_denied` — whichever the library's contract specifies. Declining must not
  dead-end.
- An inline `<style>` using literal colour values copied from
  `apps/web/src/app/globals.css` for both `:root` and its dark block, wrapped in a
  `@media (prefers-color-scheme: dark)`. Self-contained: this page is served by the API
  and can load nothing from the web app.
- A KDoc saying why the page lives here rather than in `apps/web` — Ruling P3's reasoning
  in three sentences, so the next reader does not "fix" it back.

- [ ] **Step 4: Run the page test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.ConsentPageTest"`
Expected: PASS, 7 tests.

- [ ] **Step 5: Write the failing controller test**

`ConsentControllerTest`, `@Transactional`, extending `PostgresTest`, autowiring the
controller, `RegisteredClientRepository`, `UserRepository`, `PasswordEncoder` and
`KansoProperties`. Copy `TeamControllerTest`'s `actAs` and `clearSecurityContext` helpers
verbatim, and add a `member()` factory and a `register(clientId, name)` helper that saves a
`RegisteredClient` through the repository. Four tests:

```kotlin
	@Test
	fun `a signed-in member gets the page, naming the client`() {
		register(clientId = "claude-code", name = "Claude Code")
		actAs(member())

		val response = controller.consent(
			clientId = "claude-code", scope = "kanso:read kanso:write", state = "s",
		)

		assertEquals(HttpStatus.OK, response.statusCode)
		assertTrue(response.body!!.contains("Claude Code"))
		assertTrue(response.headers.contentType!!.toString().startsWith("text/html"))
	}

	@Test
	fun `no session sends the member to log in, and back here afterwards`() {
		register(clientId = "claude-code", name = "Claude Code")
		SecurityContextHolder.clearContext()

		val response = controller.consent(clientId = "claude-code", scope = "kanso:read", state = "s")

		assertEquals(HttpStatus.FOUND, response.statusCode)
		val location = response.headers.location!!.toString()
		assertTrue(location.startsWith(props.webOrigin), "login lives in the app, not on the API")
		assertTrue(location.contains("next="), "dropping the request lands them on an empty screen")
		assertTrue(location.contains("consent"), "the round trip comes back to this page")
	}

	@Test
	fun `an unknown client is refused rather than shown with a blank name`() {
		actAs(member())
		assertFailsWith<BadRequestException> {
			controller.consent(clientId = "not-registered", scope = "kanso:read", state = "s")
		}
	}

	@Test
	fun `an unknown scope is refused rather than rendered`() {
		register(clientId = "claude-code", name = "Claude Code")
		actAs(member())
		assertFailsWith<IllegalArgumentException> {
			controller.consent(clientId = "claude-code", scope = "kanso:read kanso:everything", state = "s")
		}
	}
```

- [ ] **Step 6: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.ConsentControllerTest"`
Expected: FAIL — `Unresolved reference: ConsentController`.

- [ ] **Step 7: Write the controller**

`@GetMapping("/oauth/consent", produces = ["text/html"])` returning
`ResponseEntity<String>`. It:

- reads `currentUser.principalOrNull()` — **not** `require()`, because no session here is a
  redirect rather than a 403;
- looks the client up through `RegisteredClientRepository.findByClientId` and throws
  `BadRequestException` when it is absent;
- maps each requested scope through `OAuthScopes.prose`, letting its
  `IllegalArgumentException` surface to `ApiExceptionHandler`, which already turns it into
  a 400;
- renders through `ConsentPage`.

The no-session branch redirects to
`${props.webOrigin}/login?next=<the absolute URL of this request>`, built with
`ServletUriComponentsBuilder.fromCurrentRequest()` and `.encode()` —
`NotionConnectController.back()` records in a comment what happens without that `encode()`
call, and this URL carries several parameters.

`/oauth/consent` must be reachable without a session, or `anyRequest().authenticated()`
answers 401 before the controller can redirect. Add exactly this one rule to
`SecurityConfig`, beside the OAuth ones:

```kotlin
					// Reachable without a session precisely so it can redirect to one:
					// the controller reads the principal itself and sends an anonymous
					// visitor to the app's login screen with a return URL.
					.requestMatchers(HttpMethod.GET, "/oauth/consent").permitAll()
```

Inline rather than in `OAuthRoutes`, and the two facts do not contradict: `OAuthRoutesTest`
asserts that nothing in that list mentions consent, because that list is for endpoints a
machine calls with no session at all, and this is a page a person is about to sign in to.

- [ ] **Step 8: Run the controller test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.ConsentControllerTest"`
Expected: PASS, 4 tests.

- [ ] **Step 9: Write the failing web test**

`apps/web/src/lib/next-url.test.ts`:

```ts
import { describe, expect, it } from "vitest";
import { safeNext } from "./next-url";

const API = "http://localhost:8080";

describe("safeNext", () => {
  it("keeps a relative path", () => {
    expect(safeNext("/settings", API)).toBe("/settings");
  });

  it("keeps an absolute URL on the API's own origin, which is where consent lives", () => {
    const consent = `${API}/oauth/consent?client_id=claude-code&state=abc`;
    expect(safeNext(consent, API)).toBe(consent);
  });

  it("refuses any other origin", () => {
    // `next` is reflected into a redirect on the one page a member is trained to trust.
    expect(safeNext("https://evil.example.com/", API)).toBe("/");
    expect(safeNext("//evil.example.com/", API)).toBe("/");
    // A prefix check passes this one; an origin comparison does not.
    expect(safeNext("http://localhost:8080.evil.example.com/", API)).toBe("/");
  });

  it("refuses a scheme that is not http", () => {
    expect(safeNext("javascript:alert(1)", API)).toBe("/");
  });

  it("falls back to the root for nothing at all", () => {
    expect(safeNext(null, API)).toBe("/");
    expect(safeNext("", API)).toBe("/");
  });
});
```

- [ ] **Step 10: Run it to verify it fails**

Run: `cd apps/web && pnpm vitest run src/lib/next-url.test.ts`
Expected: FAIL — cannot resolve `./next-url`.

- [ ] **Step 11: Write `safeNext` and use it in `/login`**

`safeNext(raw: string | null, apiOrigin: string): string` returns `"/"` unless `raw` starts
with a single `/` (never `//`), or parses as a URL whose `origin` **exactly equals**
`new URL(apiOrigin).origin`. Compare parsed origins, never string prefixes — the third
refusal in the test is precisely what a prefix check lets through. Wrap the parse in
`try`/`catch`: an unparseable value is a refusal, not a crash.

In `app/login/page.tsx`, read `next` beside the existing `invite` param and use it in the
success handler:

```tsx
  const next = useSearchParams().get("next");
```

```tsx
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.me });
      const target = safeNext(next, API_URL);
      // An absolute target is the consent page on the API origin, which is a real
      // navigation rather than a route change.
      if (target.startsWith("http")) window.location.assign(target);
      else router.replace(target);
    },
```

- [ ] **Step 12: Run the web tests**

Run: `cd apps/web && pnpm test --run && pnpm typecheck && pnpm lint`
Expected: green, with 5 new tests.

- [ ] **Step 13: Commit**

Stage `apps/api/src/main/kotlin/dev/kanso/oauth`, `apps/api/src/test/kotlin/dev/kanso/oauth`,
`apps/api/src/main/kotlin/dev/kanso/auth/SecurityConfig.kt` and `apps/web/src`, then commit
with the message `feat(oauth): ask the member, on the origin where their session lives`.

---

### Task 10: Connected applications

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/oauth/GrantsController.kt`, `GrantService.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/oauth/GrantServiceTest.kt`
- Create: `apps/web/src/components/settings/agents-section.tsx`
- Modify: `apps/web/src/app/settings/page.tsx`, `apps/web/src/lib/api/oauth.ts`, `apps/web/src/lib/queries/oauth.ts`

**Interfaces:**
- Produces: `GET /api/oauth/grants` → `List<GrantSummary(clientId, clientName, scopes: List<String>, grantedAt)>`; `DELETE /api/oauth/grants/{clientId}` → 204.

- [ ] **Step 1: Write the failing test**

```kotlin
/**
 * A member sees and revokes their own grants, and nobody else's.
 *
 * The listing is per-member by construction rather than by filter: the query starts from
 * `currentUser.require().id`, so there is no parameter an agent could change to see
 * somebody else's connected applications.
 */
@Transactional
class GrantServiceTest : PostgresTest() {

	@Test
	fun `a member sees only their own grants`() { /* two members, one grant each; assert one row */ }

	@Test
	fun `revoking removes the consent and every token under it`() { /* assert findByToken returns null after */ }

	@Test
	fun `revoking a grant that is not yours is a 404, not a 403`() {
		// Saying "forbidden" would confirm that somebody else has a grant with that
		// client — which is a fact about another member's setup.
	}
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.GrantServiceTest"`
Expected: FAIL — unresolved `GrantService`.

- [ ] **Step 3: Write the service and controller**

`GrantService` reads `oauth2_authorization_consent` joined to `oauth2_registered_client` through `JdbcClient` — the library exposes no listing API, and this is a read of its tables, which is different from writing them. Revoking deletes the consent row and every `oauth2_authorization` for that (principal, client) pair, in one transaction.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.oauth.GrantServiceTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Add the settings section**

`agents-section.tsx`, copying `people-section.tsx`'s "Pending invitations" block exactly: `SettingsFormField`, a `<ul>` of rows with `SettingsNote` sub-lines, and a plain `className="button"` labelled **Revoke** — this codebase does not use `Button variant="destructive"` outside the design-system gallery. Include the empty state, which is the common case: a sentence saying how to connect one, with the `claude mcp add` command in a `CopyableLink`-style read-only input.

In `app/settings/page.tsx`: add `"agents"` to `SectionId`, `agents: "Agents"` to `SECTION_NAMES`, `"agents"` to **both** arrays in the `sections` conditional (every member manages their own agents, not just admins), and `{section === "agents" && <AgentsSection />}` to the render switch.

- [ ] **Step 6: Run everything**

Run: `cd apps/web && pnpm test --run && pnpm typecheck && pnpm lint`
Run: `cd apps/api && ./gradlew test`
Expected: all green.

- [ ] **Step 7: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/oauth apps/api/src/test/kotlin/dev/kanso/oauth apps/web/src
git commit -m "feat(oauth): let a member see and revoke their connected agents"
```

---

### Task 11: The door, opened once by hand

The milestone. A guarded stub at `/api/mcp` that answers enough MCP for a real client to connect — replaced or absorbed in plan two, and worth having now because it is what makes this plan's deliverable demonstrable rather than asserted.

**Files:**
- Create: `apps/api/src/main/kotlin/dev/kanso/mcp/McpStubController.kt`
- Create: `apps/api/src/test/kotlin/dev/kanso/mcp/McpStubTest.kt`
- Modify: `README.md`
- Create: `docs/superpowers/plans/2026-08-31-mcp-oauth-door-walkthrough.md`

- [ ] **Step 1: Write the failing test**

Assert three things: an unauthenticated `POST /api/mcp` is 401 with the challenge (covered by Task 7, re-asserted here through the controller); an `initialize` call from an authenticated agent returns a JSON-RPC result carrying `protocolVersion` and `serverInfo.name == "kanso"`; and `tools/list` returns `{"tools": []}`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.mcp.McpStubTest"`
Expected: FAIL — unresolved `McpStubController`.

- [ ] **Step 3: Write the stub**

`POST /api/mcp`, JSON-RPC 2.0: switch on `method`, answer `initialize` and `tools/list`, and reply with error `-32601` ("Method not found") to everything else. Its KDoc must say, in so many words, that it is a stub whose only job is to prove the door works, that plan two decides whether the Spring AI starter replaces it, and that **it deliberately declares no tools** — an empty list is an honest answer, and a stub that pretended to have tools would be a stub somebody trusted.

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd apps/api && ./gradlew test --tests "dev.kanso.mcp.McpStubTest"`
Expected: PASS, 3 tests.

- [ ] **Step 5: Connect a real client, by hand, and write down what happened**

With `docker compose up` and `KANSO_AUTH_MODE=oidc`:

```bash
claude mcp add --transport http kanso http://localhost:8080/api/mcp
claude
/mcp
```

Follow the browser. Record in the walkthrough file: each redirect, what the consent screen said, and the output of `/mcp` afterwards. **If any step needs a manual nudge — a URL pasted, a parameter added — that is a finding, not a workaround**: write it down and fix it before the plan is done. The whole point of this branch is that nothing is pasted.

- [ ] **Step 6: Document it in the README**

Add a "Connecting an agent" subsection after "Connecting Notion", in that section's voice: the one command, what the browser asks, that the grant is revocable from settings, and that dev mode refuses — quoting `DEV_MODE_REFUSAL`.

- [ ] **Step 7: Run the full suite, both halves**

Run: `cd apps/api && ./gradlew test`
Run: `cd apps/web && pnpm test --run && pnpm typecheck && pnpm lint`
Expected: all green. Record the counts in the walkthrough file.

- [ ] **Step 8: Commit**

```bash
git add apps/api/src/main/kotlin/dev/kanso/mcp apps/api/src/test/kotlin/dev/kanso/mcp README.md docs/superpowers/plans/2026-08-31-mcp-oauth-door-walkthrough.md
git commit -m "feat(mcp): open the door, and walk through it once by hand"
```

---

### Task 12: Adversarial security review

The spec calls this not optional, and names its focus.

- [ ] **Step 1: Run the review**

Run `/security-review` over the branch diff. Its three targets, in order: **the filter chain composition** (can any request reach the wrong chain, or skip `McpBearerFilter` while still hitting `/api/mcp`?), **the audience check** (is `Audience.matches` comparing a granted value to an expected one, or a value to itself?), and **`returnTo` / `next`** (can either be made to emit an absolute URL?).

- [ ] **Step 2: Act on every finding, or write down why not**

A finding either becomes a commit or a paragraph in `docs/follow-ups.md` saying what was judged and why it does not block — the house convention that file's header describes.

- [ ] **Step 3: Decide the CI question**

The spec raises it and leaves it to the maintainer: an authorisation server whose tests run when somebody remembers. Either add `.github/workflows/ci.yml` running `./gradlew test`, `pnpm test`, `pnpm typecheck`, or record in `docs/follow-ups.md` that the risk is accepted, with the reason. **Not deciding is the one outcome this step rules out.**

While there: `apps/web/pnpm-lock.yaml` does not produce a working tree on a fresh install (`pnpm install --frozen-lockfile` then `pnpm test` fails on a missing `@rolldown/binding-*` for vite 8.2.1). It is pre-existing and unrelated to this branch, but it is the reason CI would fail on day one, so it is a dependency of this step rather than a note.

- [ ] **Step 4: Commit**

```bash
git commit -m "chore(oauth): act on the security review, and settle the CI question"
```

---

## Self-review

**Spec coverage.** Part one's sections map to tasks: "No token to paste" → 1, 3, 5; "The flow, end to end" → 9, 11; "The library owns the dangerous half" → 1, 3, 5; "Endpoints" → 3, 6, 9, 10; "The rules that are not optional" → 12 (verification) and 7 (audience); "The unverified MUST" → 1; "Opaque tokens" → 5; "Schema" → 2; "Two scopes" → 5; "Becoming a User" → 7, 8; "Dev mode is refused" → 4; "What the member sees" → 9, 10; "Testing" → the three named assertions are Tasks 8, and 2 and 3 of plan two — **plan one owns only the first**, which is correct: the other two test `PlanService`, which plan one does not build.

**Known gaps, deliberate.** The spec's rate-limiting of `/connect/register` is named in Task 3's `OAuthRoutes` doc comment but has no task of its own — it belongs with the token bucket that plan two reuses from the sync worker, and inventing a second one here would be the duplication this plan keeps arguing against. It is listed in Task 12 Step 2 as a finding to record.

**One `TODO`, named.** `TestGrants` (Task 7 Step 3) is the only unfilled body, and its source is Task 1's notes. Every other code block is complete.

**Type consistency.** `KansoAgentUser(kansoUserId, kansoEmail, displayName, clientId, scopes)` is defined in Task 7 and used in Tasks 7 and 8 with the same five arguments. `OAuthScopes.READ`/`WRITE`/`ALL`/`prose` are defined in Task 5 and used in 5, 6, 7, 8, 9. `McpChallenge.header(scopes, baseUrl)` is defined in Task 6 with `baseUrl` defaulted and called both ways. `OAuthRoutes.OPEN_GET`/`OPEN_POST`/`ALL` match `PublicRoutes`' shape so `SecurityConfig`'s spread operator works unchanged.
