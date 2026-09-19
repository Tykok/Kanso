<!--
  Target `develop` unless this is a hotfix or a release branch. CONTRIBUTING.md says
  which, and why.

  This template deliberately does not repeat CONTRIBUTING.md. CI already runs the API
  suite and the web checks on this pull request and reports its own verdict, so asking
  you to tick them here would be asking you to restate a bot. What is below is the part
  CI cannot see.

  Delete a section that does not apply rather than ticking it dishonestly — an untouched
  checklist and a half-honest one look identical to a reviewer, which is what makes the
  second one expensive.
-->

## What moves, and why

<!--
  The why is the part a reviewer cannot reconstruct. The diff already says what changed;
  it never says what was wrong with the alternative you did not take, or what broke to
  make this necessary. Write that — it is the same thing the commit body is for.
-->



## What CI will not tell you

<!--
  Playwright and `next build` are not in CI, and `ci.yml` argues why at length. That
  makes them yours to have run, or yours to say you did not. An honest gap is reviewable;
  a silent one is not.
-->

- [ ] `e2e/` — stack up first, from the repository root:
      `KANSO_AUTH_MODE=dev docker compose up -d --build --wait`. If you overrode the
      ports, set `KANSO_WEB_URL` **and** `KANSO_API_URL` together: setting only the first
      drives your stack while seeding somebody else's, which has already happened to a
      real instance
- [ ] `cd apps/web && pnpm build`
- [ ] Not run, because: <!-- … -->
- [ ] Not relevant to this change

## The things that bite

<!-- Tick only what the change touches. Delete the rest. -->

- [ ] **Migrations.** A merged migration is immutable — a new `V<n+1>`, never an edit to
      an existing `V<n>`. And the number moves under you: another branch can merge its
      migration while yours is open, so re-check the highest `V` on the base branch
      immediately before you commit rather than when you started.
- [ ] **`docker/Caddyfile`.** Every request mapping outside `/api` needs one literal
      `reverse_proxy <path> <upstream>` line. `CaddyRoutingTableTest` enumerates the
      mappings off Spring and fails when one is uncovered — but folding routes into a
      `handle` block still satisfies Caddy while blinding the test, and an unrouted
      mapping does not 500: it lands on Next, which answers its own HTML 404, and nothing
      in the API's logs records that the request happened.
- [ ] **`KANSO_TLS` and `X-Forwarded-For`.** Under `auto` Caddy is the edge and strips the
      inbound header; under `off` the operator's proxy is. The API reads the leftmost
      entry, and the registration rate limit buckets an unauthenticated write on exactly
      that address.
- [ ] **`NEXT_PUBLIC_API_URL` stays absent.** Next inlines it at build time, so setting it
      welds one API origin into a published image.
- [ ] **The two compose files stay two things.** The root one builds from source onto two
      ports; `docker/docker-compose.yml` pulls the published image onto one origin.
      Neither grows towards the other.
- [ ] None of the above.

## Documents

<!--
  One question decides where a document goes: does it describe something that changes?
  CONTRIBUTING.md has the long version. The trap worth repeating here is the last one:
  comments in the code cite wiki pages by title, nothing in CI resolves those links, so
  renaming a page breaks them silently. Grep before you rename one.
-->

- [ ] `docs/` is still true for what this changes, or was changed in this same diff
- [ ] No wiki page was renamed, or the citations to it were updated with it
- [ ] Nothing here describes a moving artefact

## Before review

- [ ] Hand-wrapped at 100 columns, and **no formatter was run**
- [ ] Any nearby comment this change made false was fixed as part of the change
