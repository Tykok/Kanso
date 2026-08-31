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
4. The client registers itself (`POST /oauth/register`) or presents a `client_id` it
   already holds.
5. The client opens the authorisation endpoint — a web route, not an API one — with PKCE
   `code_challenge`, `resource`, and the scopes from the challenge. **No Kanso session →
   the existing login.** Session → the consent screen.
6. The member authorises. Kanso redirects to the client's callback with a single-use code
   and `iss`.
7. `POST /oauth/token` with the `code_verifier` and the same `resource` returns an access
   token and a refresh token.
8. Every subsequent MCP request carries `Authorization: Bearer …`.

## Endpoints

| Endpoint | Standard | Why it exists |
|---|---|---|
| `GET /.well-known/oauth-protected-resource` | RFC 9728 | **MUST** for a protected MCP server. Names the canonical resource URI, the authorisation server, and `scopes_supported`. |
| `GET /.well-known/oauth-authorization-server` | RFC 8414 | **MUST** publish this or OIDC Discovery. Endpoints, `code_challenge_methods_supported: ["S256"]`, `grant_types_supported: ["authorization_code", "refresh_token"]`, `authorization_response_iss_parameter_supported: true`. |
| `GET /oauth/authorize` (web) | OAuth 2.1 | The one endpoint a browser lands on. Requires a session, renders consent. |
| `GET /api/oauth/authorize/request` | — | Validates the query and describes it for the screen: client name, scopes, and why it would be refused. |
| `POST /api/oauth/authorize` | OAuth 2.1 | The decision. Issues the code, or redirects with `access_denied`. |
| `POST /api/oauth/token` | OAuth 2.1 + RFC 8707 | `authorization_code` with PKCE, and `refresh_token` with rotation. |
| `POST /api/oauth/register` | RFC 7591 | Dynamic client registration. Deprecated in the current draft in favour of Client ID Metadata Documents, and implemented anyway because it is what today's clients actually do. |
| `POST /api/oauth/revoke` | RFC 7009 | Lets a client hand a token back. Cheap, and the polite half of the revocation story. |

**The authorisation endpoint is the one piece that lives in `apps/web`.** It is the only
OAuth endpoint a human looks at, so it has to render Kanso's design system and reuse
Kanso's login redirect — which the API, serving JSON to a browserless client, does neither
of. So `authorization_endpoint` in the metadata is
`https://kanso.example.com/oauth/authorize`, a web route that asks the API to validate the
request, draws the consent screen, and posts the decision back. Every other endpoint is
JSON under `/api/oauth/`, where the rest of the API lives. The metadata documents are the
single source of these URLs — no client ever constructs one.

The three unauthenticated ones — `token`, `register`, `revoke` — plus both well-known
documents join `PublicRoutes` and are rate-limited per IP. `/api/oauth/register` in
particular is an unauthenticated row-creating endpoint, the single most abusable surface
this spec adds.

## The rules that are not optional

Each of these is a **MUST** in the specification, and each one is a way to build a
plausible-looking OAuth server that is broken:

- **Audience binding.** A token records the `resource` it was issued for, and every MCP
  request validates that it names *this* server. A token that does not is rejected with
  401 even if it is otherwise valid. Without this, a token minted for another resource by
  the same authorisation server is a free pass — the confused-deputy shape.
- **PKCE S256, always.** Clients here are public — no secret survives on a laptop — so
  the code challenge is what stops an intercepted code from being redeemable. No `plain`.
- **Exact redirect-URI matching.** String equality against the registered set. No prefix
  matching, no wildcards; that is how open redirects get built.
- **`iss` on every authorisation response**, error responses included, and
  `authorization_response_iss_parameter_supported: true` in the metadata, so clients can
  detect a mix-up attack.
- **Single-use codes**, 60-second lifetime, bound to client, redirect URI, code
  challenge, resource and user. Redeeming one twice revokes the grant rather than
  returning an error, because a replay means the code leaked.
- **Refresh rotation.** A refresh token is consumed on use and replaced. Reuse of a
  consumed one revokes the whole grant.
- **`403` with `error="insufficient_scope"` and a `scope` parameter** when a read-only
  grant reaches a writing tool, so the client can run a step-up flow instead of failing.

## Opaque tokens, not JWTs

Kanso is both the authorisation server and the resource server, over one Postgres. A
signed JWT would buy stateless validation Kanso does not need, and cost either a token
that stays valid after revocation until it expires, or an introspection call that is the
database lookup the JWT was supposed to avoid.

So: 32 random bytes, base64url, prefixed `kat_` and `krt_`. **Stored hashed** —
SHA-256, not bcrypt: the token is 256 bits of `SecureRandom`, there is no dictionary to
slow an attacker down through, and a work factor would be paid on every single tool call.
Revocation is a column, and it takes effect on the next request.

Access tokens live one hour, refresh tokens sixty days.

## Schema

`V16__oauth_server.sql` — four tables, because there are four distinct concepts and
folding any two of them together makes revocation ambiguous.

```sql
-- A client that registered itself. Public clients only: no secret is stored,
-- because no secret survives on the machine these clients run on.
CREATE TABLE oauth_clients (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  client_id         TEXT NOT NULL UNIQUE,
  client_name       TEXT NOT NULL,
  redirect_uris     TEXT[] NOT NULL,
  scopes            TEXT NOT NULL,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT oauth_clients_redirects_chk CHECK (cardinality(redirect_uris) > 0)
);

-- One row per (member, client) consent. This is the unit the member sees and
-- revokes, and the reason tokens do not carry the user directly: revoking a
-- grant must kill every token under it without a scan.
CREATE TABLE oauth_grants (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  client_id  UUID NOT NULL REFERENCES oauth_clients(id) ON DELETE CASCADE,
  scopes     TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  revoked_at TIMESTAMPTZ
);

-- One *live* grant per pair, not one ever. Revoking and authorising again is
-- normal, and the revoked row is the history saying the member did both.
CREATE UNIQUE INDEX oauth_grants_live_idx
  ON oauth_grants (user_id, client_id) WHERE revoked_at IS NULL;

CREATE TABLE oauth_codes (
  code_hash      TEXT PRIMARY KEY,
  grant_id       UUID NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
  redirect_uri   TEXT NOT NULL,
  code_challenge TEXT NOT NULL,
  resource       TEXT NOT NULL,
  scopes         TEXT NOT NULL,
  expires_at     TIMESTAMPTZ NOT NULL,
  consumed_at    TIMESTAMPTZ
);

CREATE TABLE oauth_tokens (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  grant_id     UUID NOT NULL REFERENCES oauth_grants(id) ON DELETE CASCADE,
  kind         TEXT NOT NULL,
  token_hash   TEXT NOT NULL UNIQUE,
  resource     TEXT NOT NULL,
  scopes       TEXT NOT NULL,
  expires_at   TIMESTAMPTZ NOT NULL,
  last_used_at TIMESTAMPTZ,
  revoked_at   TIMESTAMPTZ,
  rotated_to   UUID REFERENCES oauth_tokens(id) ON DELETE SET NULL,
  CONSTRAINT oauth_tokens_kind_chk CHECK (kind IN ('access', 'refresh'))
);

CREATE INDEX oauth_tokens_grant_idx ON oauth_tokens (grant_id);
CREATE INDEX oauth_grants_user_idx  ON oauth_grants (user_id, created_at DESC);

-- Provenance, not accountability. The member owns what their agent did; this
-- says which application typed it.
ALTER TABLE activity
  ADD COLUMN via_client_id UUID REFERENCES oauth_clients(id) ON DELETE SET NULL;
```

A `TokenSweeper` in the shape of the existing `TrashSweeper` deletes expired codes and
tokens. Without it these tables only grow.

## Two scopes

`kanso:read` and `kanso:write`. Namespaced because they appear on a consent screen and in
other people's client configurations, so they need to read as Kanso's own.

Not per-team, and this is a decision rather than a simplification: team membership already
bounds what a member can reach, and a second scoping system that can disagree with the
first is the exact failure this document opens with. A read-only grant exists because
"let an agent look at my backlog" is a genuinely smaller ask than "let it fill it".

## Becoming a User

`McpBearerFilter` runs on `/api/mcp/**` only. It hashes the bearer, loads the token, and
refuses — 401, with the `WWW-Authenticate` challenge that starts the flow again — if the
token is unknown, expired, revoked, under a revoked grant, or issued for another resource.
Otherwise it puts the same `Principals` type into the security context that a session
would.

That last clause is the whole design. Downstream, `CurrentUser` resolves as always and
every service sees a `User`, so `TicketAccess`, `TeamService` and the admin checks are
untouched, and the MCP surface **cannot** be more permissive than the UI. `last_used_at`
is written outside the request transaction, so a rolled-back plan still records that the
token was used.

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

**The consent screen** — the `apps/web` route at `/oauth/authorize`. Client name, the
member's own identity ("as elie@…"), the two scopes in a sentence rather than as
identifiers ("Read tickets, projects and documents in your teams" / "Create and change
them"), Authorise and Deny. Existing design system, no new patterns. Denying redirects with
`access_denied` rather than dead-ending.

**Connected applications** — one settings section: client name, scopes, when it was
granted, when it was last used, and Revoke. Revoking sets `oauth_grants.revoked_at`, and
every token under it stops working on its next request.

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
  OAuthMetadataController.kt   the two well-known documents
  AuthorizeController.kt       validate a request, and take the decision
  TokenController.kt           /oauth/token, /oauth/revoke
  RegistrationController.kt    /oauth/register
  OAuthService.kt              codes, tokens, rotation, revocation
  OAuthClientService.kt        registration and redirect-URI validation
  Pkce.kt                      S256 verification
  TokenSweeper.kt              expiry
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

Beyond those: PKCE verification (S256 pass, wrong verifier, `plain` refused), exact
redirect-URI matching, refresh rotation and reuse detection, `McpBearerFilter` over
unknown/expired/revoked/malformed tokens, a `MockMvc` test per tool, and `PlanService`
tests for the 50-ticket bound, dependency cycles and local-reference resolution.

**There is no CI** (`docs/follow-ups.md`), so the suite runs when somebody remembers. An
OAuth authorisation server whose tests run on memory is the wrong trade at any speed, and
this work is a reasonable place to add a GitHub Action running Gradle, Vitest and
Playwright. If that is out of scope, the risk is accepted explicitly rather than by
omission.

---

## Two plans, in order

This is one design and two implementation plans, because the halves have different failure
modes and the first has a milestone worth stopping at.

**Plan one — the door.** The two well-known documents, registration, authorize and consent,
token and refresh, revocation, `McpBearerFilter`, the connected-applications screen, the
dev-mode refusal, the sweeper. Its deliverable is small and completely convincing:
`claude mcp add` opens a browser, the member authorises, and `tools/list` returns an empty
list over an authenticated session. Everything hard about auth is proven before a single
tool exists.

**Plan two — the tools.** The starter spike, the six tools, `PlanService`, the
document-to-ticket linkage, the provenance rendering in the activity feed.

## Risks, stated plainly

**An authorisation server is security-critical code written once and trusted for years.**
Every MUST in "The rules that are not optional" is a way to ship something that looks like
it works. This is the part to review adversarially, and a `/security-review` pass on plan
one is not optional.

**The blast radius is Notion.** An agent's write publishes to the four mirrored databases
that everyone who does not open Kanso reads. `dry_run` by default, the 50-ticket bound and
the trash are the three things between a bad plan and a mess in somebody else's tool —
defence in depth against an *implausible* plan. None of them stops a plausible bad one.

**`/oauth/register` is an unauthenticated endpoint that creates rows.** Rate-limited, and
worth watching once deployed.

**Tool descriptions are load-bearing prose.** Six well-described tools beat forty, but only
if the descriptions are written as carefully as the code. They will need revision after the
first real conversation; that is expected, not a defect.

**The starter is unverified.** See the spike — the only unknown that could change the shape
of this work rather than its size.
