# The repository, on the ticket

Kanso knows what the work is. GitHub knows whether it is done. Today nobody tells
either one about the other, so the same fact gets typed twice — a branch named after a
ticket, a ticket dragged to Done after a merge — and the second typing is the one that
gets forgotten.

This spec links the two, in both directions, and gives the agent one tool to drive the
link rather than five.

The premise is the same one the MCP spec opens with, and it is worth repeating because
it is the thing most easily lost: **an integration is not a new kind of user.** A pull
request does not gain rights over a ticket. It becomes a *reason* a member's ticket
moved, recorded as such, and every write still lands through the services that already
own the rules.

## What this is not

- **A code host.** Kanso does not clone, does not diff, does not review. It reads pull
  request metadata and writes pull request metadata. `contents` permission is
  read-only, and that is a design decision rather than a phase — see "Kanso does not
  create branches".
- **A CI dashboard.** Check runs and statuses are deliberately out of scope. They are
  high-volume, they change many times per push, and the question they answer ("is the
  build green") is one GitHub already answers on the page the ticket links to.
- **Issue sync.** GitHub Issues are a second backlog. Kanso is the backlog. Mirroring
  them would create two places to file work and a conflict rule to arbitrate them,
  which is the failure the Notion mirror exists to avoid rather than to duplicate.
- **A second permission system.** No GitHub-derived rights. A member who cannot see a
  ticket cannot see its pull requests either, because the query starts from
  `TicketAccess` and never from the repository.

---

## Read this first

Four facts in the repository decide most of what follows.

- **Kanso already speaks OAuth as a client.** `V14__notion_oauth.sql` and
  `setup/NotionOAuth.kt` exchange a consent screen for a token, store a client id and an
  encrypted secret on `instance_settings`, and record which workspace the grant names.
  GitHub is the same shape with a better first step — see "No key to paste, and this
  time it is true".
- **Secrets have a home.** `config/SecretBox.kt` encrypts, `instance_settings` holds one
  row, and `InstanceSettingsService.load()` decides per field whether the environment or
  the database wins. `c3d1a95` established that pattern for the Notion app; GitHub
  follows it rather than inventing a second one.
- **`activity.actor_id` is nullable and `activity.kind` is closed.** `V8`'s
  `activity_kind_chk` is a CHECK listing eleven kinds, mirrored by the `ActivityKind`
  enum so a typo is a compile error *and* a constraint violation. A new kind is a
  migration, not a string.
- **`PublicRoutes` cannot absorb a webhook.** `PublicRoutesTest` asserts every pattern
  begins with `/api/public/` and that there are exactly three. The webhook joins the
  sibling list the MCP spec opens in `SecurityConfig`, with a guard test of its own —
  the same conclusion that spec reached for `/connect/register`, for the same reason.

And one fact about ordering: **this work comes after the MCP's two plans.** `V16`
belongs to the MCP door, so GitHub is `V17`. Part four of this document names tools
that do not exist yet; it is written now so that the MCP is not built in ignorance of
what lands on it, and it is implemented last.

---

# Part one — the door

## No key to paste, and this time it is true

The Notion connection removed three manual steps out of four and left one: the owner
still creates an integration on Notion and pastes its client id and secret into Kanso.
That residue is unavoidable there — Notion will not issue a client to a hostname it has
never heard of.

GitHub will. **The App Manifest flow** lets Kanso describe the application it wants,
hand that description to GitHub, and receive every credential back over the wire:

1. The owner opens `Settings › Connections › GitHub` and clicks **Connect**.
2. Kanso builds a manifest and posts it as a form to
   `https://github.com/settings/apps/new?state=<state>` — or
   `https://github.com/organizations/{org}/settings/apps/new?state=<state>` if the owner
   named an organisation.
3. GitHub shows its own creation screen. The owner confirms.
4. GitHub redirects to `{api}/api/github/manifest/callback?code=…&state=…`.
5. Kanso posts `https://api.github.com/app-manifests/{code}/conversions` and receives the
   app id, the slug, the client id, the client secret, the webhook secret **and the
   private key (PEM)**.
6. Kanso redirects to `https://github.com/apps/{slug}/installations/new`, where the owner
   picks the organisation and the repositories.
7. GitHub redirects back with an `installation_id`. One row.

Nothing is copied. Not a client id, not a secret, not a multi-line PEM — which is the
one people paste wrong.

The manifest:

```json
{
  "name": "Kanso",
  "url": "https://kanso.example.com",
  "hook_attributes": { "url": "https://kanso.example.com/api/github/webhook" },
  "redirect_url": "https://kanso.example.com/api/github/manifest/callback",
  "public": false,
  "default_permissions": {
    "pull_requests": "write",
    "contents": "read",
    "metadata": "read"
  },
  "default_events": [
    "pull_request", "pull_request_review",
    "installation", "installation_repositories"
  ]
}
```

**`state` is checked, not decorated.** It is generated the way
`NotionOAuth.newState()` generates one — 32 bytes of `SecureRandom`, URL-safe base64 —
stored against the session, and compared on return. A callback with an absent or
mismatched `state` is a 400 that writes nothing. The conversion code is single-use and
expires in an hour, so a leaked callback URL is worth less than a leaked token, but it
is still worth an App.

**The flow is unverified against GitHub from this repository**, and that is task one of
plan three: run one real conversion and look at what comes back. If the response does not
carry the PEM and the webhook secret, the fallback is a hand-registered App — the owner
creates it on GitHub and pastes four values — and **nothing else in this document
changes**, because everything downstream reads those values from
`InstanceSettingsService` and does not care how they arrived. This is the same discipline
`spike(oauth)` applied to RFC 8707: an answer, not a surprise.

## Kanso does not create branches

Opening a pull request requires a head branch with at least one commit; GitHub refuses
`No commits between main and feat/kan-142-x` otherwise. So "create the PR from Kanso"
cannot mean "from nothing", and three shapes were weighed:

- **Kanso names the branch, the member pushes it, Kanso opens the PR.** Chosen.
- **Kanso creates the branch too**, then the member fetches and pushes.
- **Kanso creates the branch, an empty commit, and a draft PR immediately.**

The first is chosen because of what it does *not* ask for. The other two need
`contents: write`, which means the installation screen tells an organisation that Kanso
may write to its repositories — for the convenience of not typing `git switch -c`. That
is a bad trade, and it is permanent: a permission granted at installation is granted for
every repository in the selection, forever, whether or not the feature that needed it is
still used.

The ticket therefore shows the branch name to copy, and `[Open the PR]` becomes active
once that branch exists on the team's repository. This is the Linear model, and it is the
model because it is right, not because Linear picked it.

## The member's own consent

Installation grants Kanso access to repositories. It does not grant Kanso the right to
act *as* a person. That is a second, per-member consent — the user-to-server flow —
reached from a member's own settings and skippable.

**When a member has linked their account, Kanso writes as them. When they have not, the
App writes and the pull request body names who asked.** That is a deliberate choice of
the third option over the two clean ones, and its cost is stated rather than hidden:
there are two write paths to test, and the author of a pull request depends on a
configuration state, which is the kind of variability that makes a bug hard to reproduce.

Two things pay that down, and they are requirements rather than nice-to-haves:

- **The activity row records which identity wrote.** `payload.via` is `"member"` or
  `"app"`. Asking "why is this PR authored by a bot" has an answer in the feed.
- **The pull request body always names the requester**, whichever identity opened it. The
  attribution that matters to a human is never ambiguous, even when the Git attribution is.

## Schema — `V17__github.sql`

```sql
ALTER TABLE instance_settings
  ADD COLUMN github_app_id             TEXT,
  ADD COLUMN github_app_slug           TEXT,
  ADD COLUMN github_client_id          TEXT,
  ADD COLUMN github_client_secret_enc  BYTEA,
  ADD COLUMN github_webhook_secret_enc BYTEA,
  ADD COLUMN github_private_key_enc    BYTEA;
```

One App per instance. Not per team: an App is an identity on GitHub, and an instance that
presents two of them to the same organisation is an instance whose audit log is harder to
read than its configuration was to write.

```sql
CREATE TABLE github_installations (
  id            BIGINT PRIMARY KEY,      -- GitHub's own installation id
  account_login TEXT NOT NULL,
  account_type  TEXT NOT NULL,           -- 'Organization' | 'User'
  suspended_at  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

**There is no repositories table**, and that is the load-bearing omission of this
schema. The repository selection is edited on GitHub, by people who are not looking at
Kanso, and a mirror of it goes stale silently — the worst failure mode there is, because
the symptom is a button that does nothing rather than an error. Every inbound payload
carries `installation.id` and `repository.full_name`, which is everything routing needs;
the settings screen's repository list is asked of GitHub when it renders.

```sql
CREATE TABLE github_accounts (
  user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  github_user_id    BIGINT NOT NULL UNIQUE,
  github_login      TEXT NOT NULL,
  access_token_enc  BYTEA NOT NULL,
  refresh_token_enc BYTEA,
  expires_at        TIMESTAMPTZ,
  linked_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

`github_user_id` is `UNIQUE` and it is the id, not the login: a login is renameable and a
webhook payload that arrives after a rename must still resolve to the right member.

```sql
ALTER TABLE teams ADD COLUMN github_repo TEXT;   -- 'tykok/kanso'
```

Why a team owns a repository is argued in part three.

**`V17` is one migration, not four.** Parts two and three add `github_pull_requests`,
`ticket_pull_requests`, `github_jobs` and `github_deliveries` to this same file; they are
written beside the argument that justifies them rather than gathered here, because a
table whose reason is three sections away is a table the next reader deletes.

## The environment may pin the App

`KANSO_GITHUB_APP_ID`, `KANSO_GITHUB_APP_SLUG`, `KANSO_GITHUB_CLIENT_ID`,
`KANSO_GITHUB_CLIENT_SECRET`, `KANSO_GITHUB_WEBHOOK_SECRET`,
`KANSO_GITHUB_PRIVATE_KEY`. When they are set, `InstanceSettingsService.load()` prefers
them and the Connect button says the App is managed by the environment — exactly the
shape `notionAppManagedByEnvironment` already has, exactly the reason: a `docker compose`
should be able to ship an instance already wired.

The rule `c3d1a95` established holds here too, and it is the subtle one: an id from the
environment married to a secret from the database is a configuration nobody intended, so
the six values are read as one group or not at all.

## Nothing in the setup wizard

Notion is a step in first-run setup because Notion is the mirror — it is how everyone who
does not open Kanso reads anything at all. GitHub is not. It matters to a team that
already has tickets and repositories, which is not the state of an instance thirty seconds
old.

So GitHub lives in settings, reachable with `,`, like connecting an agent. The
onboarding checklist may mention it; the wizard does not stop for it.

---

# Part two — the link

## Two tables, because both sides are plural

A pull request can close two tickets. A ticket can have three pull requests — a
revert, a follow-up, a split. The repository already models this shape for documents:
`doc_blocks` carries the block and `doc_block_tickets` carries the reference, and
`V9__documents.sql` argues why at length. The README makes many-to-many-on-both-sides a
promise. This follows it.

```sql
CREATE TABLE github_pull_requests (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  installation_id BIGINT NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,
  repo_full_name  TEXT NOT NULL,
  number          INT  NOT NULL,
  node_id         TEXT NOT NULL,
  title           TEXT NOT NULL,
  url             TEXT NOT NULL,
  state           TEXT NOT NULL,   -- 'open' | 'merged' | 'closed'
  draft           BOOLEAN NOT NULL DEFAULT false,
  review_state    TEXT,            -- 'approved' | 'changes_requested' | NULL
  author_login    TEXT,
  head_ref        TEXT NOT NULL,
  base_ref        TEXT NOT NULL,
  opened_at       TIMESTAMPTZ,
  merged_at       TIMESTAMPTZ,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT github_pull_requests_state_chk
    CHECK (state IN ('open', 'merged', 'closed')),
  UNIQUE (repo_full_name, number)
);

CREATE TABLE ticket_pull_requests (
  ticket_id       UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  pull_request_id UUID NOT NULL REFERENCES github_pull_requests(id) ON DELETE CASCADE,
  closes          BOOLEAN NOT NULL DEFAULT false,
  linked_by       UUID REFERENCES users(id) ON DELETE SET NULL,
  linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY (ticket_id, pull_request_id)
);
```

Two columns on the join table carry all the behaviour. **`closes`** says this pull
request may move the ticket, not merely mention it. **`linked_by`** is `NULL` when the
link was detected and a member id when a person made it — and that distinction is what
the next section rests on.

`state` is one column with a CHECK rather than a pair of booleans, because `merged` and
`closed` are not independent: a merged pull request is closed, and two booleans admit a
fourth state that GitHub cannot produce.

## Detection is a pure function

`PrLinkParser` takes `headRef`, `title` and `body` and returns a set of
`(key, closes)`. No repository, no HTTP, no clock. Keys are matched against the real team
keys afterwards by the caller, so an unknown key is *not a link* rather than an error —
`ARCH-12` in a body is somebody else's tracker, not a refusal.

The rule, which refines the one agreed in conversation:

| Where the key appears | Result |
|---|---|
| Branch name — `feat/kan-142-overlap` | link, `closes = true` |
| `Fixes` / `Closes` / `Resolves` + key, in title or body | link, `closes = true` |
| A bare mention in title or body | link, `closes = false` |

Naming a branch after a ticket is a declaration of intent at least as strong as a keyword,
and it is the common case — a rule that required `Fixes` in the body would leave most real
pull requests unable to finish anything, which is the automation nobody trusts. A bare
mention stays inert, which is what keeps `unlike KAN-99, this one...` from closing KAN-99.

Matching is case-insensitive because branches are lowercase in practice
(`feat/kan-142-…`) and keys are uppercase in Kanso.

Re-parsed on `opened`, `edited`, `reopened` and `ready_for_review`. Not on `synchronize`:
new commits change no field the parser reads.

## Automation does not undo a person

**A link with `linked_by` set is never removed by parsing.** A detected link whose key has
disappeared from the branch, the title and the body is removed; a link a member made by
hand survives an edit that removes the mention, survives a retitle, survives everything
except that member unlinking it.

This is one `if` and it is the difference between a feature people keep and a feature
people work around. The failure it prevents is specific: somebody links a pull request to
a ticket the pull request never names, an unrelated edit re-runs the parser, and the link
they made silently vanishes.

## What the ticket shows

A section in `apps/web/src/components/detail-panel.tsx`. Above the list, the branch name
to copy — `feat/kan-142-overlap-warning`, derived from key and title, lowercase and
slugged. Then one row per pull request: repository, `#418`, title, a state pill, the
author, the link out.

The pill reads the two columns together, because `state` alone is not what a person wants
to know:

| Row | Pill |
|---|---|
| `state=open`, `draft=true` | Draft |
| `state=open`, `review_state=NULL` | In review |
| `state=open`, `review_state='changes_requested'` | Changes requested |
| `state=open`, `review_state='approved'` | Approved |
| `state=merged` | Merged |
| `state=closed` | Closed |

`[Open the PR]` sits at the end, active under the conditions part three sets out.

## What the pull request shows

Kanso writes a bounded block into the body:

```
<!-- kanso:start -->
**[KAN-142](https://kanso.example.com/t/KAN-142)** · In review · P2 · @elie
<!-- kanso:end -->
```

The markers are the whole design. A rewrite replaces exactly what is between them and
never touches a line somebody wrote, which is the difference between a body Kanso
maintains and a body Kanso owns. If the markers are absent, the block is appended once.

A comment was considered instead and rejected: a comment is buried by the next one, so
keeping it current means either editing our own comment — the same problem with worse
placement — or posting a new one per change, which is noise on somebody's notification
list.

**Rewritten on link and on ticket status change.** Not on every field: status is what a
reviewer reads at the top of a pull request, and priority churn is not worth an API write.

## `github_jobs`, and the duplication it admits

That rewrite needs a transactional outbox — the ticket's status change and the intent to
tell GitHub about it must commit together, or a rollback publishes a status that never
existed. Kanso already has exactly this machinery for Notion: `sync_jobs`,
`SyncEntityType`, `SyncWorker`, attempts and backoff and `last_error`.

`github_jobs` and `GithubWorker` **duplicate that shape**, deliberately. The alternative —
a `target` column on `sync_jobs` so one worker drains two destinations — is less code and
it was rejected: it modifies a mirror that works, for a need that is not the mirror's,
and it makes every future change to Notion sync a change that can break GitHub. Two small
tables that each do one thing beat one table that does two.

> **Superseded by KAN-16 (`V26`).** The rejected alternative is what was built. `sync_jobs`
> is now `outbound_jobs`, with a `destination` column beside `entity_type`, a collapse rule
> keyed on `(destination, entity_type, entity_id)`, and an `OutboundWorker` that drains one
> destination at a time through an `OutboundJobHandler` per destination — Notion's being
> one of them. The objection above stands as written and is answered by the split rather
> than by a second table: what a job *means* lives in the handler, so a change to the
> Notion push cannot reach GitHub's. This section's `github_jobs` table and `GithubWorker`
> should not be built; KAN-18 is a `GithubOutboundHandler`, a value in the `destination`
> vocabulary, and whatever widening of `entity_type` and `operation` it needs.

```sql
CREATE TABLE github_jobs (
  id         BIGSERIAL PRIMARY KEY,
  kind       TEXT NOT NULL,   -- 'body_block'
  payload    JSONB NOT NULL,
  attempts   INT NOT NULL DEFAULT 0,
  run_after  TIMESTAMPTZ NOT NULL DEFAULT now(),
  last_error TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

One `kind` today. It is a column rather than an absence because the second kind is
foreseeable and a table with a discriminator costs nothing to add and a migration to
retrofit.

---

# Part three — inbound, and the guards

## The webhook

`POST /api/github/webhook`, no session, opened in `SecurityConfig` on the sibling list
the MCP door adds — never in `PublicRoutes`, whose test asserts three patterns under
`/api/public/`.

**The signature, and the trap.** `X-Hub-Signature-256` is an HMAC-SHA256 of the **raw
body** with the webhook secret. The controller must therefore receive bytes, not a
deserialised object: a body Jackson has parsed and re-serialised has different whitespace
and key order, and the HMAC fails. This presents as "GitHub is sending bad signatures",
which is why it is written here rather than discovered. Comparison is constant-time.

An unsigned or wrongly signed request is **401 with no body**, and nothing is parsed
before the signature passes. This is an unauthenticated endpoint that writes rows; the
signature is its only guard, so it is the first test written.

**Idempotence.** GitHub retries. `X-GitHub-Delivery` is a UUID, and
`github_deliveries (delivery_id UUID PRIMARY KEY, received_at TIMESTAMPTZ)` makes a
redelivery a no-op through insert-or-skip. The pull request upsert would be idempotent
without it — it keys on `(repo_full_name, number)` — but the activity feed would collect
duplicates. The feed pays for the table, not the state.

Rows older than thirty days are pruned by the same scheduler that already runs in
`schedule/`. Thirty days is longer than GitHub retries and short enough that the table
stays small.

## The events

| Event | Effect |
|---|---|
| `pull_request` `opened` / `edited` / `reopened` | upsert, re-parse links |
| `pull_request` `ready_for_review` | upsert, **ticket → In review** |
| `pull_request` `converted_to_draft` | upsert. No transition |
| `pull_request` `closed` with `merged: true` | upsert, **ticket → Done** |
| `pull_request` `closed` without merge | upsert. No transition |
| `pull_request` `synchronize` | nothing. Only commits changed |
| `pull_request_review` `submitted` | `review_state`. Displayed, no transition |
| `installation` created / deleted / suspend / unsuspend | row lifecycle |
| `installation_repositories` | logged, stored nowhere — there is no repositories table |

An `opened` event on a non-draft pull request transitions too: `ready_for_review` only
fires when a draft is promoted, so a pull request opened ready would otherwise move
nothing.

Two transitions, not five. `converted_to_draft` sending a ticket back to In progress and
`closed` sending it back would both be defensible, and both were rejected together: they
are the automatic *backward* moves, and an automatic backward move is the origin of every
"why did my ticket change" conversation. Forward is inferable; backward is a judgement.

## Three guards

These are the tests that carry part three.

1. **Never backwards.** Statuses rank `backlog < todo < in_progress < in_review < done`.
   A transition to a lower rank is dropped. `canceled` is outside the ranking and is
   never touched by automation in either direction — cancelling is a decision, and a
   merge is not evidence against it.

2. **Never over a person.** If the ticket's most recent `status_changed` activity has a
   non-null `actor_id` and is newer than the webhook event's timestamp, the transition is
   abandoned. You moved the ticket to Done by hand while the pull request sat open; the
   pull request does not pull it back to In review. The comparison is against the event's
   own timestamp rather than `now()`, so a delivery retried an hour later does not win a
   race it lost.

3. **Only `closes` links transition.** A bare-mention link displays and does not act.

## Who moved it

If `author_login` — or the sender's login, for a merge somebody else performed —
resolves through `github_accounts` to a Kanso member, that member is the `actor_id`, and
the feed reads *Elie moved KAN-142 to Done*. If it does not, `actor_id` stays `NULL`,
which the column already allows, and the feed reads *KAN-142 moved to Done via #418*.

This needs one new `ActivityKind`, `pull_request_linked`, and therefore an `ALTER` of
`activity_kind_chk` — the CHECK is closed and the enum mirrors it, by design. The
existing `status_changed` kind carries the transition itself, with `payload.via_pr` naming
the pull request.

---

# Part four — outbound, and the agent

## A team owns a repository

A ticket does not know its repository, and finding a branch by scanning an
installation's repositories costs one API call per repository. So `teams.github_repo`, set
once from a picker that lists the installation's repositories, overridable at the moment a
pull request is opened.

One column, no guessing, and it follows the principle the MCP spec already states for
teams and projects: **structure is a decision a person makes once.** Finding the branch
becomes a single call, `GET /repos/{repo}/branches/{branch}`.

A team with no repository set shows the branch name and an inactive button explaining
which setting is missing — not a disabled control with no explanation, which is the
failure mode of every integration nobody finishes configuring.

## The gesture

The ticket shows `feat/kan-142-overlap-warning`. You `git switch -c`, you work, you push.
`[Open the PR]` activates, pre-filled: title from the ticket, body carrying
`Fixes KAN-142`, the bounded block, and *requested by @elie*. You confirm; the pull request
is created and **linked in the same transaction**, so the link exists without waiting for
the webhook — which arrives afterwards and is absorbed by the upsert.

That ordering matters: a link that appears only when a webhook lands is a link that looks
broken for the two seconds a person is still watching.

## Two identities, one service

`GithubWriter` resolves the token in one place:

1. The member's own, from `github_accounts`, refreshed if expired.
2. Failing that, the installation token — a JWT signed RS256 with the PEM (`iss` the app
   id, `exp` no more than ten minutes) exchanged at
   `POST /app/installations/{id}/access_tokens` for a token valid one hour, cached in
   memory with a margin so it is never used in its last minute.

Whichever wrote is recorded on the activity row, and the body names the requester either
way. Both facts are in part one; they are the mitigation for a choice whose cost was
accepted openly.

## Limits and refusals

`RateLimiter` from `sync.notion` is reused rather than reinvented. GitHub returns
`X-RateLimit-Remaining` and `Retry-After` on 403 and 429; both are honoured.

Three refusals are real and get sentences rather than 500s:

- `No commits between main and feat/kan-142-x` — the branch exists and is empty. The
  message says push first.
- `A pull request already exists for tykok:feat/kan-142-x` — **and this is not a
  refusal.** Kanso fetches that pull request and links it. The member's intent was a link;
  they get one.
- A suspended installation — the settings screen says so, and the button explains rather
  than failing.

## One tool, two extended

The MCP spec argues that six well-described tools beat forty, and this document does not
break that. GitHub adds **one** tool and extends two.

**`kanso_pr`** — open a pull request for a ticket, link an existing one, or unlink.
`dry_run` defaults to **true**, as `kanso_plan` does, and for a stronger reason: a pull
request is **outward-facing**. It notifies reviewers and appears in an organisation's
feed. An agent that opens one in error does not dirty a database, it interrupts people.
The dry run resolves the repository, checks the branch exists, reports the title and body
that would be sent, and writes nothing.

**`kanso_context` extended** — linked pull requests become a field on the response that
already describes everything about one ticket. No new tool to read.

**`kanso_search` extended** — `has_pr` and `pr_state`. "Tickets in review with no open
pull request" becomes a question instead of a script.

Moving the Kanban needs nothing new: `kanso_update` already does it.

`kanso_pr` holds **no logic**. It calls the same `GithubService` the UI button calls, with
the same identity resolution and the same guards, keeping the spec's rule — *no MCP tool
contains business logic* — intact, with `PlanService` still its single argued exception.

## A third scope: `kanso:github`

The MCP spec defends exactly two scopes and distrusts portioning systems that can
disagree. The argument that overrides it here is not about portioning; it is about the
**truthfulness of the consent screen**.

A member who granted `kanso:write` before GitHub existed would silently gain the right to
open pull requests in their organisation. A sentence that was true when they read it would
become false without anyone re-reading it. So there is a third scope, existing grants do
not have it, and an agent holding one gets `403` with `error="insufficient_scope"` and
`scope="kanso:github"` — which the MCP door already implements as a step-up path.

`ScopeCopy` gains its sentence: *Open and link pull requests in the repositories you have
connected.*

---

## Notion

The mirrored ticket gains one rich-text property listing its pull requests as links —
`#418 merged`. Rich text and not URL, because Notion has no multi-URL property and a
ticket has many pull requests.

No new machinery: a link or a state change enqueues a ticket `UPSERT` on the existing
`sync_jobs`, and `NotionMapper` gains a property. People who never open Kanso see the
pull request, which is the promise the README makes.

This is also the ricochet named in the risks: a merged pull request moves a ticket, which
pushes the mirror. A noisy repository becomes noise in somebody else's tool.

---

## Testing

Five assertions carry this work.

1. **An invalid signature is never parsed.** Raw body, HMAC, constant-time comparison,
   401 with no body — asserted by posting a real payload with a wrong signature and
   checking that no row moved. First test written, because it is the only guard on an
   unauthenticated endpoint that writes.
2. **A redelivery produces nothing.** The same `X-GitHub-Delivery` twice: one activity
   row, not two.
3. **Parsing never undoes a person.** A hand-linked pull request survives an `edited`
   that removes the key from the title and the body.
4. **A merge neither reverses nor overwrites.** A ticket moved to Done by hand, then a
   pull request reopened: still Done. A ticket already Done, pull request merged: still
   Done, and no duplicate activity.
5. **An MCP token cannot open a pull request outside its teams.** The MCP premise — *an
   agent is not a new kind of user* — in its GitHub edition, and the test that proves this
   spec did not quietly add a second permission system.

Beyond those: `PrLinkParser` as a table of pure cases (branch, title, body, each keyword,
unknown keys, lowercase input, several keys in one body, a key inside a longer word);
manifest `state` mismatch; the installation JWT's claims and its ten-minute ceiling; the
bounded-block rewrite over a body with markers, without markers, and with a marker
somebody hand-edited.

**Playwright does not talk to GitHub.** The inbound path is tested by posting **real
payloads captured once and committed** as fixtures, signed with a test secret. Honest and
cheap; the alternative is a mock whose shape drifts from GitHub's and passes anyway.

The suite follows the house convention the MCP spec records: `PostgresTest` is
`WebEnvironment.NONE` and controller tests autowire the controller. The webhook needs a
real request for its raw body, so it reuses `MeVersionTest`'s context configuration rather
than adding another.

---

## Risks, stated plainly

**The manifest flow is unverified.** Task one of plan three, one hour. A negative answer
costs the setup experience and nothing else — everything downstream reads credentials from
`InstanceSettingsService`.

**The PEM in the database is the key that *is* the App.** Encrypted by `SecretBox`, but a
leak is worth every installed repository, which is a larger blast radius than any secret
Kanso holds today. GitHub can rotate it, so the settings screen carries a **Regenerate
key** gesture — a secret with no rotation story is a secret that is never rotated.

**`/api/github/webhook` is unauthenticated and writes rows.** One HMAC stands between it
and a stranger moving tickets. Tested first, and worth watching once deployed.

**The mixed identity path.** Two write paths, and an author that depends on configuration.
Recording it makes it legible; it does not make it simple.

**GitHub reaches Notion by ricochet.** A merge moves a ticket, which pushes the mirror, in
front of people who do not know what a pull request is.

**Still no CI** (`docs/follow-ups.md`). The MCP spec made the same observation about an
authorisation server; this adds an unauthenticated endpoint to the pile. A GitHub Action
running Gradle, Vitest and Playwright is the obvious place to fix both, and if it stays out
of scope the risk is accepted explicitly rather than by omission.

**Two transitions will feel too few to somebody.** They are chosen, not a first step, and
the configurable mapping was rejected on purpose: a behaviour that differs between two
instances is a behaviour nobody can debug from a bug report. If it changes, it changes for
everyone and it is argued again here.

---

## Three plans, after the MCP's two

**Plan three — the GitHub door.** The manifest spike, conversion, installation, the
member link, `V17`, the settings screen, environment pinning. Deliverable: settings says
*connected to the tykok organisation*, and nothing is linked yet — everything hard about
credentials is proven before a single pull request exists.

**Plan four — the link.** `PrLinkParser`, the webhook and its signature, the two
transitions and their three guards, the ticket's pull request section, the bounded block,
`github_jobs`, the Notion property.

**Plan five — writing, and the agent.** `teams.github_repo`, `[Open the PR]`, the two
identities, `kanso_pr`, the `kanso_context` and `kanso_search` extensions, the
`kanso:github` scope.
