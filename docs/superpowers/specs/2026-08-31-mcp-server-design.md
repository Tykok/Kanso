# A door for agents

Kanso has one door today, and a person stands behind it. Every write goes through a
session cookie that an OIDC round trip or a password put there, and every read is
bounded by the teams that cookie's owner belongs to. That door is the right shape for
the product — but it is the only one, and it means the work of turning a design
conversation into a filled backlog is done by hand, twice: once talking, once typing.

This spec opens a second door, for an agent, without weakening the first.

The premise is narrow on purpose. **An agent is not a new kind of user.** It holds a
token issued to a member who authorised it, acts with exactly that member's rights, and
leaves a trail naming the application that did the typing. Nothing in `TicketAccess`,
nothing in the team hierarchy, nothing in the admin rules changes — because the moment
an agent gets its own permission system there are two answers to "who may touch this
ticket", and the two will eventually disagree.

**Kanso never calls a language model.** It serves tools; the client — Claude Code,
claude.ai, the desktop app — talks to the model on its user's own subscription. There is
no API key in Kanso, no inference cost, no outbound dependency on Anthropic. This
matters to state plainly because "adding an MCP server" is easy to mistake for "adding
an LLM integration", and the operational profile of the two is nothing alike.

## What this is not

- **Running Claude locally on a ticket.** That is a Claude Code plugin that *consumes*
  this server, not a capability of it: a server on another host does not start a process
  on somebody's laptop. It gets its own spec, and `kanso_context` is the tool it will
  live on.
- **Notion-specific tools.** The mirror already publishes everything an agent writes. A
  tool writing to Notion directly would be a second writer to a mirror that has exactly
  one, which is the assumption the "Kanso wins" conflict rule rests on.
- **Sub-agent orchestration.** Kanso serves tools. How many agents call them is the
  client's business.
- **Hand-pasted credentials.** Deliberately excluded, and the reason it shapes the whole
  first half of this document: see "No token to paste".

## What already stands, and is reused whole

Read this before designing anything, because the interesting half is already written:

- **The services own the rules.** `TicketService.create` allocates the `KAN-142` number
  from the team's counter inside the insert transaction, writes the activity row, and
  enqueues the sync job. `ProjectService`, `DocService`, `ScheduleService`,
  `BulkEditService` are the same shape. An agent that goes through them gets a ticket
  indistinguishable from one created by pressing `c`.
- **Access is one function.** `TicketAccess` answers who may see and touch what, from the
  recursive team hierarchy. It takes a `User`, so anything that can produce a `User`
  inherits it.
- **The outbox is transactional.** A write enqueues its sync job in the same
  transaction, so a plan that rolls back publishes nothing to Notion.
- **Documents here link tickets at block granularity.** `doc_blocks.kind` includes
  `ticket_link` and `doc_block_tickets` carries the reference, which is what lets a
  document render a live status pill instead of a sentence that was true when somebody
  typed it. `V9__documents.sql` argues this at length; it is the most load-bearing fact
  in this spec.
- **The trash is real.** `trash_entries` makes deletion reversible with a countdown, and
  `TicketTrashSource` routes restore through `TicketService`. An agent's mistake is
  recoverable through a screen that already exists.
- **Half of OAuth is already written, from the other side.** `V14__notion_oauth.sql` and
  `NotionOAuth` exchange a consent screen for a token, store a client id and secret, and
  record which workspace the grant names. Kanso already knows this dance as a *client*.
  This spec makes it a *server*, which is the same protocol with the roles swapped.
- **Errors are three exceptions.** `NotFoundException`, `ConflictException`,
  `BadRequestException` in `service/Errors.kt`, mapped by `ApiExceptionHandler`.

What is missing is a way in that is not a browser session, and a gesture worth making
once you are in.

---

# Part one — the door

## No token to paste

A generated token that the member copies into a client config would be the smallest
possible amount of code, and it is rejected anyway. Two reasons, and the second is the
one that decides it.

The weak reason is user experience: a pasted secret is a secret in a shell history, a
dotfile, and a chat message to a colleague who asked how to set it up.

The deciding reason is that **claude.ai and the desktop app cannot use one.** The MCP
specification requires a protected server to behave as an OAuth 2.1 resource server, and
its clients discover authorisation rather than being configured with it. Supporting a
pasted token would mean the connector works in one client and not the others, which is a
worse place to stand than either extreme. So there is one path in, and it is OAuth.

What the member actually does:

```bash
claude mcp add --transport http kanso https://kanso.example.com/api/mcp
```

and then, in the browser that opens: log into Kanso as usual, read one sentence about
what is being granted, click **Authorise**. Nothing is copied, and the token lives in the
client's own credential store and refreshes itself.

**The consent window is Kanso's, not Anthropic's.** This is worth naming because
"connect with Claude" suggests the opposite direction. Anthropic is not an identity
provider here, and the specification is explicit that a server MUST NOT accept or transit
a token issued for anything else — so the screen that asks the question has to be Kanso's
own, authenticated by Kanso's own login.

## The flow, end to end

1. The client calls `POST /api/mcp` with no token.
2. Kanso answers `401` with
   `WWW-Authenticate: Bearer resource_metadata="https://kanso.example.com/.well-known/oauth-protected-resource", scope="kanso:read kanso:write"`.
3. The client reads that document, finds the authorisation server, and reads its metadata.
4. The client registers itself (`POST /connect/register`) or presents a `client_id` it
   already holds.
5. The client opens `GET /oauth2/authorize` with PKCE `code_challenge`, `resource`, and the
   scopes from the challenge. **No Kanso session → the existing login, then back here.**
   Session → a redirect to `/oauth/consent`, the screen.
6. The member authorises; the screen posts the decision back to `POST /oauth2/authorize`.
   Kanso redirects to the client's callback with a single-use code and `iss`.
7. `POST /oauth2/token` with the `code_verifier` and the same `resource` returns an access
   token and a refresh token.
8. Every subsequent MCP request carries `Authorization: Bearer …`.

## The library owns the dangerous half

**Amended after the design was approved.** This section replaced a hand-written
authorisation server, and the correction is worth recording rather than quietly
absorbing: the first draft specified four tables, `/authorize`, `/token`, PKCE and
refresh rotation, all written here. `spring-boot-starter-oauth2-authorization-server`
resolves on Boot 4.1 through the BOM — `spring-security-oauth2-authorization-server:7.1.0`
— and implements every one of them. Kanso is maintained by one person and has no CI;
security-critical code written once and trusted for years is exactly what belongs to a
maintained library. The paragraph above about MUSTs being "a way to ship something that
looks like it works" argues against writing them by hand, including when this document
was the thing proposing it.

What the library owns: authorisation code issuance and single use, PKCE, the token
endpoint, refresh rotation and reuse detection, RFC 8414 metadata, exact redirect-URI
matching, and the persistence for all of it.

**Amended after the Task 1 spike** (`plans/2026-08-31-mcp-oauth-door-spike.md`, which is
binding where it disagrees with this document). Two items left that list once the library
was actually stood up and driven by hand:

- **Dynamic client registration.** The endpoint is off until enabled, and once enabled
  `OAuth2ClientRegistrationAuthenticationProvider` requires a single-use initial access
  token bearing scope `client.create`. MCP clients present nothing. See "Registration is
  ours" below.
- **`iss` on the authorisation response.** SAS 7.1.0 does not implement RFC 9207: no
  setting on `AuthorizationServerSettings`, no name in `ConfigurationSettingNames`, no
  `iss` on the 302, and no `authorization_response_iss_parameter_supported` in the
  metadata. It is a **MUST** here, so it is hand-written.

Audience binding moved the other way, partly. `resource` *is* carried and readable —
`OAuth2AuthorizationRequest.additionalParameters["resource"]`, persisted and readable back
— so nothing has to record it. Nothing validates it either, so both enforcement points
below are still ours.

What is still written here, because the library does not claim it:

- **`/.well-known/oauth-protected-resource`** (RFC 9728) — a resource-server document, out
  of an authorisation server's scope by definition. Twenty lines of static JSON.
- **The consent screen**, which the library supports as a first-class custom page but does
  not draw.
- **Audience binding to this resource** (RFC 8707) — the value is carried for us; the
  validation, at both ends, is not. See "The unverified MUST", now answered.
- **Dynamic client registration**, open rather than token-gated. See "Registration is ours".
- **`iss` on the authorisation response** (RFC 9207), which the library does not implement.
- **The MCP bearer filter**, which turns a token into a `User` the existing services accept.
- **Connected applications**, the screen and the grant queries behind it.

## Endpoints

Library defaults are kept rather than renamed: a client discovers every one of these from
the metadata documents, so the paths are not a product decision, and renaming them would
be configuration that can drift from what the library actually serves.

| Endpoint | Who | Why it exists |
|---|---|---|
| `GET /.well-known/oauth-protected-resource` | **ours** | RFC 9728, a **MUST** for a protected MCP server. Names the canonical resource URI, the authorisation server, and `scopes_supported`. |
| `GET /.well-known/oauth-authorization-server` | library | RFC 8414 metadata. |
| `GET /oauth2/authorize` | library | Where the browser lands. Validates, then redirects to our consent page. |
| `GET /oauth/consent` (API) | **ours** | The consent screen, reached by that redirect. Server-rendered on the API origin — see below. |
| `POST /oauth2/authorize` | library | The decision our screen posts back. Issues the code. |
| `POST /oauth2/token` | library | `authorization_code` with PKCE, and `refresh_token` with rotation. |
| `POST /connect/register` | **ours** | Dynamic client registration (RFC 7591), open. The library's own is token-gated — see "Registration is ours". |
| `POST /oauth2/revoke` | library | Lets a client hand a token back. |
| `GET /api/oauth/consent/request` | **ours** | Describes the pending consent for the screen: client name, scopes in prose, and why it would be refused. |
| `GET`/`DELETE /api/oauth/grants` | **ours** | The connected-applications list, and revoking one. |

**Amended: the consent screen is served by the API, not by `apps/web`.** The first draft
put it in the web app, reasoning that the only OAuth surface a human looks at should render
Kanso's design system. Implementation planning found that it cannot work there.

Web runs on :3000 and the API on :8080 — `WebConfig` configures CORS between them, so they
are genuinely different origins — and `application.yml` sets the session cookie
`SameSite=Lax`. Lax sends no cookie on a cross-site POST, so the decision would arrive at
`/oauth2/authorize` with no session and the library would have nobody to record the consent
for. `fetch` does not rescue it either: CORS forbids reading `Location` off the library's
302, which is the trap `api/core.ts`'s `startNotionConnect` comment already records for the
Notion flow.

The browser is **already on the API origin** when it reaches `/oauth2/authorize`. Serving
the page there makes it same-origin and first-party, and the decision is a plain form post
the library handles unaided. The cost is that one page does not use the shadcn design
system: it gets a self-contained stylesheet built from the same CSS custom properties
`globals.css` defines, and it is the only page in Kanso that renders from the API.

An anonymous visitor is redirected to the app's login screen with a return URL, and comes
back. That return URL is reflected into a redirect, so it is validated against exactly two
shapes — a relative path, or an absolute URL whose origin equals the API's — compared as
parsed origins rather than as string prefixes.

Two integration facts that are easy to discover too late:

- **Kanso already serves `/oauth2/authorization/{provider}`** for signing *in* with Google.
  The library serves `/oauth2/authorize` for signing *out* to an agent. Different paths,
  adjacent prefixes, opposite directions. They must not be matched by one rule.
- **`SecurityConfig` has a single filter chain with no `securityMatcher`.** The library
  needs its own chain ahead of it. Kanso's existing chain becomes the second, and that
  reordering is a change to the most dangerous file in the application — it gets its own
  task and its own test.

The unauthenticated endpoints — `token`, `register`, `revoke`, both well-known documents —
must be opened in `SecurityConfig`. They cannot go in `PublicRoutes`: `PublicRoutesTest`
asserts every pattern begins with `/api/public/` and that there are exactly three. They
get a sibling list with a guard test of its own rather than a weakened existing one.
`/connect/register` in particular is an unauthenticated row-creating endpoint, the single
most abusable surface this spec adds, and it is rate-limited per IP.

## Registration is ours

`POST /connect/register` answers 404 out of the box: the library ships
`OAuth2ClientRegistrationEndpointConfigurer` but leaves it disabled. Enabling it is not
enough. `OAuth2ClientRegistrationAuthenticationProvider` requires the caller to present an
**initial access token with scope `client.create`**, which it then invalidates — RFC 7591's
protected-registration mode, single use, correct for an enterprise handing out credentials
and wrong for this. No MCP client has such a token, and there is nobody to give it one:
`claude mcp add` speaks RFC 7591 unauthenticated or not at all.

Three ways out were weighed — open registration written here, an initial access token
issued from the Kanso UI, or no registration at all and one pre-registered client per
instance. **Open registration, written here.** The others each break something this
document already committed to: the second reintroduces the pasted credential that "No token
to paste" exists to eliminate, and the third makes the one-command install depend on
client behaviour Kanso does not control.

So Kanso serves its own RFC 7591 endpoint at the library's default path, and it is
deliberately small: accept a `POST`, validate, write one `RegisteredClient` through
`RegisteredClientRepository`, answer with the registration response. It creates a client
and nothing else — no initial access token, no registration access token, no update or
delete, because a client that cannot be edited cannot be edited by an attacker either.

What guards it, given it is unauthenticated and it creates rows:

- **`redirect_uris` against a narrow allowlist**, compared as parsed URLs and not as string
  prefixes: loopback (`http://127.0.0.1:*/…`, `http://localhost:*/…`) for a CLI, and the
  Claude origins for the hosted clients. Anything else is refused. This is the whole
  security of the endpoint — a registration is only ever as dangerous as where it can send
  a code.
- **Rate limited per IP**, and **capped in total**, so a loop cannot fill the table. The cap
  is a refusal, not a prune: deleting a stranger's client to make room for another
  stranger's is worse than saying no.
- **Fixed grants and scopes.** The request does not get to ask for `client_secret_post`, a
  confidential client, or a scope outside `kanso:read kanso:write`. Every registration comes
  out a public client with PKCE required.
- **No secret is ever issued**, so a leaked registration response is worth nothing on its
  own. The client still has to get a member through the consent screen.

A registered client is not an authorisation. It is a name that may *ask* — and until a
member says yes on the consent screen, it can read nothing. That is what makes open
registration acceptable here rather than merely convenient.

## The rules that are not optional

Each of these is a **MUST** in the specification, and each one is a way to build a
plausible-looking OAuth server that is broken. The library enforces four of them; they
are listed anyway, because a rule nobody can name is a rule nobody can test, and plan one
asserts each of them against the running server rather than trusting a changelog — and the
spike has already moved two of them out of the library's column:

- **Audience binding.** A token records the `resource` it was issued for, and every MCP
  request validates that it names *this* server. A token that does not is rejected with
  401 even if it is otherwise valid. Without this, a token minted for another resource by
  the same authorisation server is a free pass — the confused-deputy shape. **This is the
  one the library does not claim** — see below.
- **PKCE S256, always.** Clients here are public — no secret survives on a laptop — so
  the code challenge is what stops an intercepted code from being redeemable. No `plain`.
- **Exact redirect-URI matching.** String equality against the registered set. No prefix
  matching, no wildcards; that is how open redirects get built.
- **`iss` on every authorisation response**, error responses included, and
  `authorization_response_iss_parameter_supported: true` in the metadata, so clients can
  detect a mix-up attack. **Ours**: the library does not implement RFC 9207.
- **Single-use codes**, 60-second lifetime, bound to client, redirect URI, code
  challenge, resource and user. Redeeming one twice revokes the grant rather than
  returning an error, because a replay means the code leaked.
- **Refresh rotation.** A refresh token is consumed on use and replaced. Reuse of a
  consumed one revokes the whole grant.
- **`403` with `error="insufficient_scope"` and a `scope` parameter** when a read-only
  grant reaches a writing tool, so the client can run a step-up flow instead of failing.

## The unverified MUST

RFC 8707 resource indicators are what make audience binding possible: the client sends
`resource` on the authorisation and token requests, and the server records it on the token
and checks it on every use. The library's documentation does not list RFC 8707 among the
specifications it implements, and the jar was not in the Gradle cache to inspect — so this
is an open question, not a known gap.

**Plan one's first task settles it**, and it is cheap: stand the library up, run one
authorisation with a `resource` parameter, and look at what the token row records.

- **If supported** — configure it, and assert it.
- **If not** — the `resource` is carried in the scope set or in a token customiser, and the
  bearer filter enforces the check. That code is ours either way; only its input changes.

Either outcome is a task, not a redesign. This is written down so the answer is a finding
rather than a surprise.

**Answered, and it was neither branch.** The spike drove one authorisation and one token
exchange by hand, with `resource` on both. Verdict: **carried, readable, unenforced.**

The authorisation endpoint's converter copies every parameter it does not itself consume
into `additionalParameters` — its bytecode excludes exactly `response_type`, `client_id`,
`redirect_uri`, `scope`, `state` — so `resource` survives without being a supported
feature. It is readable back, in memory and as persisted JSON:

```kotlin
authorization
    .getAttribute<OAuth2AuthorizationRequest>(OAuth2AuthorizationRequest::class.java.name)
    ?.additionalParameters
    ?.get("resource")
```

One value arrives as a `String`, repeated ones as an `Array<String>`; both need handling.
Only the authorize-time value is stored — the token endpoint parses `resource` and drops
it — so the two cannot be compared after the fact.

Nothing else is true of it. The library does not check the value against anything, does not
reject an unknown resource, and puts no `aud` on the token, which is opaque and carries no
claims at all. So the token customiser the second branch imagined is unnecessary, and two
enforcement points are load-bearing:

1. **At authorise time**, a custom `OAuth2AuthorizationCodeRequestAuthenticationValidator`
   refuses a `resource` that is not this server's MCP endpoint.
2. **At `/api/mcp`**, the bearer filter reads the attribute and compares.

Without the first, a token minted for another resource is accepted here. Without the
second, one minted for Kanso is accepted anywhere. Both, or neither is worth writing.

## Opaque tokens, not JWTs

Kanso is both the authorisation server and the resource server, over one Postgres. A
signed JWT would buy stateless validation Kanso does not need, and cost either a token
that stays valid after revocation until it expires, or an introspection call that is the
database lookup the JWT was supposed to avoid.

The library offers both, per client, and the choice is one line:
`TokenSettings.accessTokenFormat(OAuth2TokenFormat.REFERENCE)`. Reference tokens are
opaque and stored, so the bearer filter resolves one through `OAuth2AuthorizationService`
— an in-process lookup against the same Postgres, no introspection round trip — and
revoking a grant takes effect on the next request with no window.

Access tokens live one hour, refresh tokens sixty days.

## Schema

`V16__oauth_server.sql`. **`V16`, not `V15`, and not by accident:** `V15` is taken by the
Notion-import branch, which is unmerged and 25 commits ahead of `main`. Flyway tolerates a
gap in the numbering and rejects the same version twice, so leaving `V15` alone is what
lets the two branches merge in either order — but only in one order safely. Flyway's
default refuses an out-of-order migration, so **this branch must merge after the import
branch**, or be renumbered before it merges. That constraint belongs to whoever merges,
which is why it is written here and not only in a commit message.

The library ships its own schema as classpath resources —
`oauth2-registered-client-schema.sql`, `oauth2-authorization-schema.sql`,
`oauth2-authorization-consent-schema.sql`. They are copied into the migration **verbatim**,
under a header saying where they came from and that they are not to be hand-edited: they
are the library's contract with its own `Jdbc*` implementations, and an improvement to a
column here is a runtime failure there. This is the one place in Kanso where a migration is
not argued from first principles, and the header says so.

That gives `oauth2_registered_client` (clients, including those that registered
themselves), `oauth2_authorization` (codes, access tokens, refresh tokens — one row per
authorisation, which is why there is no separate token table) and
`oauth2_authorization_consent` (what a member granted which client — the row the
connected-applications screen lists).

What the migration adds beyond the copied files:

```sql
-- Provenance, not accountability. The member owns what their agent did; this
-- says which application typed it. TEXT, not UUID: the library's client id is a
-- varchar of its own choosing, and this column follows it rather than the house
-- convention — a foreign key that has to convert is a foreign key that will not.
ALTER TABLE activity
  ADD COLUMN via_client_id TEXT REFERENCES oauth2_registered_client(id) ON DELETE SET NULL;
```

No sweeper is written. The library's `JdbcOAuth2AuthorizationService` keeps one row per
authorisation and rewrites it as tokens rotate, so the table does not grow per refresh the
way a hand-rolled token table would. Whether expired rows are removed at all is a question
for after the first deployment, with a real row count to look at — inventing a retention
policy now would be inventing a number.

## Two scopes

`kanso:read` and `kanso:write`. Namespaced because they appear on a consent screen and in
other people's client configurations, so they need to read as Kanso's own.

Not per-team, and this is a decision rather than a simplification: team membership already
bounds what a member can reach, and a second scoping system that can disagree with the
first is the exact failure this document opens with. A read-only grant exists because
"let an agent look at my backlog" is a genuinely smaller ask than "let it fill it".

## Becoming a User

`McpBearerFilter` runs on `/api/mcp/**` only. It resolves the bearer through the library's
`OAuth2AuthorizationService` and refuses — 401, with the `WWW-Authenticate` challenge that
starts the flow again — if the token is unknown, expired, revoked, or issued for another
resource. Otherwise it reads the member id off the authorisation and puts the same
`Principals` type into the security context that a session would.

Its own class rather than Spring's `oauth2ResourceServer`: the built-in support ends at an
`Authentication` holding scopes, and every Kanso service takes a `dev.kanso.domain.User`.
The translation is where this feature actually integrates, and it is four lines that belong
somewhere a reader can find them.

That last clause is the whole design. Downstream, `CurrentUser` resolves as always and
every service sees a `User`, so `TicketAccess`, `TeamService` and the admin checks are
untouched, and the MCP surface **cannot** be more permissive than the UI.

Scope enforcement lives in one place: a tool declared as writing refuses a `kanso:read`
grant before doing anything, as a 403 carrying `scope="kanso:write"` so the client can
step up.

## Dev mode is refused

`KANSO_AUTH_MODE=dev` takes identity from an unverified `X-Kanso-User` header. An
authorisation server running behind that is a credential factory: anyone who can reach it
mints a real token for any member.

**So the OAuth endpoints refuse to start in dev mode**, with a startup log saying why, and
`/api/mcp` answers 503 with the same sentence. Tests that need a token create rows
directly. This is not a precaution against a hypothetical — the README already warns never
to expose an instance running that way, and this is the first feature where doing so would
hand out durable credentials rather than a session.

## What the member sees

**The consent screen** — served by the API at `/oauth/consent`, for the origin reasons
above. Client name, the
member's own identity ("as elie@…"), the two scopes in a sentence rather than as
identifiers ("Read tickets, projects and documents in your teams" / "Create and change
them"), Authorise and Deny. Existing design system, no new patterns. Denying redirects with
`access_denied` rather than dead-ending.

**Connected applications** — one settings section: client name, scopes, when it was
granted and Revoke. Revoking deletes the consent row and every authorisation under it,
so the client's tokens stop working on their next request — no window, because the bearer
filter resolves each token against the same table.

There is nothing in the setup wizard. Connecting an agent is something a member does when
they want one, not a step between an empty instance and a first ticket.

---

# Part two — the gesture

The obvious tool set is the wrong one. Kanso has around twenty controllers; mirroring them
gives forty tools, and forty tools is a worse product than none: the agent spends its
context listing them, picks the plausible-but-wrong one, and needs six calls to file one
ticket — six calls that can each fail independently, so half a plan can land.

So the design starts from the gesture. What a design conversation should become is not
fifteen tickets. It is **a page whose `ticket_link` blocks are the tickets it created** —
decision and work born linked, in one transaction, so the document stays true without
being re-read. That is the README's promise executed by a machine, and it is only
expressible as a single composite write.

Everything else follows from making that one write good.

## Six tools

Each description says what the tool is for *and when not to use it*, because six
overlapping tools are as bad as forty.

**1. `kanso_search`** — one query across tickets, projects, documents and cycles. Free
text, team, project, status, assignee, label, cycle, limit — what the UI already scopes
by. Returns compact lines, not DTOs:

```
KAN-142  In progress  P2  Fix the overlap warning        @elie  due 2026-09-04
KAN-147  Todo         P3  Timeline drags past its cycle  —      —
```

About twenty tokens a ticket, so a hundred results is a cheap read. Delegates to
`TicketService.search` and its siblings and adds nothing.

**2. `kanso_context`** — everything about one ticket, project or page, addressed by key
(`KAN-142`) or id: description, comments in order, dependencies both ways, linked Notion
docs *and* linked `doc_pages`, cycle, assignees, dates, labels, recent activity. The
expensive read, deliberately separate from the cheap one, and the tool a local-run plugin
will live on.

**3. `kanso_plan`** — the composite write, and the only tool that can create.

Input is a whole plan: an optional page (title, folder, blocks), a list of tickets (title,
description, status, priority, estimate, assignee, dates, labels), dependencies expressed
between **local references** rather than ids the caller cannot yet know, and a target
(team, optionally project and cycle).

`dry_run` defaults to **true**, and that default is the safety story. A dry run resolves
every name to an id, checks every access rule, validates the dependency graph for cycles,
and returns what *would* happen — "1 page in Design, 9 tickets in KAN, 2 dependencies, 3
assignees resolved, 1 label created" — having written nothing. The agent shows that to the
member, who says yes, and the same call runs with `dry_run: false`.

The commit is one transaction. Either the page, the tickets, the `ticket_link` blocks, the
`doc_block_tickets` rows and the sync jobs all exist, or none do. There is no state where
nine tickets sit in a team with no document explaining them.

**4. `kanso_update`** — status, priority, assignee, dates, labels, project, cycle on
things that already exist, in batch, over `BulkEditService`. No `dry_run`: these are the
reversible per-field edits the UI does with a keystroke, and a preview on each one would
train the agent to click through the preview that matters.

**5. `kanso_organise`** — create a team, project or cycle. Rare, coarse, gated by the
admin rules `TeamService` and `ProjectService` already enforce. Separate from `kanso_plan`
because structure is a decision a person makes once: a plan naming an unknown team
**refuses and says which teams exist**, rather than inventing one.

**6. `kanso_comment`** — a comment on a ticket or project. How a local run reports back,
and the smallest useful write in the set.

## Why the plan tool needs a service

`kanso_plan` is the one place an MCP tool would otherwise hold business logic. "The page
and its tickets exist together or not at all" is a rule about Kanso, not about transport,
so it lives in `dev.kanso.service.PlanService` beside the others and the tool stays the
same thin shell as the other five.

`PlanService` exposes `preview(actor, plan)` and `apply(actor, plan)` over one body: shared
resolution and validation, differing only in whether they may write — the way
`NotionImportService.preview` cannot write because it is never handed anything that can.
`preview` is `@Transactional(readOnly = true)`, which makes that structural rather than
disciplined.

Two bounds, both refusals with a sentence rather than a truncation:

- **50 tickets per plan.** An agent in a loop is a real failure mode, and 400 rows
  arriving in Notion is a mess somebody else cleans up. Fifty exceeds any real
  conversation and stays small enough to read in a diff.
- **Every ticket's team is checked, not just the plan's.** A plan may name a project in
  another team.

## Where the code lives

```
mcp/
  McpToolsConfig.kt      the six declarations
  McpBearerFilter.kt     Bearer → the same Principal a session produces
  McpErrors.kt           Errors.kt → sentences an agent can act on
  tools/                 SearchTool, ContextTool, PlanTool, UpdateTool,
                         OrganiseTool, CommentTool — one file each
oauth/
  AuthorizationServerConfig.kt   the library's filter chain, settings, token format
  ProtectedResourceController.kt /.well-known/oauth-protected-resource  (RFC 9728)
  ConsentController.kt           serve the consent page, or redirect to login
  ConsentPage.kt                 the page as a pure render, escaping included
  GrantsController.kt            list and revoke connected applications
  GrantService.kt                the queries behind those two
  ScopeCopy.kt                   a scope's name in prose, shared with the screen
service/PlanService.kt
```

**No MCP tool contains business logic.** Each validates its input, calls an existing
service, and formats the output. A tool file that grows is a tool that stole work from a
service — with `PlanService` the single, argued exception.

## Serving the protocol

`spring-ai-starter-mcp-server-webmvc` (Spring AI 2.0.1) with
`spring.ai.mcp.server.protocol=STREAMABLE`, mounted at `/api/mcp`. The starter owns
initialisation, protocol-version negotiation, session handling and JSON-RPC framing;
`McpToolsConfig` declares the tools.

**This is gated on a spike, and the spike is the first task of part two.** The starter's
documentation does not state its Spring Boot floor and Kanso runs Boot 4.1. If it does not
run there, the fallback is a hand-written `McpController` — JSON-RPC 2.0 over
`POST /api/mcp` with `initialize`, `tools/list` and `tools/call`, roughly 250 lines, no new
dependency. That fallback is genuinely acceptable, since none of the six tools needs
anything server-initiated; the cost is following spec revisions by hand, and the protocol
is moving (SSE is already deprecated in favour of Streamable HTTP). Prefer the starter if
it runs. Decide in an hour, not a week.

## Errors an agent can act on

A tool failure is a result, not a transport error: the tool returns error *content* the
agent can read and retry against, rather than a 500 the client reports as a broken server.
`McpErrors` maps the three existing exceptions onto sentences that name the fix —
`No team with key "DES". Existing teams: KAN, OPS.` beats `BadRequestException` — the same
discipline the Notion import's refusal sentences already follow.

Authorisation failures are the exception and stay HTTP: a 401 or a 403 with a `scope`
challenge is how the client knows to re-authorise rather than to rephrase.

Rate limiting reuses the token bucket the outbound sync worker uses, keyed per grant on
`tools/call`. A looping agent gets 429 and a `Retry-After`, not a filled database.

## Testing

The suite's shape matters more than its size. Five assertions carry this work, and the
first three are the ones to write first:

1. **A token cannot outrank its owner.** A grant belonging to a member of one team calls
   `kanso_search` and `kanso_plan` against another team's project and gets the same refusal
   the REST API gives that member. This is the test that proves the "agents are not a new
   kind of user" premise holds in code.
2. **A dry run writes nothing.** Row counts in `tickets`, `doc_pages`, `doc_blocks` and
   `sync_jobs` before and after a `dry_run: true` plan of nine tickets, every count
   unchanged. If one test survives from this spec, it is this one.
3. **A failing plan lands nothing.** A plan whose ninth ticket names an unknown assignee
   leaves no page, no tickets, no sync jobs.
4. **A token issued for another resource is refused**, and a replayed authorisation code
   revokes its grant. The two audience-and-replay rules, asserted rather than assumed.
5. **A revoked grant stops working on the next request** — no window.

Beyond those: `McpBearerFilter` over unknown, expired, revoked and malformed tokens; the
consent page as a pure render — copy, the form's target, and the escaping of a client
name the client chose itself, which is the assertion that matters most on that page; the
return-URL validation as its own module, because `vitest` runs under
`environment: "node"` here and cannot reach JSX; and `PlanService` tests for the
50-ticket bound, dependency cycles and local-reference resolution.

**The library's rules are tested at the boundary, not reimplemented.** PKCE (S256 accepted,
wrong verifier refused, `plain` refused), exact redirect-URI matching, refresh rotation and
code replay are asserted by driving the real endpoints once each. Six short tests against a
running server, not a second implementation of the specification to keep in step — but not
nothing either: a version bump that changed one of them silently is exactly what these
catch.

One convention note, because it shapes the tests more than any decision above: this suite
has **no MockMvc habit** — `PostgresTest` is `WebEnvironment.NONE`, and controller tests
autowire the controller and call its methods, authenticating with a hand-built
`KansoLocalUser` pushed into `SecurityContextHolder`. `MeVersionTest` is the single MockMvc
test and stands up its own context, which `SecurityBootstrapTest` warns against
multiplying. The filter and the six boundary tests need a real request, so they reuse
`MeVersionTest`'s pattern — one added context configuration, shared, not one per class.

**There is no CI** (`docs/follow-ups.md`), so the suite runs when somebody remembers. An
OAuth authorisation server whose tests run on memory is the wrong trade at any speed, and
this work is a reasonable place to add a GitHub Action running Gradle, Vitest and
Playwright. If that is out of scope, the risk is accepted explicitly rather than by
omission.

---

## Two plans, in order

This is one design and two implementation plans, because the halves have different failure
modes and the first has a milestone worth stopping at.

**Plan one — the door.** The RFC 8707 spike, the library's filter chain composed with the
existing one, the migration, the protected-resource document, the consent screen and its
login round trip, `McpBearerFilter`, the connected-applications screen, the dev-mode
refusal. Its deliverable is small and completely convincing: `claude mcp add` opens a
browser, the member authorises, and `tools/list` returns an empty list over an
authenticated session. Everything hard about auth is proven before a single tool exists.

**Plan two — the tools.** The starter spike, the six tools, `PlanService`, the
document-to-ticket linkage, the provenance rendering in the activity feed.

## Risks, stated plainly

**An authorisation server is security-critical code written once and trusted for years.**
Handing it to the library removes most of that exposure and moves the rest: what remains
ours is the filter chain composition, the audience check, and the bearer-to-`User`
translation — three small things, each of which can be wrong in a way that opens
everything. A `/security-review` pass on plan one is not optional, and its focus is those
three rather than the protocol.

**The blast radius is Notion.** An agent's write publishes to the four mirrored databases
that everyone who does not open Kanso reads. `dry_run` by default, the 50-ticket bound and
the trash are the three things between a bad plan and a mess in somebody else's tool —
defence in depth against an *implausible* plan. None of them stops a plausible bad one.

**`/connect/register` is an unauthenticated endpoint that creates rows.** Rate-limited, and
worth watching once deployed.

**The library is a dependency with opinions.** Its schema is copied rather than designed,
its endpoint paths are kept rather than chosen, and a major version will eventually ask for
work that a hand-written server would not have. That is the price, and it is the right one
here — but it is a price, not a free lunch.

**Tool descriptions are load-bearing prose.** Six well-described tools beat forty, but only
if the descriptions are written as carefully as the code. They will need revision after the
first real conversation; that is expected, not a defect.

**The starter is unverified.** See the spike — the only unknown that could change the shape
of this work rather than its size.
