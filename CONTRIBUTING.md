# Contributing to Kanso

Kanso was a private repository with one author until recently, and everything about it
was built for that: the history is merge commits, `main` was pushed to directly, and CI
ran on every push because there was no one else's push to catch. It is public now, and
this file is the part of that change that has to be written down rather than inferred.

Read it before opening a pull request. It is short, and the parts that will surprise you
are the branch you target and the fact that pushing a branch no longer runs anything.

## Two long-lived branches

| Branch | What it is | What lands there |
|---|---|---|
| `main` | What is released. Every tag is cut here. | Fast-forwards from `develop`, and hotfixes. |
| `develop` | The next release, integrated. | Everything else. |

**Target `develop`.** Features, fixes, documentation, refactors — all of it. `develop` is
merged into `main` when a release is cut, and `main` is where the tag goes.

The two exceptions target `main` directly:

- **A hotfix** against something already released, from `hotfix/*`. Merge it into `main`,
  cut the tag, then merge `main` back into `develop` so the fix is not lost at the next
  release.
- **A release branch**, `release/*`, if a release needs stabilising while `develop` moves
  on. Neither branch is protected, and neither is created until something needs one.

Name topic branches for what they do — `feat/…`, `fix/…`, `docs/…`, `ci/…`. Nothing
enforces it; it is only so the branch list reads.

Both `main` and `develop` are protected: they take pull requests, not pushes, and a pull
request cannot be merged until CI is green on it. Force-pushing and deleting them are
off. Tags are not protected, because a release is a tag and blocking it would block
releasing.

The one push that is not a pull request is the release itself — a fast-forward of `main`
onto `develop`, which no merge button can perform. "Cutting a release" below says why it
has to be that and not a PR, and `.github/workflows/gitflow.yml` fails on `main` when it
was not.

## What runs, and when

[`.github/workflows/ci.yml`](.github/workflows/ci.yml) triggers on a pull request against
any base, and on a push to `main` or `develop`. Its comments carry the full argument; the
part you need is the consequence:

**Pushing a topic branch runs nothing.** Coverage starts when the pull request exists. If
you want the suite on work that is not ready for review, open the PR as a draft — a draft
PR triggers CI exactly like a ready one — or run the workflow by hand from the Actions
tab (`workflow_dispatch`). This is deliberate. Testing the branch push *and* the pull
request would run the same commit twice, and paying double for a 60-second Postgres suite
is how a maintainer learns to stop reading the result.

Two jobs run, in parallel, and both are required before a merge:

- **API** — `./gradlew test` in `apps/api`, on JDK 21 (Temurin). The suite starts a real
  `postgres:16-alpine` through Testcontainers, so it needs the runner's Docker daemon and
  nothing else: no database, no secret, no service container to configure. On failure the
  Gradle HTML report is uploaded as `api-test-reports` and kept for seven days — open it
  rather than scrolling the Spring Boot log.
- **Web** — `pnpm typecheck`, then `pnpm test`, then `pnpm lint` in `apps/web`, on Node
  22 with `--frozen-lockfile`. Node 22 is a floor, not a preference: Vite's rolldown
  bindings declare `engines: node ^20.19 || >=22.12`, and pnpm *skips* an optional
  dependency whose engines do not match instead of failing, so an older Node gives you a
  green install and a Vitest run that dies on a missing `@rolldown/binding-*`.

What does not run, so that its absence reads as a decision: Playwright (the suite in
`e2e/` needs a stack brought up around it, and a green e2e job that is green for the
wrong reason is worse than none) and `next build`. Both are argued at the foot of
`ci.yml`.

### Running the same thing locally

There is nothing CI does that you cannot do first, and doing it first is faster than a
round trip:

```bash
cd apps/api && ./gradlew test          # needs a Docker daemon for Testcontainers
cd apps/web && pnpm install --frozen-lockfile && pnpm typecheck && pnpm test && pnpm lint
```

`README.md` covers bringing the application itself up (`docker compose up`, the setup
wizard, `KANSO_AUTH_MODE=dev`).

### The end-to-end suite

Playwright is not in CI, which makes it yours to run when you change something it
covers. It has no `webServer` and assumes the stack is already up, so it is two commands
and not one — both from the repository root:

```bash
KANSO_AUTH_MODE=dev docker compose up -d --build --wait
pnpm install && pnpm exec playwright install chromium && pnpm test:e2e
```

`KANSO_AUTH_MODE=dev` is not optional here. The permission scenarios need two people
playable inside one test, and under `oidc` there is no automatable sign-in path.

If you override the ports, set `KANSO_WEB_URL` **and** `KANSO_API_URL` together. The
browser reads the first; every seed call and every assertion made over HTTP reads the
second. Setting only one drives your stack while seeding — and claiming — somebody
else's, and that is not hypothetical: a run pointed at a fresh web port left its teams,
its tickets and a `users` row on the maintainer's own instance, and reported the failure
as a team it could not create. `e2e/README.md` is the full version, including
`KANSO_WEB_ORIGIN`, which you also need whenever `WEB_PORT` is not the default.

### If you are contributing from a fork

Fork the repository, push your branch to your fork, open the pull request against
`develop`. Two things are worth knowing about how that runs here:

- The suite works from a fork. A `pull_request` run from a fork gets a read-only
  `GITHUB_TOKEN` and none of the repository's secrets, and neither job needs any:
  `postgres:16-alpine` is pulled anonymously and Testcontainers invents the credentials
  it hands Spring. You get the same verdict a branch in this repository gets.
- For a first-time contributor, GitHub holds the run until a maintainer approves it. So
  the checks may sit as "Expected — waiting for status to be reported" for a while. That
  is the approval, not a broken workflow.

## Commits and style

**Conventional Commits.** `feat:`, `fix:`, `docs:`, `ci:`, `refactor:`, `test:`,
`chore:`, with the scope in parentheses where it helps — `fix(docker):`. The subject says
what changed; the body says *why*, and the why is the half that is worth writing.

**The code is hand-formatted at 100 columns. Do not run a formatter.** There is no
Prettier configuration in this repository and running one anyway turns a five-line change
into a two-hundred-line diff that cannot be reviewed and cannot be undone. `pnpm lint`
checks what is actually enforced; nothing else is.

**Comments explain why.** The workflows in `.github/workflows/` are the house style taken
to its limit: they say what was considered and rejected, so that the next reader does not
re-litigate it or delete something load-bearing. Match that register in anything with a
non-obvious reason behind it, and write nothing where the code already says it.

**English in the code and in the repository**, including commit messages, comments and
this file. The product is bilingual — `site/i18n/` and `docs/architecture.fr.md` — and
that is a separate thing from the language the repository is worked in.

A comment that asserts something untrue is worse than no comment. If your change makes a
nearby comment false, fixing that comment is part of your change, not a follow-up.

## Where a document goes

One question decides it: **does this document describe something that changes?**

A document about a moving artefact lives in `docs/` and is reviewed in the pull request
that moves it. That is all three files there: `docs/self-hosting.md` describes the image,
the routing table and the variables; `docs/architecture.md` and its French twin describe
the shape the system has today. Every line of them can be falsified by the next commit,
so they sit in the diff, where a reviewer sees both halves at once. Otherwise the
staleness is paid by a stranger trying to install the product, who is the last person in
a position to notice it. A fourth file there needs the argument that it is this kind of
document.

A dated decision lives in the [wiki](https://github.com/Tykok/Kanso/wiki). A spec arguing
what a feature should do, and the plan it was built from, say nothing about the present.
The code moving on does not make them wrong — it makes them the record of what was
decided before it moved. `Follow-ups`, the debts found in review and left deliberately,
reads the same way, and it is worth checking before you propose a fix: yours may already
be on it, agreed and unbuilt.

**Comments in the code cite wiki pages by title, and the title is the only handle they
have.** Nothing in CI builds the wiki or resolves a link into it, so renaming a page
breaks those citations silently. Grep before you rename one.

## The invariants

`CLAUDE.md` at the root lists them with the reasoning behind each: Postgres is the source
of truth and Notion an asynchronous mirror; Flyway owns the schema and a merged migration
is immutable; `docker/Caddyfile` is a test fixture as much as configuration; `KANSO_TLS`
decides who owns `X-Forwarded-For`; the JVM binds to loopback; `NEXT_PUBLIC_API_URL`
stays absent; the two compose files are not variants of each other; `/data` must outlive
the container; `KANSO_AUTH_MODE=dev` never reaches a deployed instance.

It is addressed to an agent and it is accurate for a person. Read it before a first
non-trivial change — it is shorter than this file, and every entry on it is there because
that thing has already gone wrong once.

## Cutting a release

Releases are tags on `main`, and the tag is the whole ceremony —
[`.github/workflows/release.yml`](.github/workflows/release.yml) does the rest.

1. Fast-forward `main` onto `develop`, once CI is green on `develop`:

   ```bash
   git push origin develop:main
   ```

   **Not a pull request.** GitHub's merge button has three modes and all three write a
   commit that `develop` does not have: merge creates one, squash flattens the branch
   into one, rebase replays the branch as new ones. Any of them leaves `main` holding
   something `develop` never sees, and the next release PR then proposes the whole
   history again — a conflict per commit, against content already present. That is not
   hypothetical; #13, #14 and #15 each did it in turn, each reported success, and none
   of them changed a byte. `.github/workflows/gitflow.yml` fails on `main` when it
   happens.

   A fast-forward moves `main` to the exact commit `develop` is on, so the two are
   identical and `develop` only ever runs ahead. It is a direct push: branch protection
   takes pull requests, and this is the one thing that cannot be one.

2. Tag that commit and push the tag:

   ```bash
   git checkout main && git pull
   git tag v1.2.3 && git push origin v1.2.3
   ```

   The tag must be `vMAJOR.MINOR.PATCH`. `release.yml` checks the shape and fails loudly
   rather than publishing `ghcr.io/tykok/kanso:` with an empty tag.
3. `release.yml` builds the jar and the Next bundle once on amd64, stamps `KANSO_COMMIT`
   with the tagged SHA so `/api/me` and the footer report what is actually running,
   builds the image, **smoke-tests it before logging in to any registry**, and only then
   pushes `linux/amd64` and `linux/arm64` to `ghcr.io/tykok/kanso`.

The image is tagged `v1.2.3`, `1.2` and `latest`. A prerelease — `v1.2.3-rc1` — publishes
under its own full tag only and moves neither `1.2` nor `latest`, because
`docs/self-hosting.md` tells people to pull `latest` and a release candidate arriving
there would be a lie told to every `docker compose pull` in the world.

The tag does not re-run `ci.yml`. It does not need to: the tagged commit is the commit
`develop` was on, and it was tested twice already — on its push to `develop`, and again
on the fast-forward push to `main`. That is also why `release.yml` builds with
`-x test`.

Branch protection does not touch tags, so pushing one needs nothing special. Re-pushing a
tag is how you re-run a release.

## Reporting things

Issues are open, and there are three forms rather than a blank box: a bug, a self-hosting
problem, and a feature or change. The forms ask for the version and how the instance is
deployed because that is the answer that always goes missing, and a report without it
costs a round trip before anybody can try to reproduce it. Self-hosting has a form of its
own because an install that never came up has to be asked different questions than an
application that behaved wrong.

**Security-sensitive reports belong in a [private advisory][advisory], never a public
issue.** `SECURITY.md` says what is in scope, and lists the things that are documented
limitations rather than vulnerabilities — dev mode, a token with no scope narrower than a
person, a published 8080 — so that a reporter reads the answer instead of writing the
report.

[advisory]: https://github.com/Tykok/Kanso/security/advisories/new
