# Trading a hand-written stylesheet for a component library

Kanso's interface is 2664 lines of CSS written by hand across four files, and the top of
`globals.css` argues for that choice: *"One stylesheet, no framework. […] a utility
framework would add a build step and a vocabulary without removing any of the decisions
below."* This branch overturns that decision.

The reason is not the one usually given. Design tokens were never missing — `globals.css`
already centralises colour, radius and spacing, already covers light and dark in one
declaration each through `light-dark()`, and already switches six accents and two
densities at runtime. What is missing is a **component library**: accessible primitives
that exist before we need them, so the next surface starts from something rather than
from nothing. That is what shadcn/ui and Radix bring, and it is the whole of the
justification.

The aesthetic target is Notion and Linear: neutral palette, soft contrast, moderate
radius, careful typography.

## What ships

- Tailwind CSS v4 and shadcn/ui, installed the way Next.js 16 documents.
- A token layer rewritten to the shadcn convention: one palette, class-based dark mode.
- Radix primitives behind Kanso's existing component signatures, so call sites survive.
- Every surface but one migrated to utilities: shell, list, dialogs, settings, onboarding.
- A `/design-system` page that shows the tokens and every installed component at a glance.
- The e2e suite stops keying on private CSS classes, closing `follow-ups.md:55`.

## What does not

**The keyboard.** Non-negotiable, and stated first because it is the branch's main risk.
The global shortcuts live in `page.tsx:217-263` and the `lib/actions.ts` registry, guarded
by `actions.test.ts`; they are not touched. The *local* keyboard of menus and overlays is
reimplemented on Radix, which does not behave identically — see "The eight keyboard
invariants".

**The timeline's geometry.** `timeline.css` is a layout engine, not styling. See "What
stays hand-written".

**The backend.** No Flyway migration, no change to the API contract, no Kotlin touched.
Accent and density become dormant rather than removed — see "Accent and density go
dormant".

**Icons, for Kanso's own triggers.** They are text glyphs (`⋯`, `▁▄█`, the status and
priority pills) and they stay glyphs: zero weight, and no fresh accessibility question
about what a trigger is called.

`lucide-react` is nevertheless installed, which revises this spec's first draft. shadcn's
Dialog and DropdownMenu import icons for their own chrome — a close cross, a check mark —
and stripping those imports would mean repeating the cleanup on every future `add`. So
lucide dresses the inside of generated components and nothing else. The line is between
Kanso's vocabulary and the library's internals, not between having icons and not.

## The token layer

The new tokens are **added beside the old ones, not substituted for them.** Replacing
`globals.css` outright would delete the rules that dress `.shell`, `.row`, `.sidebar` and
everything else in one commit, leaving the interface broken until the last surface
migrated — no step green, nothing reviewable. Instead the two token systems share the
document, and each surface migrates on its own.

Three legacy names collide with shadcn's and are namespaced to `--k-*` first: `--border`
(43 uses), `--radius` (20 uses), and `--accent` (28 uses). The last one is the dangerous
one — shadcn's `--accent` is a pale hover background, while Kanso's *is* the brand colour,
so leaving it alone would turn every accented element grey. The rename touches 102 usages
and 17 declarations across the four sheets and no `.tsx` at all, which makes it a provable
no-op and a commit of its own.

The new layer then lives in `src/styles/tokens.css`, imported by `globals.css`, so the
values people tune are not buried in 1269 lines of component rules. Its shape:

```css
@import "tailwindcss";

/* Dark mode by class — the shadcn standard. */
@custom-variant dark (&:is(.dark *));

/* ═══ ADJUST HERE ═══ the single place the interface is tuned from. */
:root {
  --radius: 0.5rem;                  /* 8px, moderate, Linear-like */
  --primary: oklch(0.53 0.16 277);   /* desaturated indigo, continuous with today's */
  --background: oklch(0.99 0 0);
  --foreground: oklch(0.15 0.01 280);
  --border: oklch(0.92 0.003 280);
  /* --success --warning --destructive */
}

.dark { /* the same names, re-evaluated */ }

/* The bridge to utilities: bg-background, rounded-lg, text-primary… */
@theme inline {
  --color-background: var(--background);
  --radius-lg: var(--radius);
}
```

Changing the primary colour is one line. Changing the radius is one line. That is the
point of the exercise, and it is why both sit at the top under a marked comment.

**`system` survives.** All three theme values stay in the API and in Settings. The
bootstrap script (`lib/theme.ts:78`) resolves `system` against `prefers-color-scheme` and
adds or removes `.dark` on `<html>`. It stays inline and blocking in `<head>`, so there is
still no flash of the wrong theme on first paint — the reason that script exists.

Class-based dark mode does cost one thing `light-dark()` gave for free: `color-scheme` no
longer follows the theme automatically, so native selects, date pickers and scrollbars
would keep the OS scheme while the page changes. It is restored explicitly, one declaration
per theme — but **not while both systems coexist.** The legacy palette resolves
`light-dark()` *off* `color-scheme`, which `[data-theme]` narrows (`globals.css:63-69`);
redeclaring it early would repaint every surface that has not migrated yet. So
`[data-theme]` stays in charge of `color-scheme` until nothing reads `light-dark()` any
more, and it moves onto `.dark` in the final cleanup. Both are set together in
`applyPreferences` from the one preference, so they cannot drift in the meantime.

**`success` and `warning` are additions.** shadcn ships `destructive` only. Kanso already
distinguishes amber from red on purpose — `globals.css:39` marks `--warn` as "a dependency
that is broken but still repairable, unlike `--urgent`'s red", and `.disposition-warning`
rests on that distinction. Both tokens are added, with matching `Badge` variants, or the
distinction is lost in translation.

## Accent and density go dormant

The user picked the plain shadcn standard: one accent, one density. That is a bigger ask
than it looks, because neither is a CSS concern:

- `V5__setup_and_local_auth.sql:87-97` — `NOT NULL` columns with `CHECK` constraints over
  the six accents and two densities
- `db/Tables.kt:72-73`, `domain/Model.kt:165-166` (`Accent`, `Density` enums)
- `PreferencesController`, `PreferencesService`, `PreferencesRepository`, `PreferencesTest`
- and on the web side, a dedicated onboarding step with a live preview

Taken literally, the simplification is a full-stack teardown. It is scoped to the visual
layer instead:

- `applyPreferences` (`lib/theme.ts:17`) stops writing `data-accent` and `data-density`.
- The six swatches leave `settings/appearance-section.tsx`.
- The onboarding step is **reduced to the theme choice**, not deleted. Deleting it would
  change the step count the progress rail derives (`setup.css:87`) and force a rewrite of
  the wizard for no gain.
- Columns, enums and controller are untouched, so the fields stay dormant and the decision
  stays reversible — either rewired or removed cleanly, later, on its own branch.

## Installation

Everything runs from `apps/web`, which carries its own lockfile and `pnpm-workspace.yaml`;
the repository root is the Playwright harness and explicitly not a workspace root. `pnpm
dlx`, never `npx`.

```
pnpm add -D tailwindcss @tailwindcss/postcss
pnpm add class-variance-authority clsx tailwind-merge lucide-react
# postcss.config.mjs, src/lib/utils.ts and components.json, all written by hand
pnpm dlx shadcn@latest add button card input dialog dropdown-menu badge
```

**`shadcn init` is never run**, which is a correction to this spec's first draft rather than a preference. `shadcn@latest` is CLI 4.17, where `init` has become `init|create`: a scaffolder that prompts for one of eight presets even under `-y`, whose `--defaults` resolve to Base UI rather than Radix, and which wants to write `globals.css`. A probe against a throwaway project hung for seven minutes without producing a file.

`add` reads `components.json` and does not care what produced it, and the two files `init` would have written — `components.json` and a four-line `cn()` — are small and fully known. So they are hand-written and the scaffolder is skipped, and `add --dry-run` and `add --view` verify the schema resolves before anything depends on it.

Two corrections this paragraph needed once the work was done. A `"base": "radix"` field was planned and **does not exist** in CLI 4.17's schema — it fails validation, and `style: "new-york"` alone already resolves components to Radix. And hand-authoring `init`'s output accounted for two of its three products: the third is the base rule that colours borders, whose absence left every `border` utility falling back to `currentColor` until it was added to `tokens.css`.

Verified against this version's own documentation rather than from memory
(`node_modules/next/dist/docs/01-app/01-getting-started/11-css.md`): Tailwind v4 needs no
`tailwind.config.js`, being configured in CSS through `@theme`. Turbopack resolves PostCSS
from the Next project root first, so `apps/web/postcss.config.mjs` is correctly placed and
`turbopackLocalPostcssConfig` is not needed.

`components.json`: `tsx: true`, `rsc: true`, `baseColor: neutral`, `cssVariables: true`,
css at `src/app/globals.css`, aliases `@/components` and `@/lib/utils` — the `@/*` alias
already exists (`tsconfig.json:38`).

New runtime dependencies, as shipped: `class-variance-authority`, `clsx`, `tailwind-merge`,
`lucide-react`, and the **unified `radix-ui` package** — not the per-primitive
`@radix-ui/react-dialog` and `@radix-ui/react-dropdown-menu` this paragraph first predicted,
which is what CLI 4.17 generates against. `tw-animate-css` was predicted and **never
installed**: the generated Dialog and DropdownMenu reference `animate-in`, `zoom-in-95` and
their kin, which therefore compile to nothing. Confirmed by screenshot that both overlays
appear and are legible; only the transition is absent. Reversible with one dependency.

**`init` overwrites `globals.css`, and that file is not disposable.** 115 of its lines are
comments recording decisions that are not re-derivable from the CSS: why
`role="menu"` sits on the list and not the popover, why `--accent-soft` is a tint of the
background rather than of the accent, why `.main` carries `min-height: 0`. The current
sheet is committed before `init` runs, and the reasoning that still applies is carried
into the new files. A migration that forgets why is a migration that reintroduces what the
comments prevented.

## Components

The six requested do not cover the existing surfaces. They are installed first; the rest
arrive as each surface migrates: `select label textarea command toggle-group tabs checkbox
radio-group separator`.

| Today | Becomes | Note |
|---|---|---|
| `.button` (border + surface) | `Button variant="outline"` | `outline`, not `default` |
| `.button-primary` | `Button` (default) | |
| `.button-danger` | `Button variant="destructive"` | |
| `menu.tsx` | `DropdownMenu`, behind an adapter | see below |
| `Backdrop`, `DetailPanel`, `HelpOverlay` | `Dialog` | |
| `CommandPalette` | `Command` (cmdk) | a seventh component |
| `dialogs/*`, `field.tsx` | `Dialog` + `Input` + `Label` + `Textarea` + `Select` | |
| `disposition-dialog` choices | `RadioGroup` | drops the counter-style at `globals.css:1016` |
| `SyncBadge` | `Badge` + success/warning/destructive | |
| `.segmented` | `ToggleGroup` | |
| `settings-nav` (`aria-current`) | `Tabs` | |
| `.shell`, `.sidebar`, `.topbar`, `.row`, `.statusbar` | utilities, no component | |

**`StatusPill` does not become a `Badge`.** Its dot encodes state through its fill: a 50%
gradient for `in_progress`, solid for `done` and `canceled`, hollow otherwise
(`globals.css:556-563`), all in `currentColor`. A `Badge` would drop that information. It
stays a small local component, retokenised. Same for `PriorityMark`, which is a glyph.

**The `Menu` adapter.** The signature stays — `Menu({ label, items, trigger, header,
footer })` — and only the internals move to Radix. All seven call sites are untouched: a row's
`⋯`, the sidebar's rows, `new-menu.tsx`, `brand-menu.tsx`, and the status and priority
pills. `menu.tsx:79` is preserved regardless — **an empty item list renders nothing** — as
defensive code worth keeping even though, per invariant 4 below, no live permission
combination currently reaches it. One line in the adapter; if a future caller's `when`
list ever *does* filter down to nothing, forgetting the line turns into a visible `⋯`
where the permissions model meant none.

The net win: with `DropdownMenuTrigger asChild` the pill genuinely *is* the trigger, and
the 35 lines of `display: contents` plus `position: absolute; inset: 0` at
`globals.css:598-632` — the subtlest passage in the sheet — are deleted.

## The eight keyboard invariants

Radix does not reproduce the current local keyboard for free. Each of these becomes a test
*before* the rewrite, not after.

1. **The popover must stop key propagation.** `menu.tsx:117-120` calls
   `stopPropagation()` because `page.tsx` listens on `window`. `DropdownMenuContent` does
   not shield it. Forgotten, every arrow inside a menu *also* moves the list cursor. Fix:
   `onKeyDown={(e) => e.stopPropagation()}` on the content.
2. **Tab.** `menu.tsx:136-155` refocuses the trigger and *then* lets the browser's own
   default action run, so focus lands after the row instead of at the top of the document.
   Reproduce through `onCloseAutoFocus`, and assert it.
3. **Selecting an entry.** `menu.tsx:174-182` refocuses the trigger *before* running the
   action, so a dialog opened from a menu entry has a focus-return target that still
   exists. On Radix, `onSelect` runs before the close; a known trap, handled explicitly.
   Verified in `e2e/14-menu-keyboard.spec.ts` against "Rename team" (opens `TeamDialog`,
   a `DialogFrame`), not the ticket row's own "Rename": that one opens `tickets.tsx`'s
   inline `TitleEditor`, which has no focus-restore wiring at all and really does drop
   focus to `<body>` on close today — a pre-existing, unrelated fact about the ticket row,
   not a case this invariant covers. Separately, and worth carrying into the Radix
   adapter: on a cold cache, opening any of the three `DialogFrame` dialogs (Team,
   Project, Disposition) through a menu goes through their "Loading…" placeholder first,
   which is *itself* a `DialogFrame` — its unmount races the real dialog's mount and the
   real dialog ends up capturing the doomed placeholder as its own focus-return target
   instead of the trigger, dropping focus to `<body>` on close. That is a defect in
   `dialogs/field.tsx` + the three dialogs' loading branches, not in `menu.tsx`, and this
   branch does not touch it; the e2e test primes the relevant query cache first so it
   observes menu.tsx's own behaviour rather than tripping over it.
4. **Empty list renders nothing.** See "The `Menu` adapter". Not asserted as "no `⋯` on a
   team row for a member" — `permissions.spec.ts:17-32` already rules against that
   premise, because `project.create`'s `when` is deliberately ungated on role. Checked
   across all six `Menu` call sites: every one includes at least one unconditional or
   role-blind action, so this branch is real but not reachable through any permission
   combination the live app can put someone in today. `e2e/14-menu-keyboard.spec.ts`
   asserts the true, load-bearing fact instead — the trigger shows, holding exactly the
   one entry a member is entitled to.
5. **Trigger click stops propagation.** `menu.tsx:96-98`, or the row underneath changes
   the scope.
6. **`role="menu"` belongs on the list, not the popover.** `menu.tsx:161`, with lines 30-36
   explaining why: a `menu` may only own `menuitem`, `group` and `separator`, so a header
   placed inside one may be *dropped* by assistive technology — and that header carries the
   identity block. Radix puts `role="menu"` on the content, which puts header and footer
   back inside it. **Settled in code, and it went against us**: `DropdownMenuLabel` renders
   inside the content's `role="menu"`, so it does not save us. The shipped `menu.tsx` takes
   the first of the three routes — `role="presentation"` on the content, a nested
   `role="menu"` around the entries carrying the `aria-label`. It cost nothing: both of
   Radix's collections find their items with `querySelectorAll` on the content, so the extra
   depth is invisible to them, and the `asChild` fallback was never needed. No regression, so
   nothing was recorded in `follow-ups.md`. `mouse.spec.ts` is what proves the header stayed
   outside the menu role.
7. **Escape must close once.** Radix `Dialog` handles it and so does `page.tsx`. Without
   `onEscapeKeyDown`, both fire.
8. **Text fields.** `overlays.tsx:69` and `:207` stop propagation so typing does not
   trigger global shortcuts. Radix `Dialog` does not stop the `window` listener, so these
   remain necessary.

## What stays hand-written

`timeline.css` is 744 lines, 28% of the CSS, and it is a geometry engine rather than
styling. `--tl-names`, `--tl-axis` and `--tl-chart` are set at runtime and everything else
derives from them through `calc()` and `flex: 0 0 var(--tl-chart)` (lines 146-154, 198-220,
302-304). In utilities that reads `flex-[0_0_var(--tl-chart)]` and
`h-[calc(var(--tl-axis)-1px)]` — strictly worse than the CSS it replaces, for no benefit.

The timeline keeps its own sheet, migrated **only for colour and radius** onto the new
tokens, geometry intact.

Tailwind v4 coexists with hand-written CSS, but **not without configuration** — this spec's
first draft claimed otherwise and `globals.css`'s layer statement exists because it is false.
Two things had to be arranged by hand: Kanso's element reset belongs in a layer between
Tailwind's `base` and its `utilities`, so it keeps beating preflight while a utility can still
reach a bare element; and a bare `border` utility needs a default colour, or it falls back to
`currentColor`. Both are in `tokens.css` and `globals.css` with the reasoning attached.

The honest arithmetic: roughly 1920 of 2664 lines move to utilities; the timeline's 744
stay as retokenised CSS. This branch does not claim a fully migrated stylesheet.

## Tests

**e2e.** Seventeen selectors key on private CSS classes — `.row`, `.row-id`, `.status`,
`.nav-item`, `.panel-header`, `.menu-header`, `.menu-footer`, `.tl-handle`,
`.disposition-count` among them. Utilities destroy every one. `follow-ups.md:55` already
records this as a defect: *"The e2e suite keys on private CSS classes."* So the migration
settles it rather than compounding it: `data-testid` on those anchors, e2e selectors
updated in the same commit.

**vitest.** `actions.test.ts`, `composer.test.ts`, `team-dialog.test.ts`, `row.test.ts`,
`timeline-geometry.test.ts` and `errors.test.ts` cover logic, not style, and are expected
to pass untouched — confirmed by running them, not by assumption.

**The keyboard invariants go in Playwright, not vitest.** `vitest.config.mts` is
`environment: "node"` by an argued choice, and overturning it would not help: jsdom does
not implement Tab's native focus-moving default action, which is exactly what invariants 2
and 3 turn on. A real browser is the only place those can be asserted. They are
characterisation tests — passing against today's code, then again after the rewrite —
following `e2e/keyboard.spec.ts:16-22`, which did the same thing when the shortcut registry
replaced the previous keyboard path.

## Order of work

Each step is green before the next begins.

1. Namespace the colliding legacy tokens to `--k-*`, then add Tailwind v4, a hand-written
   `components.json` and `cn()` — **not `shadcn init`**, see Installation — and the token
   layer alongside the existing sheet, including the base rule that colours borders.
1b. Move Kanso's element reset into `@layer kanso-reset`. Not in this spec's first draft, and
   found by the styleguide walking into it: an unlayered declaration beats a layered one
   whatever its specificity, so `button { background: none }` was silently defeating
   `.bg-primary` on every shadcn `Button`.
2. Primitives (`Button`, `Input`, `Badge`, `Card`) and the `/design-system` page **from
   this step onward**, so it grows with the branch instead of being written at the end.
3. Keyboard parity tests for the eight invariants, then the `Menu` adapter on Radix.
4. Overlays and dialogs (`overlays.tsx`, `dialogs/*`).
5. The command palette (`Command`).
6. Shell and list (`shell`, `sidebar`, `topbar`, `row`).
7. Settings (`Tabs`, `ToggleGroup`), and accent and density controls withdrawn.
8. Onboarding, its preferences step reduced to the theme.
9. The timeline retokenised.
10. Dead CSS deleted, e2e moved onto `data-testid`.
