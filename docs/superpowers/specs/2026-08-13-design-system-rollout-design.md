# Putting the drawn design on the running product

A design bundle exported from Claude Design lands 28 screens and a token file at the root
of the repository. Every screen in it is a redrawing of a surface Kanso already has, in a
palette, a typeface and a rhythm Kanso does not have yet. This branch makes the product
look like the drawings.

It arrives in the middle of another branch's work.
`2026-08-13-design-system-shadcn-design.md` moved Kanso onto Tailwind v4 and shadcn/ui in
ten steps; steps 1 to 3 are merged (the token layer, `cn()`, the layer order, and
`Button`/`Input`/`Badge`/`Card`/`Dialog`/`DropdownMenu` on Radix), and steps 4 to 10 —
every actual screen — are not started. `tickets.tsx`, `sidebar.tsx`, `overlays.tsx`,
`settings/`, `setup/` and `timeline/` carry no utility class at all: they are still
dressed by 2664 lines of hand-written CSS that step 10 exists to delete.

So this is not a restyle laid on top of that migration. It **is** that migration, finished
in the new visual language. Every screen changes hands once: it moves to utilities and to
the new design in the same edit, and the stylesheet section that dressed it is deleted in
the same commit. Doing it in two passes would mean writing CSS in lot 2 that lot 3 deletes.

Two decisions in the earlier spec are overturned here, deliberately:

- **Accents and densities do not go dormant.** That spec retired six accents and two
  densities to reach plain shadcn. The new design system is built on both — the seal takes
  the colour of the chosen accent, and compact is a 27px row against 36px. They stay live.
  Nothing on the API side was ever removed, so there is nothing to rewire.
- **The typed wordmark comes back, as a seal.** `82e8ca0` replaced it with
  `kanso-logo.png`. None of the 28 screens uses that image; all of them use `.k-seal`,
  which is 簡 and 素 stacked in a ruled square, set in real type. The PNG stays as the
  favicon, where an opaque tile at 16px is still the right answer.

## What ships

- One token layer, in shadcn vocabulary, carrying the design system's values.
- Public Sans and a two-glyph subset of Noto Sans JP, self-hosted through `next/font`.
- `Seal`, replacing `BrandLogo` at every call site but the favicon.
- Shell, list, floating surfaces, settings, onboarding, sign-in and timeline redrawn to
  the mockups and moved onto utilities.
- Dark theme, compact density and the sub-720px layout, verified across all of the above.
- `globals.css`, `settings.css`, `setup.css` and `timeline.css` gone.

## What does not

The bundle contains screens for areas the product does not have. They are drawings of
features, not of surfaces, and each needs its own brainstorm:

kanban board, project page, global search, documents and blocks, cycle with projection,
keyboard triage, saved views and bulk edit, workload per person, Notion import step 2,
the shortcut sheet, the trash, the public roadmap, the contributor screen, the marketing
page, the deck.

`kanso-tokens.css` from the bundle does not enter the repository either. It is an export
for contributors that would duplicate `styles/tokens.css` — two files to keep in
agreement, which is the exact debt this branch settles. If contributors need it later, it
gets generated from `tokens.css`, not maintained beside it.

`_ds/modernist/` and `_ds/nocturne/` in the bundle are referenced by no prototype. Dead
weight; ignored.

## The token layer

`styles/tokens.css` is the single source. Every value below is read from the bundle;
nothing is invented except where noted.

### Roles that already existed

| shadcn | Light | Dark |
| --- | --- | --- |
| `--background` | `oklch(0.988 0.002 262)` | `oklch(0.185 0.008 262)` |
| `--card`, `--popover` | `#ffffff` | `oklch(0.228 0.010 262)` |
| `--accent` (hover fill) | `oklch(0.965 0.004 262)` | `oklch(0.272 0.012 262)` |
| `--foreground` | `oklch(0.22 0.012 262)` | `oklch(0.935 0.006 262)` |
| `--muted-foreground` | `oklch(0.52 0.011 262)` | `oklch(0.715 0.011 262)` |
| `--border`, `--input` | `oklch(0.925 0.005 262)` | `oklch(0.315 0.012 262)` |
| `--radius` | `6px` — the row radius; panels take `--radius-panel: 10px` | same |

The neutrals stop being pure grey. `tokens.css:20` argues that a tinted grey "reads as a
colour decision at large areas"; the design system tints every neutral toward hue 262 at
chroma 0.002–0.012, which is an order of magnitude below where that argument bites and is
what makes the ground sit under the accent rather than beside it. The comment gets
rewritten rather than left contradicting the file it heads.

### Roles shadcn does not have

| Token | Light | Dark | What it is |
| --- | --- | --- | --- |
| `--faint` | `oklch(0.68 0.010 262)` | `oklch(0.565 0.011 262)` | third ink level: group labels, ids, meta |
| `--rule` | `oklch(0.86 0.006 262)` | `oklch(0.315 0.012 262)` | a stated divider, where `--border` is a contour |
| `--status-backlog` | `oklch(0.68 0.010 262)` | `oklch(0.565 0.011 262)` | |
| `--status-todo` | `oklch(0.52 0.011 262)` | `oklch(0.715 0.011 262)` | |
| `--status-progress` | `oklch(0.62 0.13 78)` | `oklch(0.76 0.12 78)` | |
| `--status-review` | `oklch(0.58 0.12 232)` | `oklch(0.72 0.11 232)` | |
| `--status-done` | `oklch(0.56 0.13 158)` | `oklch(0.70 0.12 158)` | |
| `--urgent` | `oklch(0.55 0.19 22)` | `oklch(0.68 0.17 22)` | priority, not error — see below |
| `--row-h` | `36px` | | `27px` under compact |
| `--row-pad-x` | `12px` | | `10px` under compact |
| `--row-gap` | `2px` | | `1px` under compact |
| `--group-gap` | `18px` | | `11px` under compact |
| `--panel-radius` | `10px` | | panel corner; `--radius` is the row corner, `6px` → `5px` under compact |
| `--touch-min` | `44px` | | floor under `(pointer: coarse)` |

`--destructive`, `--success` and `--warning` survive untouched. The design system does not
cover them, and `tokens.css:53` records why amber is not red: a dependency that is broken
but repairable is not an error. `--urgent` sits near `--destructive` in hue and stays a
separate token for the same reason — an urgent ticket is not a failed one.

The five status hues share a lightness plane (0.52–0.62 light, 0.70–0.76 dark) so no
status outweighs another at a glance. That is the property to protect if a value is ever
tuned: move lightness for all five or for none.

### Accents

Six, one per hue, at a fixed lightness. The names match the six already in the database.

| `data-accent` | Light `--primary` | Dark `--primary` |
| --- | --- | --- |
| `indigo` | `oklch(0.52 0.14 262)` | `oklch(0.70 0.13 262)` |
| `violet` | `oklch(0.52 0.15 302)` | `oklch(0.70 0.14 302)` |
| `rose` | `oklch(0.52 0.15 12)` | `oklch(0.70 0.14 12)` |
| `blue` | `oklch(0.52 0.14 232)` | `oklch(0.70 0.13 232)` |
| `green` | `oklch(0.52 0.13 158)` | `oklch(0.70 0.12 158)` |
| `amber` | `oklch(0.52 0.13 78)` | `oklch(0.70 0.12 78)` |

Only indigo is drawn in the bundle, in both schemes. The other five light values are read
from the accent picker in `Kanso - Écrans 4.dc.html`; their dark values follow the one
transform the bundle demonstrates on indigo — lightness to 0.70, chroma down 0.01, hue
unchanged. Derived, therefore checked rather than trusted: each of the twelve is read on
the `/design-system` page against `--primary-foreground`, and the pair ships only at 4.5:1
or better. A hue that will not clear it gets its lightness moved, not its label.

Alongside each accent:

- `--primary-foreground`: `#ffffff` light, `oklch(0.185 0.008 262)` dark.
- `--accent-soft`: `oklch(0.965 0.021 <h>)` light, `oklch(0.30 0.048 <h>)` dark. It sits
  behind body text, so it is a tint of the ground, never of the accent.
- `--accent-ink`: `oklch(0.42 0.13 <h>)` light, `oklch(0.80 0.11 <h>)` dark. Text on
  `--accent-soft`.

### Reaching the utilities

`@theme inline` grows to expose the new roles, so screens can be written in utilities
rather than in `style` attributes:

```css
--color-faint: var(--faint);
--color-rule: var(--rule);
--color-status-backlog: var(--status-backlog);   /* …todo, progress, review, done */
--color-urgent: var(--urgent);
--height-row: var(--row-h);        /* h-row  — follows density */
--spacing-row-x: var(--row-pad-x); /* px-row-x */
--spacing-group: var(--group-gap); /* mt-group */
--radius-panel: var(--panel-radius);
```

The raw token and the theme key never share a name: `--radius-*` and `--shadow-*` are
Tailwind namespaces, so a raw token called `--shadow-panel` mapped to a theme key called
`--shadow-panel` resolves to itself. The raw elevation tokens are therefore `--elev-flat`,
`--elev-panel`, `--elev-float`, and `@theme inline` maps them to `--shadow-flat`,
`--shadow-panel`, `--shadow-float`.

Because these resolve at use, a `h-row` written once answers both densities and needs no
variant. That is the whole reason density is a token and not a class.

### Elevation

Two layers of shadow in any one value, never three: `--elev-flat` for a resting surface,
`--elev-panel` for a panel, `--elev-float` for something lifted over the page. Values from
the bundle, reachable as `shadow-flat`, `shadow-panel`, `shadow-float`.

## Type

Public Sans through `next/font/google`, weights 400 and 500, latin subset. `next/font`
downloads at build and serves from the application's own origin: no request leaves the
browser for Google at runtime, which matters for a product that ships as a container and
claims no telemetry. This overturns the note at `layout.tsx:13` — "No web fonts: the
system stack renders immediately" — and that note gets rewritten to say what is true: the
font is self-hosted, preloaded, and `size-adjust` keeps the first paint from shifting.

500 is the heaviest weight in the system. No bold anywhere.

The scale is fixed: 11px labels and ids, 12px secondary interface, 13px interface body,
15px panel titles, 21px ticket title, 30px page title. Tracking `-0.02em` on the two
largest, `0.1em` on uppercase labels.

The seal needs 簡 and 素 only. Rather than pull a CJK family, a two-glyph subset of Noto
Sans JP (~2KB) is generated at `public/fonts/` and declared with `unicode-range` covering
U+7C21 and U+7D20, with `'Hiragino Sans', 'Yu Gothic'` behind it. A minimal container
without CJK fonts installed is the deployment target, so tofu there is a real failure, not
a theoretical one.

## The seal

`components/seal.tsx` — a `<span className="seal">` sized by its caller, coloured by
`currentColor`, defaulting to `--primary`. The stacked 簡素 is a `::before` measured in
`cqw` against a size container, so one rule holds from 14px in the sidebar to 220px on a
cover. Under 20px wide, a container query drops it to 簡 alone: two stacked characters
stop being legible below that. Over 34px, `data-size="lg"` thickens the rule and the
relief by one step.

It replaces `BrandLogo` in the sidebar, `BrandMenu`, the sign-in screen, the setup wizard
and both splash screens. `BrandSplash` keeps its shape and its refusal to animate.
`public/kanso-logo.png` is deleted; `app/icon.png` stays exactly as it is.

## The screens

Each lot below owns its files outright. Fidelity to the mockups is the standard: where a
drawing and the current markup disagree about structure, the drawing wins.

**Lot 1 — foundations.** `styles/tokens.css`, the fonts, `Seal`, `lib/status.ts` rewired
to the five status tokens, `lib/theme.ts` left writing `data-accent` and `data-density`,
the focus ring (`2px solid var(--primary)`, offset 2), the 44px touch floor. Then one
mechanical move: `globals.css` splits into `styles/shell.css`, `styles/list.css` and
`styles/surfaces.css`, unchanged content, so that each later lot owns a whole file and
deletes it as it empties. `app/globals.css` keeps only the imports, the reset and the
layer order. `/design-system` grows to show every token, every status, both densities,
both schemes and all six accents — it is where contrast gets read, not computed.

**Lot 2 — shell and list.** `page.tsx`, `sidebar.tsx`, `tickets.tsx`, `pills.tsx`,
`brand-menu.tsx`, `new-menu.tsx`; deletes `styles/shell.css` and `styles/list.css`.
Reference: `Kanso - Écrans actuels.dc.html` and `Kanso - Écrans.dc.html`.
Structure comes from space, not from rules — no border between two rows. Group headers are
11px uppercase at `0.1em` in `--faint`. A selected row is `--accent-soft` with
`inset 2px 0 0 var(--primary)`. Priority reads as bars and never as a background colour.

**Lot 3 — floating surfaces.** `overlays.tsx`, `composer.tsx`, `menu.tsx`, `dialogs/*`,
`ui/*`; deletes `styles/surfaces.css`. Reference: `Kanso - Écrans.dc.html` (ticket panel
and page), `Kanso - Écrans 5.dc.html` (composer, menus, error states).
The primitives already on Radix keep their behaviour and their signatures; what changes is
what they are dressed in. The eight keyboard invariants from the previous spec still hold
and their tests must stay green.

**Lot 4 — dedicated screens.** `settings/*`, `setup/*`, `login.tsx`, `timeline/*`; deletes
`settings.css`, `setup.css`, `timeline.css`. Reference: `Kanso - Écrans 4.dc.html` and
`Kanso - Timeline.dc.html`.
The appearance section keeps its six swatches and its density control. The onboarding
preferences step keeps its full choice and its live preview.

**Lot 5 — modes.** Dark, compact and sub-720px read across every screen the four lots
above delivered, and fixed where they broke. Not a lot that writes new surfaces: a lot that
refuses to let the other four ship half a theme. References: `Kanso - Écrans 5.dc.html`
for dark and mobile, `Kanso - Écrans 6.dc.html` for compact.

## Order and parallelism

Lot 1 is serial and lands first; everything reads its tokens. Once it is committed and
`tokens.css` is frozen for the branch, lots 2, 3 and 4 run as three agents at once — their
file sets are disjoint by construction, which is the only reason the `globals.css` split
in lot 1 exists. Lot 5 is serial and last, because it can only judge what the other three
have already produced.

An agent that finds it needs a token that lot 1 did not define stops and asks rather than
adding one locally. A second definition of a colour is how a design system dies.

## Verification

`pnpm test`, `pnpm lint` and `pnpm typecheck` in `apps/web`, and `pnpm test:e2e` at the
repository root, are green at the end of every lot — and "green" means the output was
read, not that the command was started.

The restyle will break e2e selectors that reach for legacy class names. They move to
`data-testid`, which the previous spec already listed as step 10 — the work is the same
work, done as the class it selects disappears. A test whose assertion no longer describes
the design gets rewritten deliberately, with the reason in the commit; a test that fails
because the code is wrong gets a fixed code.

Contrast is read on `/design-system`, in both schemes, at every accent: `--primary` under
`--primary-foreground`, `--accent-ink` on `--accent-soft`, and each status hue on
`--background`. 4.5:1 is the floor and it is not negotiated down by lowering the standard.

## Risks

**The derived dark accents.** Ten of the twelve accent values are extrapolated from one
demonstrated transform. They are the most likely thing in this document to be wrong, which
is why they are read on a page rather than shipped on the strength of the arithmetic.

**Scope creep from the bundle.** Fifteen designed screens have no feature behind them. The
temptation during lot 2 will be to build the kanban because the drawing is right there.
They are listed in "What does not" so that answer is already written down.

**The one-pass bet.** Migrating and restyling in one edit means a lot that goes wrong goes
wrong in two dimensions at once. The mitigation is the lot boundary: each is a commit that
stands alone, and any single lot can be reverted without touching the others.
