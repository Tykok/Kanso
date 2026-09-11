-- Kanso knows what the work is. GitHub knows whether it is done. Today nobody tells either
-- one about the other, so the same fact gets typed twice — a branch named after a ticket, a
-- ticket dragged to Done after a merge — and the second typing is the one that gets
-- forgotten.
--
-- This is the schema for the whole of the wiki's `Spec - The repository, on the ticket`,
-- and it is **one migration rather than four** because the spec says so and because the
-- reason it gives is right: parts one, two and three each add a table, and a table whose
-- argument is three sections away is a table the next reader deletes. The columns arrive
-- together and the argument for each one arrives beside it.
--
-- The spec numbers this file `V17`. That number was taken by `V17__cycle_rollover.sql`
-- while this was still a document; the number here is `V36`, checked against
-- `db/migration` immediately before commit rather than trusted from the spec. It is worth
-- naming because it is not the only number in that spec that has gone stale — see the
-- vocabulary section at the bottom, where the spec names `V26` as the authority on a CHECK
-- that `V31` has since rewritten.
--
-- ---------------------------------------------------------------------------
-- What is *not* here, so that an empty table is not mistaken for an unfinished one.
--
-- The spec is three plans and this migration serves all three, but the code landing with it
-- is plan four — the inbound half. So `github_installations` and `github_pull_requests`
-- fill from the webhook, and `github_accounts` fills only when the member-consent flow of
-- plan three exists. A `github_accounts` table with no rows is not a bug: it is the reason
-- the feed says *KAN-142 moved to Done via #418* instead of naming a person, which is the
-- documented fallback and not a degradation.
-- ---------------------------------------------------------------------------


-- ---------------------------------------------------------------------------
-- Part one — the App, and there is one of it.
--
-- One App per instance. Not per team: an App is an *identity on GitHub*, and an instance
-- that presents two of them to the same organisation is an instance whose audit log is
-- harder to read than its configuration was to write.
--
-- So the credentials go on `instance_settings`, which already holds exactly one row and
-- already has the shape for this — `V14__notion_oauth.sql` put a client id, an encrypted
-- secret and a workspace name there, `config/SecretBox.kt` does the encrypting, and
-- `InstanceSettingsService.load()` decides per field whether the environment or the
-- database wins. Following that rather than inventing a second home for secrets is the
-- whole of the argument.
--
-- `BYTEA` and the `_enc` suffix for all four secrets, for the reason `V14` gives: the
-- suffix is what stops the next reader passing the column straight to an HTTP client, and
-- the type is what makes it impossible.
--
-- **The private key is the one that is different in kind.** A leaked Notion secret is worth
-- one workspace. A leaked PEM *is* the App: it signs the JWT that mints an installation
-- token for every repository anybody has selected, in every organisation. It is encrypted
-- like the rest and that is not the mitigation — the mitigation is that GitHub can rotate
-- it, so the settings screen owes this column a **Regenerate key** gesture. A secret with
-- no rotation story is a secret that is never rotated.
-- ---------------------------------------------------------------------------
ALTER TABLE instance_settings
  ADD COLUMN github_app_id             TEXT,
  ADD COLUMN github_app_slug           TEXT,
  ADD COLUMN github_client_id          TEXT,
  ADD COLUMN github_client_secret_enc  BYTEA,
  ADD COLUMN github_webhook_secret_enc BYTEA,
  ADD COLUMN github_private_key_enc    BYTEA;

-- All six nullable, and read as *one group or not at all* — the rule `c3d1a95` established
-- for the Notion app, and the subtle one. An app id from `KANSO_GITHUB_APP_ID` married to a
-- secret from the database is a configuration nobody intended and it fails at the first
-- signature, a long way from the mistake. Six `NOT NULL`s would have said the same thing
-- badly: they would make an instance with no GitHub connection an instance with no
-- `instance_settings` row.


-- ---------------------------------------------------------------------------
-- Where Kanso was installed.
--
-- The primary key is **GitHub's own installation id**, not a `gen_random_uuid()`. There is
-- nothing for a surrogate key to buy: every inbound payload carries `installation.id`, so a
-- local id would mean a lookup on every delivery to reach a row we could have addressed
-- directly, plus a `UNIQUE` on the real id to keep the mapping honest. The identifier
-- already exists and it is already unique; minting a second one only creates the chance for
-- the two to disagree.
--
-- `suspended_at` rather than a `suspended` boolean, and rather than deleting the row. An
-- organisation can suspend an App and unsuspend it, so the row has to survive the
-- suspension — and *when* it was suspended is the sentence the settings screen wants
-- ("suspended since Tuesday"), which a boolean cannot say. `NULL` means active, which makes
-- the common query a null check rather than a comparison.
--
-- A deleted installation *is* deleted: `installation` `deleted` drops the row, and the
-- cascade below takes its pull requests with it. That is the honest reading — an App
-- removed from an organisation can no longer be asked anything about those repositories, so
-- a state pill on a ticket would be reporting a fact nobody can refresh. The link's *loss*
-- is visible, which is better than a link that silently stops moving.
-- ---------------------------------------------------------------------------
CREATE TABLE github_installations (
  id            BIGINT PRIMARY KEY,

  -- The organisation or user the App is installed on. Denormalised from the payload on
  -- purpose: the settings screen says "connected to the tykok organisation" and asking
  -- GitHub for a name it sends on every delivery would be a network call to render a label.
  --
  -- Renameable, like every GitHub login, and therefore *not* an identity — it is refreshed
  -- from whatever the latest payload says and nothing joins on it.
  account_login TEXT NOT NULL,

  -- Closed here and closed again in Kotlin, like every other vocabulary in this schema.
  -- Two values because GitHub has two: an App is installed on an organisation or on a
  -- personal account, and the difference is a different URL for the App-creation screen.
  account_type  TEXT NOT NULL,

  suspended_at  TIMESTAMPTZ,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT github_installations_account_type_chk
    CHECK (account_type IN ('Organization', 'User'))
);

-- GitHub's own capitalisation, kept rather than lowercased. It is what arrives in
-- `installation.account.type` and what would be sent back if anything ever sent it; a
-- vocabulary that differs from the wire by a `.lowercase()` is a vocabulary with a
-- conversion nobody remembers on one of the two paths.


-- **There is no repositories table**, and that is the load-bearing omission of this schema.
--
-- The obvious design mirrors the selection: an `installation_repositories` event arrives, we
-- store the list, and the ticket's repository picker reads it locally. It fails for a reason
-- that has nothing to do with correctness at the moment it is written. **The repository
-- selection is edited on GitHub, by people who are not looking at Kanso** — an admin adds a
-- repository to the App from a settings page in another tab, and whether Kanso ever hears
-- about it depends on a webhook delivery arriving and succeeding. A mirror of that list goes
-- stale silently, and stale-silently is the worst failure mode available here, because the
-- symptom is *a button that does nothing* rather than an error anybody can report.
--
-- What routing actually needs is already on every payload: `installation.id` and
-- `repository.full_name`. `github_pull_requests` stores the full name as text beside the
-- installation, which is enough to answer "who does this pull request belong to" without
-- any list at all. And the settings screen's repository list is *asked of GitHub when it
-- renders*, which is one call that is either current or visibly failed.
--
-- The consequence, stated rather than discovered: `installation_repositories` is logged and
-- stored nowhere. A reader looking for the handler that writes it should find this paragraph
-- instead.


-- ---------------------------------------------------------------------------
-- Whose account it is.
--
-- Installation grants Kanso access to repositories. It does not grant Kanso the right to act
-- *as* a person — that is a second, per-member consent, and this table is where it lands.
--
-- `user_id` is the primary key, so a member links at most one GitHub account. The
-- alternative — a member with two — has no reader: every question this table answers is
-- "which Kanso member is this GitHub login" or "may I write as this member", and neither
-- improves with a choice to make.
--
-- `github_user_id` is `UNIQUE` and **it is the id, not the login**. A login is renameable,
-- and a webhook payload that arrives after a rename must still resolve to the right member;
-- keying on the login would make "who moved this ticket" wrong for exactly the people who
-- renamed themselves, silently, and only sometimes. The login is stored beside it because
-- the feed and the pull request row display it, and it is refreshed rather than trusted.
--
-- The `UNIQUE` is also a real refusal and worth naming: one GitHub account cannot be linked
-- to two Kanso members. Without it, two members could both claim the same login and "who
-- moved it" would have two answers.
--
-- `ON DELETE CASCADE` from `users`: a closed account takes its grant with it. The token in
-- this table can act on somebody's repositories, so the one thing it must not do is outlive
-- the member who consented to it.
-- ---------------------------------------------------------------------------
CREATE TABLE github_accounts (
  user_id           UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  github_user_id    BIGINT NOT NULL UNIQUE,
  github_login      TEXT NOT NULL,

  -- `SecretBox` again, and `NOT NULL` this time — unlike the App's six above, a row here
  -- exists *because* a member completed a consent flow, so a row without a token is a row
  -- that means nothing.
  access_token_enc  BYTEA NOT NULL,

  -- Nullable because GitHub's user-to-server tokens are only refreshable if the App opted
  -- into expiring tokens. Both shapes are real, so "no refresh token" has to be storable
  -- rather than assumed away.
  refresh_token_enc BYTEA,
  expires_at        TIMESTAMPTZ,
  linked_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The lookup that the feed does on every inbound event: a login on a payload, to a member.
-- It goes through `github_user_id` when the sender's id is on the payload and through the
-- login when it is not, and the second is the one without an index — the `UNIQUE` above
-- already indexes the id. Lowercased because GitHub logins are case-insensitive and the
-- payload's capitalisation is not stable enough to join on.
CREATE UNIQUE INDEX github_accounts_login_idx ON github_accounts (lower(github_login));


-- A team owns a repository, and it is one column because finding the branch is otherwise
-- one API call per repository in the installation. Set once from a picker, overridable at
-- the moment a pull request is opened — **structure is a decision a person makes once**,
-- which is the principle the MCP spec already states for teams and projects.
--
-- Nullable, and the null has a screen: a team with no repository set shows the branch name
-- and an inactive button *explaining which setting is missing*, rather than a disabled
-- control with no explanation, which is the failure mode of every integration nobody
-- finishes configuring.
--
-- Not a foreign key to anything, because of the paragraph above — there is no repositories
-- table to point at. It is `'tykok/kanso'`, GitHub's own `full_name`, unvalidated here
-- because the only thing that can validate it is GitHub.
ALTER TABLE teams ADD COLUMN github_repo TEXT;


-- ---------------------------------------------------------------------------
-- Part two — two tables, because both sides are plural.
--
-- A pull request can close two tickets. A ticket can have three pull requests — a revert, a
-- follow-up, a split. Neither side is the "one", so neither side can hold a column pointing
-- at the other, and `tickets.pull_request_url` would be a schema that is wrong the first
-- time somebody reverts something.
--
-- The repository already models exactly this shape for documents: `doc_blocks` carries the
-- block and `doc_block_tickets` carries the reference, and `V9__documents.sql` argues it at
-- length. This follows it rather than re-deciding it.
-- ---------------------------------------------------------------------------
CREATE TABLE github_pull_requests (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Which installation can be asked about this pull request. The cascade is the paragraph
  -- above: an App removed from the organisation takes these rows with it, because a state
  -- pill nobody can refresh is worse than an absent one.
  --
  -- It also means the installation row must exist before the pull request does. The
  -- `installation` event is not a reliable way to get there — Kanso may have been installed
  -- before this migration ran, and a `pull_request` delivery can be the first one that ever
  -- arrives — so `GithubWebhookService` upserts the installation from whatever payload names
  -- it, not only from the `installation` event. Stated here because the alternative is an FK
  -- violation on a webhook, which presents as "GitHub events do nothing".
  installation_id BIGINT NOT NULL REFERENCES github_installations(id) ON DELETE CASCADE,

  -- `'tykok/kanso'`. Text and not a reference, for the reason above.
  repo_full_name  TEXT NOT NULL,

  -- `#418`. Unique per repository, which is what the upsert keys on.
  number          INT  NOT NULL,

  -- GitHub's GraphQL id. Not the key — `(repo, number)` is what a payload and a human both
  -- carry — but stored because the v4 API needs it and re-deriving it is a request.
  node_id         TEXT NOT NULL,

  title           TEXT NOT NULL,
  url             TEXT NOT NULL,

  -- **One column with a CHECK, not a pair of booleans**, and this is the interesting one.
  --
  -- `merged` and `closed` are not independent: a merged pull request *is* closed. Two
  -- booleans therefore admit four combinations, one of which — merged and not closed —
  -- GitHub cannot produce, so every reader would have to know the rule and one of them
  -- eventually would not. Three values, and the impossible state is unrepresentable rather
  -- than merely undocumented.
  state           TEXT NOT NULL,

  -- Not folded into `state` for the opposite reason: a draft *is* open, and a pull request
  -- moves between draft and ready in both directions without its openness changing. It is a
  -- second, orthogonal fact about an open pull request, so it is a second column.
  draft           BOOLEAN NOT NULL DEFAULT false,

  -- Nullable, and the null is a real value: *nobody has reviewed this yet*. It reads as "In
  -- review" on the pill, which is why there is no `'pending'` in the vocabulary — a fourth
  -- word would give "not reviewed" two spellings, which `V35` refused for the same reason.
  --
  -- Two words, where GitHub's own review vocabulary has five. `commented`, `dismissed` and
  -- `pending` are all mapped to *no change* by the handler, because the question this column
  -- answers is the one the pill asks — has somebody blocked or unblocked this — and a
  -- comment does neither. The narrowing is here rather than in the display so that two
  -- readers cannot disagree about what a `commented` review meant.
  review_state    TEXT,

  -- Nullable because GitHub's `user` can be `null` on a pull request opened by an account
  -- that has since been deleted. Displayed, and resolved through `github_accounts` to find
  -- an actor for the feed.
  author_login    TEXT,

  -- `head_ref` is what `PrLinkParser` reads and it is the strongest signal in the whole
  -- feature, so it is `NOT NULL` and stored rather than parsed out of the URL.
  head_ref        TEXT NOT NULL,
  base_ref        TEXT NOT NULL,

  -- Nullable, both of them, and for different reasons. `opened_at` is GitHub's `created_at`,
  -- which is present on every payload but absent on a row we backfilled from a list call.
  -- `merged_at` is null for every pull request that is not merged, which is most of them —
  -- it is the timestamp *and* the evidence, and `state = 'merged'` without it would be a row
  -- that cannot say when.
  opened_at       TIMESTAMPTZ,
  merged_at       TIMESTAMPTZ,

  -- When Kanso last heard about it, not when GitHub last changed it. The distinction
  -- matters for the "never over a person" guard: that comparison uses the *event's* own
  -- timestamp, never this column and never `now()`.
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

  CONSTRAINT github_pull_requests_state_chk
    CHECK (state IN ('open', 'merged', 'closed')),

  CONSTRAINT github_pull_requests_review_state_chk
    CHECK (review_state IS NULL OR review_state IN ('approved', 'changes_requested')),

  -- The natural key, and the whole of what makes the inbound path idempotent. GitHub sends
  -- `opened`, then `synchronize`, then `edited`, then `closed` for one pull request, and
  -- every one of them is an upsert on this pair. A surrogate-only key would have made a
  -- redelivery a second row.
  CONSTRAINT github_pull_requests_repo_number_uniq UNIQUE (repo_full_name, number)
);

-- The cascade's own direction, which the unique key above does not serve.
CREATE INDEX github_pull_requests_installation_idx ON github_pull_requests (installation_id);


CREATE TABLE ticket_pull_requests (
  ticket_id       UUID NOT NULL REFERENCES tickets(id) ON DELETE CASCADE,
  pull_request_id UUID NOT NULL REFERENCES github_pull_requests(id) ON DELETE CASCADE,

  -- **This pull request may move the ticket**, as opposed to merely mentioning it.
  --
  -- Two columns on this table carry all the behaviour in part three, and this is the first.
  -- A branch named `feat/kan-142-x`, or a `Fixes KAN-142`, sets it true; a bare `unlike
  -- KAN-99, this one...` in a body creates a link with it false. That is what keeps a
  -- sentence about KAN-99 from closing KAN-99 while still letting a reader follow it.
  --
  -- `DEFAULT false` is the safe direction on purpose: a writer that forgets to say gets an
  -- inert link, not an automatic transition.
  closes          BOOLEAN NOT NULL DEFAULT false,

  -- The second, and the one the next section rests on. **`NULL` means the parser found this
  -- link; a member id means a person drew it.**
  --
  -- `ON DELETE SET NULL` rather than cascade: a closed account does not un-draw the links
  -- they made. What it costs is that the link becomes indistinguishable from a detected one
  -- and so becomes removable by parsing again — which is the correct reading, since there is
  -- no longer anybody whose intent the exception was protecting.
  linked_by       UUID REFERENCES users(id) ON DELETE SET NULL,
  linked_at       TIMESTAMPTZ NOT NULL DEFAULT now(),

  -- The pair is the identity: a ticket is linked to a pull request once. Not `(ticket,
  -- pr, closes)` — unlike `ticket_links` in `V33`, where two tickets genuinely stand in more
  -- than one relation at a time, `closes` is not a kind of link but a property of the one
  -- link, and a key including it would let the same pair exist twice with both answers.
  PRIMARY KEY (ticket_id, pull_request_id)
);

-- "Which tickets does this pull request close" — the read the merge handler does, which the
-- primary key's leading column cannot answer.
CREATE INDEX ticket_pull_requests_pr_idx ON ticket_pull_requests (pull_request_id);


-- **Automation does not undo a person**, and it is `linked_by` that makes it expressible.
--
-- A detected link whose key has disappeared from the branch, the title and the body is
-- removed — that is what keeps the set current when somebody fixes a typo'd key. A link a
-- member made by hand survives an edit that removes the mention, survives a retitle,
-- survives everything except that member unlinking it.
--
-- This is one `if` in `PrLinkService` and it is the difference between a feature people keep
-- and a feature people work around. The failure it prevents is specific and it is not
-- hypothetical: somebody links a pull request to a ticket the pull request never names —
-- which is the whole point of a manual link — an unrelated edit re-runs the parser, and the
-- link they made silently vanishes.


-- ---------------------------------------------------------------------------
-- Part three — the deliveries, and what pays for them.
--
-- GitHub retries. `X-GitHub-Delivery` is a UUID and this table makes a redelivery a no-op
-- through insert-or-skip.
--
-- **The pull request upsert would be idempotent without this table** — it keys on
-- `(repo_full_name, number)` and writes the payload's current state, so applying the same
-- delivery twice reaches the same row. What is not idempotent is the *activity feed*: a
-- second delivery of a merge writes a second `status_changed`, and a history that says a
-- ticket moved to Done twice is a history somebody has to explain.
--
-- So the feed pays for this table, not the state. Worth saying plainly, because the cheaper
-- reading — "the upsert is idempotent, drop the table" — is correct about everything except
-- the thing the table is for.
-- ---------------------------------------------------------------------------
CREATE TABLE github_deliveries (
  -- GitHub's `X-GitHub-Delivery`, as a UUID rather than text so a malformed header is
  -- refused by the type instead of inserted as a row that can never collide with anything.
  delivery_id UUID PRIMARY KEY,
  received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Rows older than thirty days are pruned by `GithubDeliverySweeper`, which is
-- `TrashSweeper`'s shape and for the same reasons — a separate short file so that "when
-- does this run" is one annotation, and absent in the test profile so no scheduler deletes
-- rows from under an assertion.
--
-- Thirty days is chosen at both ends. Longer than GitHub retries, which it gives up on
-- within about three days, so pruning can never resurrect a duplicate; short enough that a
-- busy instance's table stays small, since nothing reads these rows except the insert that
-- collides with them.
CREATE INDEX github_deliveries_received_idx ON github_deliveries (received_at);


-- ---------------------------------------------------------------------------
-- The two closed vocabularies this widens, and the grep that found their authorities.
--
-- Postgres has no `ALTER CONSTRAINT ... ADD VALUE` for a CHECK the way it does for an enum
-- type, so widening one means **re-stating it whole** — and re-stating it from the wrong
-- ancestor silently *revokes* whatever a later migration added. Kotlin carries on writing
-- the word and the database starts refusing the row: a failure with no compile error and no
-- obvious test. `V31` records the rule, `V35` records being caught by it.
--
-- So both lists below were grepped for in `db/migration` rather than copied from the
-- neighbour whose shape this file follows, and one of the two was not where the spec says.
-- ---------------------------------------------------------------------------

-- **`outbound_jobs_destination_chk` — the authority is `V31`, not `V26`.**
--
-- The spec is explicit and the spec is stale: it says this list is the one "`V26` left
-- holding exactly one value because it added no consumer and had nothing honest to add".
-- That was true when it was written. `V31__outbound_webhooks.sql` then became the second
-- destination and rewrote the CHECK to `('notion', 'webhook')`, so restating `V26`'s
-- `('notion')` plus `'github'` would have dropped `'webhook'` — breaking the outbound
-- webhooks that shipped one migration ago, from a file that never mentions them.
--
-- This is the same trap `V35` documents from the inside, arriving through a *document* this
-- time rather than through a branch. The design of record is the authority on the design;
-- the newest migration is the authority on the constraint.
--
-- Three values, and the third is GitHub's. What arrives on the queue with it is argued in
-- `GithubOutboundHandler` rather than here: `entity_type` and `operation` are **deliberately
-- untouched**, because the bounded-block rewrite is *about a ticket* and `V26`'s
-- `outbound_jobs_entity_chk` already lists `ticket` while `outbound_jobs_operation_chk`
-- already lists `upsert`. Re-stating a list this file has no reason to change would be one
-- more chance to drop a word from it — `V35`'s reasoning about `activity_entity_type_chk`,
-- applied to two lists instead of one.
--
-- The collapse rule then does real work for free: at most one `pending` job per
-- `(destination, entity_type, entity_id)` means a ticket dragged across three columns in ten
-- seconds is one queued body rewrite rather than three, because the push writes the block's
-- current content and not a delta.
ALTER TABLE outbound_jobs DROP CONSTRAINT outbound_jobs_destination_chk;
ALTER TABLE outbound_jobs ADD CONSTRAINT outbound_jobs_destination_chk
  CHECK (destination IN ('notion', 'webhook', 'github'));

-- **`activity_kind_chk` — the authority is `V35`.**
--
-- Sixteen words from `V35__custom_fields.sql`, verified by grep to be the newest statement
-- of this list in `db/migration`, plus `pull_request_linked`. The chain `V35` writes down
-- continues: `V8` wrote eleven, `V17` added `carried_over`, `V21` `estimated`, `V23`
-- `health_posted`, `V30` `token_revoked`, `V35` `field_set`. From now on *this* migration is
-- the authority, and the next one to widen it must re-state this list — after checking that
-- no migration numbered above it has done so first.
--
-- **One new kind, not two.** Linking a pull request is an event with no other narrator: it
-- is not an edit to any scalar of the ticket, so none of the sixteen fits, and a link that
-- happened silently would make "why is this pull request on my ticket" unanswerable — which
-- matters more here than for most kinds, because a link can be drawn by *automation* reading
-- a branch name, with no person to ask.
--
-- The transition itself is **not** a new kind. A merge moving a ticket to Done is a
-- `status_changed` like every other status change, with `payload.via_pr` naming the pull
-- request. Giving automation its own kind would have split the one question a history is
-- kept for — when did this become Done, and why — across two vocabularies, and every reader
-- of the feed would have had to know both. The feed reads *Elie moved KAN-142 to Done* when
-- the author resolves through `github_accounts`, and *KAN-142 moved to Done via #418* when
-- it does not, which is `activity.actor_id` already being nullable doing exactly the job
-- `V8` left it able to do.
ALTER TABLE activity DROP CONSTRAINT activity_kind_chk;

ALTER TABLE activity ADD CONSTRAINT activity_kind_chk
  CHECK (kind IN ('created', 'status_changed', 'priority_changed', 'assigned',
                  'unassigned', 'renamed', 'scheduled', 'archived', 'commented',
                  'labelled', 'mirror_pushed', 'carried_over', 'estimated',
                  'health_posted', 'token_revoked', 'field_set',
                  'pull_request_linked'));

-- `activity_entity_type_chk` is deliberately untouched, `V35`'s reasoning unchanged: these
-- rows are written against `ActivityEntity.TICKET`, which `V8` already allowed. A pull
-- request is not an entity Kanso narrates a feed *for* — it is the reason a ticket's feed
-- has a row.
