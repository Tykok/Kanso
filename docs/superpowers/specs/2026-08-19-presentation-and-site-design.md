# Saying what Kanso is, and building the page that says it

Kanso presents itself by comparison. `README.md`'s third line reads *"a keyboard-first
task tracker, in the spirit of Linear, that keeps Notion as a durable, readable
mirror"*, and `layout.tsx`'s metadata says *"A keyboard-first task tracker, mirrored to
Notion"*. Two sentences, three mentions of other people's products, and no mention of
the thing that took 291 commits: cycles, a roadmap, a timeline with dependencies and
overlap warnings, workload per person, triage, saved views, an inbox.

A reader arriving at either sentence learns that Kanso resembles two tools they may
already pay for. They do not learn that it manages a project.

This spec does two things. It rewrites how the project describes itself, and it builds
the public page that description belongs on — a static site published to GitHub Pages,
in English with a French switch.

## The positioning

The sentence every other sentence derives from:

> Kanso runs a project end to end — teams, projects, tickets, cycles, a roadmap, a
> timeline with dependencies, workload — and keeps the documents that explain it
> alongside. Everyone who does not open it reads the same data in Notion.

What leaves: *"in the spirit of Linear"*, *"task tracker"*, and Notion as the subject of
the first sentence. What stays: *keyboard-first*. That is a property of Kanso, not a
comparison to anything.

### The four pillars, in this order

1. **A whole project, not a task list.** Cycles, roadmap, a timeline that knows about
   dependencies and warns about overlap, workload per person. This is what earns the
   words *project manager*, and it is checkable: `e2e/12-timeline.spec.ts`,
   `e2e/13-scoped-timeline.spec.ts`, `WorkloadController`.
2. **The document sits next to the work.** `notion_docs` are referenced by projects
   *and* tickets, many-to-many on both sides.
3. **At the keyboard.** `j`/`k`, `1`…`6`, `⌘K`. Three dispositions — list, board,
   timeline — drawn from one scoped query (`store/ui.ts:5-9`).
4. **Nobody else has to learn Kanso.** Connect Notion and Kanso feeds four databases;
   the people who only need to follow along build their own views there and never open
   Kanso. Arrive with an existing Notion workspace and the import adopts it once.

### What pillar 4 must not claim

The import exists (`NotionImportService`: `sources()` → `preview()` → `perform()`), and
it is tempting to sell it as a second way in — "two entry points, one source of truth".
That is false, and the code says so in three places:

- `docs/architecture.md`: *"Pages created in Notion are not adopted. […] Create tickets
  in Kanso."* The poller logs such pages and moves on.
- The import needs a human to answer which team a base becomes, and it **copies**: the
  imported ticket gets its own page in `Kanso · Tickets`, and the source page is never
  adopted and never written to.
- In steady state the poller applies scalar fields only — title, status, priority,
  dates, archived. Relations and assignees stay Kanso-authoritative.

So the honest formulation is **one door to move in, one source of truth to live in**.
That is also the better pitch: it answers "I already have six months of tickets in
Notion" without promising a bidirectional tool. A site that says *bidirectional* loses
the first visitor who creates a ticket in Notion and watches it be ignored — on exactly
the point where it asked for trust.

### The licence contradiction

`README.md` says MIT. `components/publik/copy.ts:16` says `AGPL-3.0`. Both are public
surfaces and there is no `LICENSE` file to arbitrate. **It is AGPL-3.0.** This spec adds
the full text at `LICENSE` and corrects the README. It is the one line here with
consequences outside the repository.

### Files the positioning touches

| File | What changes |
|---|---|
| `README.md` | the two headline lines become the sentence above; MIT → AGPL-3.0 |
| `apps/web/src/app/layout.tsx` | `metadata.description` |
| `apps/web/src/app/about/page.tsx` | the eyebrow `Work tracking · living document` |
| `LICENSE` | new: the AGPL-3.0 text |
| `docs/architecture.md` | **nothing.** That document is about the mirror decision; the mirror is its legitimate subject |

## The site

### Why it is not the in-app page exported

Three approaches were weighed.

Exporting `apps/web` statically fails on its own terms: `output: "export"` requires
every route to be exportable, and `/t/[id]`, `/p/[slug]` and `/settings` are not without
`generateStaticParams`. The app would be mutilated for the benefit of its shop window.

A second Next app importing the vitrine keeps one source for the markup, but the
repository is not a pnpm workspace — the root `package.json` says so itself — so it means
a second install and a second Tailwind v4 wiring to repair every time Next moves.

The site is therefore **standalone and framework-free**, and the choice is not only about
cost. The two surfaces have different jobs:

| | in-app `/about` | the Pages site |
|---|---|---|
| Reader | already inside an instance | has never run Kanso |
| Buttons | "Open the app", `⌘K` | `docker compose up`, GitHub |
| Evidence | miniatures drawn in CSS | captures of the running app |

Fusing them forces both into the weak union of the two. So the prose is not shared. What
is shared is what must genuinely not drift:

1. **`tokens.css`, copied verbatim at build time.** Its `:root` and `.dark` blocks are
   ordinary CSS and work alone; the Tailwind at-rules (`@custom-variant` line 12,
   `@theme inline` line 240) are ignored by browsers and cost a little weight. Copied
   whole and not carved: a partial copy is a copy that drifts. The site's own `style.css`
   is hand-written and reads those tokens — no Tailwind.
2. **Media captured from the running app**, addressed through a manifest (below).

### Layout

One page, seven bands:

| # | Band | Contents |
|---|---|---|
| 1 | Hero | the sentence, `docker compose up`, GitHub, the six statuses drawn from the tokens |
| 2 | **The walk-through** | five steps, one caption each — the heart of the page |
| 3 | The four pillars | one short block each |
| 4 | Notion | both directions, stated as above |
| 5 | Run it | `docker compose up`, the ports, the wizard, "Notion is optional" |
| 6 | Open source | AGPL-3.0, the repository, the review promise from `copy.ts:40` |
| 7 | Closing | |

The walk-through's five steps, in order: create a project → create a ticket → the
timeline → the board → the list filtered to the assignee. The last three are the same
query drawn three ways, which is itself the argument.

### English, with a French switch

The application is English — `layout.tsx` declares `lang="en"` and `copy.ts:10-13`
already recorded why a French shop window on an English product is the first
inconsistency a visitor sees. The site keeps English as the default and adds French.

Three ways to do that; two are traps. Two hand-written HTML files reintroduce exactly
the divergence this design avoids. One HTML file with JavaScript swapping the text makes
the French invisible to crawlers, makes `lang` lie until JS runs, and flashes English at
the reader first.

So: **one template, two dictionaries, two output files**, assembled by `site/build.mjs`
— roughly forty lines, no dependencies. It is not a framework, it is a loop over two
objects. A key present in the template and missing from a dictionary **fails the build**
rather than leaving an English paragraph in the French page.

`<html lang>` follows the page (`en` / `fr`) and each page names the other in
`hreflang`, so the switch exists for machines and not only for humans.

The switch itself is a pair of links in the page header (`EN · FR`), not a dropdown:
two languages do not need a widget, and a link is what a crawler follows.

**Dark mode.** `tokens.css` selects its dark palette with a `.dark` class rather than a
media query, because the app drives it from a stored preference. The site has no
preference to store and no toggle: a three-line inline script in `<head>` sets
`class="dark"` on `<html>` from `prefers-color-scheme`, before first paint. Nothing else
about the theme is site-specific.

**The captures stay in English.** The app has no i18n. The French page therefore carries
French captions over English screens. That is visible and honest; the alternative is
translating the application, which is not this work.

### Files

```
site/
  build.mjs           # template + dictionaries + media.json → dist/
  template.html
  i18n/en.json        # default
  i18n/fr.json
  style.css           # hand-written, reads the tokens
  media.json          # what the walk-through shows, and where it lives
dist/                 # gitignored; what Pages publishes
  index.html          # en
  fr/index.html
  tokens.css          # copied from apps/web/src/styles/tokens.css
  style.css
```

Two entries join `.gitignore`: `dist/` (a build output) and `site/media/` (the raw PNG
captures, for the reason the next section gives — `site/media/v1/`, the packed WebP the
site publishes, is the exception that section's note explains).

## Media

The five captures in v1 were **placeholders**; they are now real, taken from the running
app by the test below. Video clips still come later, from the author. So the media layer
has to be replaceable without touching the template:

```json
// site/media.json
{
  "base": "https://tykok.github.io/Kanso/media/v1/",   // filled: the site serves its own
  "steps": [
    { "key": "step.project",  "type": "image", "src": "01-project.webp" },
    { "key": "step.timeline", "type": "video", "src": "03-timeline.mp4",
      "poster": "03-timeline.png" }
  ]
}
```

The build reads `type` and emits `<img loading="lazy">` or
`<video muted loop playsinline preload="none">` with its poster. Replacing a capture is
dropping a file on the host; turning an image into a video is changing one word.

### The media is hosted outside the repository

> **Superseded for the five images, September 2026.** The arithmetic below was done on
> PNG and it was right about PNG: `pnpm shots` writes 1.1 MB of them. `pnpm shots:pack`
> re-encodes the same five as WebP at 1920 wide and they come to **233 KB for the set** —
> below the 313 KB of design material this paragraph cites as the thing `.gitignore`
> refuses, so the comparison it rests on no longer holds. They are committed, in
> `site/media/v1/`, copied into `dist/` by `build.mjs`, and served from Pages at
> `https://tykok.github.io/Kanso/media/v1/`. What that buys is not the bytes: it is that
> the page and its pictures deploy together and cannot drift, and that no live page
> depends on a host somebody has to remember to keep paying for.
>
> **The clips are not superseded.** 5–30 MB per revision is still 5–30 MB per revision,
> and the section below is the standing decision the day one arrives.

Not committed — at a URL the author hosts. `.gitignore` already made this call for 313 KB
of design material, on the grounds that *"every clone of an AGPL repository would carry
them forever"*. Five `@2x` PNGs are 1–2 MB; video clips are 5–30 MB **per revision**, and
a clip retouched three times is three clips in the history, permanently. Committing them
would walk in through the front door that file locked, at twenty times the scale.

Three consequences the host must satisfy — all three still hold, and being the host
ourselves satisfies them rather than excusing them:

- **HTTPS.** An `http://` image on an HTTPS Pages document is blocked, not degraded.
- **A versioned path** (`…/v1/`). Replace a file at a stable URL and caches keep serving
  the old one; the reader sees the previous screenshot with the new caption.
- **Publicly readable and stable.** No signed URLs — they expire, and the page has no
  way to notice.

The build performs **no network access**: a missing host must not be able to redden the
workflow. URL verification is a local opt-in, `node site/build.mjs --check`, which HEADs
every entry. Every media slot renders inside a fixed `aspect-ratio` box and every caption
reads on its own, so an unreachable host leaves a page that is thinner, not broken.

## The captures

They are a **test**, not a folder of images: each shot waits for its locators before it
fires, so a changed screen breaks the script instead of producing a tidy photograph of
the wrong thing.

`e2e/shots.spec.ts`, tagged `@shots` and excluded from the default run by `grepInvert` in
`playwright.config.ts`, run on demand. It reuses `e2e/support.ts` — `apiAs`, `userIdOf` —
so there is no second install and no duplicated seeding logic. Output goes to
`site/media/` (gitignored), and `pnpm shots:pack` re-encodes it into `site/media/v1/`,
which is committed and which `build.mjs` copies into `dist/`.

The seed, over the API: a team `Atlas` → a project → six tickets, two of them joined by a
dependency and three assigned to the owner → a saved view `Assigned to me`
(`POST /api/teams/{teamId}/views` with `filters: {assignee:[…]}`,
`OrganiseControllers.kt:178`).

Three constraints that are expensive to discover later:

- **A virgin database is required.** Ticket identifiers come from a counter on the team
  row, so `KAN-1` is `KAN-1` only the first time. The script **refuses to run** if
  `Atlas` already exists rather than producing `KAN-47`.
- **Literal names, not `unique()`.** `support.ts:20` timestamps its names — right for
  tests, wrong for images that would then change on every regeneration. This is why the
  virgin database is a precondition and not a preference.
- **Absolute dates**, or the timeline's bars move. The "today" marker moves regardless;
  accepted, since it only shows on regeneration.

Viewport `1440×900`, `deviceScaleFactor: 2`, light theme forced. No dark variant in v1.

## Deployment

`.github/workflows/pages.yml`, on push to `main` touching `site/**`: Node 22,
`node site/build.mjs`, `upload-pages-artifact`, `deploy-pages`. Nothing to install — the
build has no dependencies. The Pages source is GitHub Actions, not a branch.

This is the repository's **first workflow**. `docs/follow-ups.md` records that there is no
CI: Vitest and Playwright run when someone remembers. That stays true — this workflow
publishes, it does not test. Widening it is a separate decision, and the site does not
repair it. The first automation in the repository's life should be trivial and impossible
to redden by anything but itself, which is also why the build never touches the network
and never runs the stack.

## Testing

- `node site/build.mjs` on a dictionary missing a key must exit non-zero, naming the key
  and the language. This is the one behaviour the i18n design rests on.
- `node site/build.mjs --check` reports every unreachable media URL, and is never run by
  CI.
- `pnpm exec playwright test --grep @shots` against a virgin stack writes five files to
  `site/media/` and fails if a locator has moved.
- The default `pnpm test:e2e` must not run `@shots`. Verify by running it and confirming
  the file count is unchanged.
- The published page is read once in both languages at 375px and 1440px, in light and
  dark, with the media host unreachable.

## Out of scope

Translating the application. Dark-variant captures. A blog, a changelog page, or docs
hosting. Running the test suites in CI. Any second Notion-like connector — the site says
Notion, singular, because that is what exists.
