# Design System Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the running product look like the 28 screens in the design bundle, finishing the shadcn migration in the same pass.

**Architecture:** One token layer in `styles/tokens.css` holds every value. Task 1–5 build the foundation and are serial. Tasks 6–8 own disjoint file sets and run as three agents at once. Task 9 is a serial pass over dark, compact and mobile. Every screen moves to utility classes and to the new design in the same edit, and the stylesheet section that dressed it is deleted in the same commit.

**Tech Stack:** Next.js 16, React, Tailwind CSS v4, shadcn/ui on Radix, `next/font/google`, vitest, Playwright.

**Spec:** `docs/superpowers/specs/2026-08-13-design-system-rollout-design.md`

**Design bundle:** unzip `Kanso Design System Linear-Notion-handoff.zip` at the repository root into a scratch directory. Prototypes are self-contained HTML with inline styles; read them with `python3` slicing rather than `grep -o` with wide context windows — the files are UTF-8 and ugrep refuses wide unicode context patterns.

## Global Constraints

- **Simple code, small reusable components.** A visual element that appears on two screens is a component in `components/ui/`, not two copies. Prefer a component with three props over a component with a `variant` matrix. If a file passes ~250 lines, it is doing too much.
- **No hard-coded colour, radius, shadow, font-size or row metric anywhere but `styles/tokens.css`.** Everything else reads `var(--…)` or a Tailwind utility that resolves to one.
- **Weights 400 and 500 only.** No bold in the interface.
- **Type scale is closed:** 11, 12, 13, 15, 21, 30 px. Nothing between.
- **Six accents and two densities stay live.** `applyPreferences` keeps writing `data-accent` and `data-density`.
- **No border between two list rows.** Space separates; a rule is a deliberate exception.
- **Priority reads as a glyph in its own colour, never as a background fill.**
- **Two shadow layers in any one value, never three.**
- **Focus is always `2px solid var(--primary)` at `2px` offset.** Never the browser ring.
- **Under `(pointer: coarse)`, no target below 44px.**
- **Verification is reading output.** `pnpm test`, `pnpm lint`, `pnpm typecheck` in `apps/web`; `pnpm test:e2e` at the root. A task is not done until they have been run and read.
- **A missing token is a question, not a local definition.** Tasks 6–9 do not add tokens; they ask.

---

### Task 1: The token layer

**Files:**
- Modify: `apps/web/src/styles/tokens.css` (whole file)
- Create: `apps/web/src/lib/tokens.test.ts`

**Interfaces:**
- Produces: every CSS custom property named below, and the Tailwind theme keys `text-faint`, `border-rule`, `bg-status-*`, `text-urgent`, `h-row`, `px-row-x`, `gap-row`, `mt-group`, `rounded-panel`, `shadow-flat`, `shadow-panel`, `shadow-float`.

- [ ] **Step 1: Write the failing test**

The guard that matters here is not "is the colour right" — that is read on the page — but "does every `var(--x)` in the source have a definition". That breaks silently and invisibly.

```ts
// apps/web/src/lib/tokens.test.ts
import { readFileSync, readdirSync, statSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

const SRC = join(import.meta.dirname, "..");

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((entry) => {
    const path = join(dir, entry);
    return statSync(path).isDirectory() ? walk(path) : [path];
  });
}

/** Tokens the browser defines, or that a component scopes to itself. */
const NOT_OURS = new Set(["--radix-popper-available-height", "--radix-popper-anchor-width"]);

describe("design tokens", () => {
  const tokens = readFileSync(join(SRC, "styles/tokens.css"), "utf8");
  const defined = new Set(Array.from(tokens.matchAll(/^\s*(--[\w-]+):/gm), (m) => m[1]));

  it("defines every token the interface reads", () => {
    const files = walk(SRC).filter((f) => /\.(tsx?|css)$/.test(f) && !f.endsWith("tokens.css"));
    const missing = new Set<string>();
    for (const file of files) {
      const source = readFileSync(file, "utf8");
      for (const [, name] of source.matchAll(/var\((--[\w-]+)/g)) {
        if (!defined.has(name) && !NOT_OURS.has(name) && !source.includes(`${name}:`)) {
          missing.add(name);
        }
      }
    }
    expect(Array.from(missing).sort()).toEqual([]);
  });

  it("keeps the five drawn statuses on one lightness plane", () => {
    const plane = Array.from(
      tokens.matchAll(/--status-(?:backlog|todo|progress|review|done): oklch\(([\d.]+)/g),
      (m) => Number(m[1]),
    );
    expect(plane).toHaveLength(5);
    expect(Math.max(...plane) - Math.min(...plane)).toBeLessThanOrEqual(0.16);
  });
});
```

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/web && pnpm test tokens`
Expected: FAIL — the current `tokens.css` defines none of `--faint`, `--rule`, `--status-*`, so both assertions break.

- [ ] **Step 3: Rewrite `styles/tokens.css`**

Keep `@custom-variant dark (&:is(.dark *))` and the `@layer base` border rule at the end of the file exactly as they are — the comment above that rule explains why it is layered and it is still true.

```css
:root {
  /* Ground and ink. Neutrals carry a trace of hue 262 at chroma 0.002–0.012:
     an order of magnitude below where a tint reads as a colour decision, and
     what puts the ground under the accent rather than beside it. */
  --background: oklch(0.988 0.002 262);
  --foreground: oklch(0.22 0.012 262);
  --muted-foreground: oklch(0.52 0.011 262);
  --faint: oklch(0.68 0.010 262);          /* third ink: labels, ids, meta */

  --card: #ffffff;
  --card-foreground: var(--foreground);
  --popover: #ffffff;
  --popover-foreground: var(--foreground);
  --secondary: oklch(0.965 0.004 262);
  --secondary-foreground: var(--foreground);
  --muted: oklch(0.965 0.004 262);

  /* shadcn's --accent is the hover fill, not the brand colour. */
  --accent: oklch(0.965 0.004 262);
  --accent-foreground: var(--foreground);

  --border: oklch(0.925 0.005 262);        /* a contour */
  --input: oklch(0.925 0.005 262);
  --rule: oklch(0.86 0.006 262);           /* a stated divider */

  /* Statuses: five drawn hues on one lightness plane, so none outweighs
     another at a glance. Move all five or none. Canceled sits above the
     plane on purpose — a canceled ticket should recede. */
  --status-backlog: oklch(0.68 0.010 262);
  --status-todo: oklch(0.52 0.011 262);
  --status-progress: oklch(0.62 0.13 78);
  --status-review: oklch(0.58 0.12 232);
  --status-done: oklch(0.56 0.13 158);
  --status-canceled: oklch(0.75 0.008 262);

  /* Priority. Not statuses: a separate axis, read as a glyph.
     --urgent is next to --destructive in hue and stays its own token:
     an urgent ticket is not a failed one. */
  --urgent: oklch(0.55 0.19 22);
  --priority-high: oklch(0.58 0.14 52);
  --priority-medium: oklch(0.58 0.12 232);
  --priority-low: oklch(0.68 0.010 262);
  --priority-none: oklch(0.75 0.008 262);

  /* Not in the design system, and load-bearing. globals.css records why amber
     is not red: a dependency broken but still repairable is not an error. */
  --destructive: oklch(0.577 0.245 27.325);
  --destructive-foreground: oklch(0.985 0 0);
  --success: oklch(0.50 0.13 150);
  --success-foreground: oklch(0.985 0 0);
  --warning: oklch(0.70 0.15 75);
  --warning-foreground: oklch(0.205 0 0);

  /* Density. Compact removes space, never legibility: the body stays 13px. */
  --radius: 6px;                            /* the row corner */
  --panel-radius: 10px;
  --row-h: 36px;
  --row-pad-x: 12px;
  --row-gap: 2px;
  --group-gap: 18px;
  --touch-min: 44px;

  /* Elevation. Raw tokens are --elev-*: --shadow-* is a Tailwind namespace,
     and a raw token mapped to a theme key of the same name resolves to itself. */
  --elev-flat: 0 1px 2px rgb(20 20 30 / 8%);
  --elev-panel: 0 1px 2px rgb(20 20 30 / 8%), 0 18px 44px rgb(20 20 30 / 10%);
  --elev-float: 0 1px 2px rgb(20 20 30 / 10%), 0 20px 44px rgb(20 20 30 / 22%);

  --font-sans: var(--font-public-sans), system-ui, -apple-system, sans-serif;
  --font-mono: ui-monospace, SFMono-Regular, "SF Mono", Menlo, monospace;
  --font-seal: var(--font-noto-sans-jp), "Hiragino Sans", "Yu Gothic", sans-serif;
}

[data-density="compact"] {
  --row-h: 27px;
  --row-pad-x: 10px;
  --row-gap: 1px;
  --group-gap: 11px;
  --radius: 5px;
}

.dark {
  --background: oklch(0.185 0.008 262);
  --foreground: oklch(0.935 0.006 262);
  --muted-foreground: oklch(0.715 0.011 262);
  --faint: oklch(0.565 0.011 262);

  --card: oklch(0.228 0.010 262);
  --card-foreground: var(--foreground);
  --popover: oklch(0.228 0.010 262);
  --popover-foreground: var(--foreground);
  --secondary: oklch(0.272 0.012 262);
  --secondary-foreground: var(--foreground);
  --muted: oklch(0.272 0.012 262);
  --accent: oklch(0.272 0.012 262);
  --accent-foreground: var(--foreground);

  --border: oklch(0.315 0.012 262);
  --input: oklch(0.315 0.012 262);
  --rule: oklch(0.315 0.012 262);

  --status-backlog: oklch(0.565 0.011 262);
  --status-todo: oklch(0.715 0.011 262);
  --status-progress: oklch(0.76 0.12 78);
  --status-review: oklch(0.72 0.11 232);
  --status-done: oklch(0.70 0.12 158);
  --status-canceled: oklch(0.50 0.009 262);

  --urgent: oklch(0.68 0.17 22);
  --priority-high: oklch(0.74 0.13 52);
  --priority-medium: oklch(0.72 0.11 232);
  --priority-low: oklch(0.565 0.011 262);
  --priority-none: oklch(0.50 0.009 262);

  --destructive: oklch(0.704 0.191 22.216);
  --destructive-foreground: oklch(0.145 0 0);
  --success: oklch(0.70 0.14 150);
  --success-foreground: oklch(0.145 0 0);
  --warning: oklch(0.78 0.14 75);
  --warning-foreground: oklch(0.145 0 0);

  --elev-flat: 0 1px 2px rgb(0 0 0 / 30%);
  --elev-panel: 0 1px 2px rgb(0 0 0 / 30%), 0 18px 44px rgb(10 10 16 / 30%);
  --elev-float: 0 1px 2px rgb(0 0 0 / 34%), 0 20px 44px rgb(8 8 14 / 40%);
}
```

Then the six accents. `--accent-soft` is a tint of the ground, not of the accent: it sits behind body text, and a saturated fill would either bury the text or vibrate against the page. Write all six for light and dark; only the shape is shown here, and the six hues are `indigo 262`, `violet 302`, `rose 12`, `blue 232`, `green 158`, `amber 78`.

```css
/* Light: --primary is oklch(0.52 C h); dark is oklch(0.70 C-0.01 h).
   Chroma per hue: 262→0.14, 302→0.15, 12→0.15, 232→0.14, 158→0.13, 78→0.13.
   Only indigo is drawn in the bundle in both schemes; the other five dark
   values follow the transform it demonstrates, so they are read on
   /design-system before they ship, not trusted. */
[data-accent="indigo"] {
  --primary: oklch(0.52 0.14 262);
  --accent-soft: oklch(0.965 0.021 262);
  --accent-ink: oklch(0.42 0.13 262);
}
.dark [data-accent="indigo"],
[data-accent="indigo"].dark {
  --primary: oklch(0.70 0.13 262);
  --accent-soft: oklch(0.30 0.048 262);
  --accent-ink: oklch(0.80 0.11 262);
}
/* …violet 302 (0.15/0.14), rose 12 (0.15/0.14), blue 232 (0.14/0.13),
   green 158 (0.13/0.12), amber 78 (0.13/0.12), each with
   --accent-soft oklch(0.965 0.021 h) / oklch(0.30 0.048 h)
   and --accent-ink oklch(0.42 0.13 h) / oklch(0.80 0.11 h). */

:root {
  --primary-foreground: #ffffff;
  --ring: var(--primary);
}
.dark {
  --primary-foreground: oklch(0.185 0.008 262);
}
```

`data-accent` and `.dark` both land on `<html>` (see `lib/theme.ts:18`), so `[data-accent="x"].dark` is the selector that fires in the running app; the descendant form is there for `/design-system`, which shows a palette the app is not in.

Extend `@theme inline` with the new roles:

```css
@theme inline {
  /* …the existing --color-* mappings stay… */
  --color-faint: var(--faint);
  --color-rule: var(--rule);
  --color-accent-soft: var(--accent-soft);
  --color-accent-ink: var(--accent-ink);
  --color-status-backlog: var(--status-backlog);
  --color-status-todo: var(--status-todo);
  --color-status-progress: var(--status-progress);
  --color-status-review: var(--status-review);
  --color-status-done: var(--status-done);
  --color-status-canceled: var(--status-canceled);
  --color-urgent: var(--urgent);
  --color-priority-high: var(--priority-high);
  --color-priority-medium: var(--priority-medium);
  --color-priority-low: var(--priority-low);
  --color-priority-none: var(--priority-none);

  --height-row: var(--row-h);
  --spacing-row-x: var(--row-pad-x);
  --spacing-row-gap: var(--row-gap);
  --spacing-group: var(--group-gap);
  --spacing-touch: var(--touch-min);

  --radius-sm: calc(var(--radius) - 2px);
  --radius-md: var(--radius);
  --radius-lg: var(--radius);
  --radius-panel: var(--panel-radius);

  --shadow-flat: var(--elev-flat);
  --shadow-panel: var(--elev-panel);
  --shadow-float: var(--elev-float);

  --text-11: 11px;
  --text-12: 12px;
  --text-13: 13px;
  --text-15: 15px;
  --text-21: 21px;
  --text-30: 30px;
}
```

Because these resolve at use, one `h-row` answers both densities and needs no variant. That is the whole reason density is a token rather than a class.

- [ ] **Step 4: Run the test and read the output**

Run: `cd apps/web && pnpm test tokens`
Expected: PASS. If the first assertion still lists names, they are tokens the old `globals.css` defined and this file does not — decide for each whether it moves here or dies with its stylesheet in tasks 6–8, and note the decision in the commit.

- [ ] **Step 5: Focus, the touch floor, and the base border rule**

In `src/app/globals.css`, replace whatever focus rule exists with the one the system mandates, and keep it where unlayered rules win:

```css
:where(button, a, input, select, textarea, [tabindex]):focus-visible {
  outline: 2px solid var(--primary);
  outline-offset: 2px;
}

@media (pointer: coarse) {
  :where(button, a, [role="button"], [role="menuitem"], [role="option"]) {
    min-height: var(--touch-min);
  }
}
```

- [ ] **Step 6: Run the full suite and read it**

Run: `cd apps/web && pnpm test && pnpm lint && pnpm typecheck`
Expected: all pass. The interface will look wrong at this point — `globals.css` still defines the old `--k-*` palette and still wins. That is expected and is undone in Task 5.

- [ ] **Step 7: Commit**

```bash
git add apps/web/src/styles/tokens.css apps/web/src/lib/tokens.test.ts apps/web/src/app/globals.css
git commit -m "feat(web): the design system's values become the token layer"
```

---

### Task 2: Public Sans and the two-glyph seal font

**Files:**
- Modify: `apps/web/src/app/layout.tsx`
- Create: `apps/web/public/fonts/noto-sans-jp-seal.woff2`
- Modify: `apps/web/src/app/globals.css` (the `@font-face` for the seal)

**Interfaces:**
- Produces: CSS variables `--font-public-sans` and `--font-noto-sans-jp` on `<html>`, which `tokens.css` reads as `--font-sans` and `--font-seal`.

- [ ] **Step 1: Load Public Sans**

`next/font/google` downloads at build and serves from the app's own origin — no request leaves the browser for Google at runtime, which is what makes this acceptable in a container that claims no telemetry.

```tsx
// apps/web/src/app/layout.tsx
import { Public_Sans } from "next/font/google";

const publicSans = Public_Sans({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-public-sans",
  display: "swap",
});
```

Put `publicSans.variable` on `<html className={…}>`. Rewrite the comment at `layout.tsx:11` — it currently argues for no web fonts, and that is no longer what the file does. Say what is true: the font is self-hosted and preloaded, the metric override keeps the first paint from shifting, and the design system is set in it.

- [ ] **Step 2: Subset Noto Sans JP to 簡 and 素**

The seal needs two glyphs. A minimal container without CJK fonts installed is a real deployment target, so relying on `Hiragino Sans` is a tofu risk, not a theoretical one.

```bash
cd apps/web
pip install fonttools brotli
curl -L -o /tmp/noto.otf "https://github.com/notofonts/noto-cjk/raw/main/Sans/OTF/Japanese/NotoSansJP-Medium.otf"
pyftsubset /tmp/noto.otf --text="簡素" --flavor=woff2 \
  --output-file=public/fonts/noto-sans-jp-seal.woff2
ls -l public/fonts/noto-sans-jp-seal.woff2
```

Expected: a file well under 5KB. If `pyftsubset` is unavailable, say so and stop rather than shipping a full CJK font — a 4MB download for two characters is not a fallback.

- [ ] **Step 3: Declare it**

In `globals.css`, unlayered:

```css
@font-face {
  font-family: "Noto Sans JP Seal";
  src: url("/fonts/noto-sans-jp-seal.woff2") format("woff2");
  font-weight: 500;
  font-display: swap;
  unicode-range: U+7C21, U+7D20;
}
```

Then point `--font-noto-sans-jp` at it in `layout.tsx` or set `--font-seal` in `tokens.css` to `"Noto Sans JP Seal", "Hiragino Sans", "Yu Gothic", sans-serif` — one of the two, not both.

- [ ] **Step 4: Verify in the running app**

Run: `cd apps/web && pnpm dev`, open the app, and confirm in devtools that the computed `font-family` on `body` is Public Sans and that no request goes to `fonts.googleapis.com` or `fonts.gstatic.com`.
Expected: both true. The second is the point of the exercise.

- [ ] **Step 5: Commit**

```bash
git add apps/web/src/app/layout.tsx apps/web/public/fonts apps/web/src/app/globals.css apps/web/src/styles/tokens.css
git commit -m "feat(web): Public Sans, self-hosted, and two Japanese glyphs"
```

---

### Task 3: The seal

**Files:**
- Create: `apps/web/src/components/ui/seal.tsx`
- Modify: `apps/web/src/components/brand-logo.tsx` (becomes `BrandSplash` only)
- Modify: `apps/web/src/components/brand-menu.tsx`, `sidebar.tsx`, `login.tsx`, `components/setup/frame.tsx`
- Delete: `apps/web/public/kanso-logo.png`
- Modify: `apps/web/src/app/globals.css` (delete `.brand-logo` and its `invert()`)

**Interfaces:**
- Produces: `<Seal size={number} title?={string} />` — a square that takes `--primary` from `currentColor`, usable from 14px to 220px.

- [ ] **Step 1: Write the component**

One rule holds every size because the glyph is measured in `cqw` against a size container. Under 20px the container query drops the second character: two stacked glyphs stop being legible below that.

```tsx
// apps/web/src/components/ui/seal.tsx
/**
 * The whole identity: 簡 and 素 stacked in a ruled square, in real type rather
 * than a traced path — so it stays sharp at every size and can be selected.
 * Colour comes from `currentColor`, which is why it follows the accent chosen
 * in settings without knowing that accents exist.
 */
export function Seal({ size, title }: { size: number; title?: string }) {
  return (
    <span
      className="seal"
      style={{ width: size, height: size }}
      data-size={size > 34 ? "lg" : undefined}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    />
  );
}
```

- [ ] **Step 2: Its one stylesheet rule**

In `globals.css`, unlayered, next to the other element-level rules:

```css
.seal {
  display: grid;
  place-items: center;
  container: seal / size;
  box-sizing: border-box;
  color: var(--primary);
  border: 1px solid currentColor;
  border-radius: 26%;
  overflow: hidden;
  background-image:
    linear-gradient(158deg, rgb(255 255 255 / 30%), rgb(255 255 255 / 0%) 48%),
    radial-gradient(120% 95% at 28% 6%, color-mix(in oklch, currentColor 16%, transparent), transparent 64%);
  box-shadow:
    inset 0 0.5px 0 rgb(255 255 255 / 45%),
    inset 0 -0.5px 0 color-mix(in oklch, currentColor 26%, transparent),
    0 1px 2px color-mix(in oklch, currentColor 22%, transparent);
}
.seal::before {
  content: "簡\A素";
  white-space: pre;
  text-align: center;
  font-family: var(--font-seal);
  font-weight: 500;
  font-size: 34cqw;
  line-height: 1.03;
}
@container seal (max-width: 20px) {
  .seal::before { content: "簡"; font-size: 66cqw; line-height: 1; }
}
.seal[data-size="lg"] {
  border-width: 2px;
  box-shadow:
    inset 0 1px 0 rgb(255 255 255 / 45%),
    inset 0 -1px 0 color-mix(in oklch, currentColor 26%, transparent),
    0 2px 4px color-mix(in oklch, currentColor 22%, transparent);
}
```

- [ ] **Step 3: Replace every call site**

`BrandLogo` disappears; `BrandSplash` stays, keeps its shape and its refusal to animate, and renders `<Seal size={120} title="Kanso 簡素" />` instead of the image. In `BrandMenu` the button already carries `aria-label`, so the seal there passes no `title` and stays `aria-hidden`.

Run: `cd apps/web && grep -rn "BrandLogo\|kanso-logo" src`
Expected: no results when the step is done. `src/app/icon.png` is untouched — a favicon at 16px against browser chrome we do not control still wants its opaque tile.

- [ ] **Step 4: Verify**

Run: `cd apps/web && pnpm test && pnpm lint && pnpm typecheck`, then `pnpm dev` and look at the sidebar at 14px, the sign-in screen and the splash. Switch accent in settings and confirm the seal follows.
Expected: all green; the seal reads at 14px as 簡 alone and at 64px as both glyphs.

- [ ] **Step 5: Commit**

```bash
git rm apps/web/public/kanso-logo.png
git add -A apps/web/src
git commit -m "feat(web): the wordmark comes back as a seal, in real type"
```

---

### Task 4: Status and priority, as data and as marks

**Files:**
- Modify: `apps/web/src/lib/status.ts`
- Create: `apps/web/src/components/ui/status-dot.tsx`
- Create: `apps/web/src/components/ui/priority-mark.tsx`
- Create: `apps/web/src/lib/status.test.ts`

**Interfaces:**
- Consumes: the `--status-*` and `--priority-*` tokens from Task 1.
- Produces: `STATUS_LABELS`, `STATUS_COLORS` (unchanged names), `PRIORITY_GLYPHS`, `PRIORITY_COLORS`, `<StatusDot status={TicketStatus} />`, `<PriorityMark priority={TicketPriority} />`. Tasks 6, 7 and 8 all render these two components and none of them redraws a dot.

- [ ] **Step 1: Write the failing test**

```ts
// apps/web/src/lib/status.test.ts
import { describe, expect, it } from "vitest";
import { PRIORITY_COLORS, PRIORITY_GLYPHS, STATUS_COLORS, STATUS_LABELS } from "./status";
import { TICKET_PRIORITIES, TICKET_STATUSES } from "./api";

describe("status and priority tables", () => {
  it("covers every status", () => {
    for (const status of TICKET_STATUSES) {
      expect(STATUS_LABELS[status]).toBeTruthy();
      expect(STATUS_COLORS[status]).toMatch(/^var\(--status-/);
    }
  });

  it("covers every priority with a glyph and a colour of its own", () => {
    for (const priority of TICKET_PRIORITIES) {
      expect(PRIORITY_GLYPHS[priority]).toBeTruthy();
      expect(PRIORITY_COLORS[priority]).toMatch(/^var\(--(urgent|priority-)/);
    }
    expect(new Set(Object.values(PRIORITY_COLORS)).size).toBe(TICKET_PRIORITIES.length);
  });
});
```

`lib/api.ts:8,17` exports `TICKET_STATUSES` (six, including `canceled`) and `TICKET_PRIORITIES` (`none`, `low`, `medium`, `high`, `urgent`); `ACCENTS` at line 214 is the six the token layer must match, in that spelling.

- [ ] **Step 2: Run it and watch it fail**

Run: `cd apps/web && pnpm test status`
Expected: FAIL — `PRIORITY_GLYPHS` and `PRIORITY_COLORS` do not exist.

- [ ] **Step 3: Fill the tables**

The glyphs stay exactly what the product already uses: they are read at the keyboard and already learned. Only their colours move.

```ts
export const PRIORITY_GLYPHS = {
  urgent: "!",
  high: "█",
  medium: "▄",
  low: "▁",
  none: "·",
} as const;

export const PRIORITY_COLORS = {
  urgent: "var(--urgent)",
  high: "var(--priority-high)",
  medium: "var(--priority-medium)",
  low: "var(--priority-low)",
  none: "var(--priority-none)",
} as const;
```

- [ ] **Step 4: The two marks**

A status is an 8px disc whose *fill* carries the meaning, not its colour alone — so it survives being printed, and being colour-blind.

```tsx
// apps/web/src/components/ui/status-dot.tsx
import type { TicketStatus } from "@/lib/api";
import { STATUS_COLORS } from "@/lib/status";

/** backlog and todo are rings, in progress is half, in review is three
 *  quarters, done and canceled are full. The fill is the meaning; the hue
 *  only makes it faster to find. */
const FILL: Record<TicketStatus, string | undefined> = {
  backlog: undefined,
  todo: undefined,
  in_progress: "linear-gradient(90deg, currentColor 50%, transparent 50%)",
  in_review: "conic-gradient(currentColor 0 75%, transparent 75% 100%)",
  done: "currentColor",
  canceled: "currentColor",
};

export function StatusDot({ status }: { status: TicketStatus }) {
  return (
    <span
      aria-hidden
      className="size-2 shrink-0 rounded-full border-[1.5px] border-current"
      style={{ color: STATUS_COLORS[status], background: FILL[status] }}
    />
  );
}
```

```tsx
// apps/web/src/components/ui/priority-mark.tsx
import type { TicketPriority } from "@/lib/api";
import { PRIORITY_COLORS, PRIORITY_GLYPHS } from "@/lib/status";

/** A glyph in its own colour, never a background fill: priority is a second
 *  axis and must not compete with the status it sits beside. */
export function PriorityMark({ priority }: { priority: TicketPriority }) {
  return (
    <span
      aria-hidden
      className="w-3.5 shrink-0 text-center text-11 font-medium"
      style={{ color: PRIORITY_COLORS[priority] }}
    >
      {PRIORITY_GLYPHS[priority]}
    </span>
  );
}
```

Both are presentational and `aria-hidden`: the label beside them is what a screen reader reads, and it is the caller's job to render it. Do not add a `title`.

- [ ] **Step 5: Run the tests and read them**

Run: `cd apps/web && pnpm test && pnpm lint && pnpm typecheck`
Expected: pass.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/lib/status.ts apps/web/src/lib/status.test.ts apps/web/src/components/ui/status-dot.tsx apps/web/src/components/ui/priority-mark.tsx
git commit -m "feat(web): one status dot and one priority mark, for every screen"
```

---

### Task 5: Split the stylesheet so three agents can work at once

**Files:**
- Create: `apps/web/src/styles/shell.css`, `apps/web/src/styles/list.css`, `apps/web/src/styles/surfaces.css`
- Modify: `apps/web/src/app/globals.css`
- Move: the settings block out of `globals.css` into `apps/web/src/app/settings/settings.css`

**Interfaces:**
- Produces: three stylesheets, each owned outright by one of Tasks 6, 7 and 8, each deleted by its owner when the screens it dresses no longer need it.

- [ ] **Step 1: Cut on the existing section markers**

`globals.css` already labels its sections. Cut exactly there, moving lines verbatim — no reformatting, no renaming, no "while I'm here". A diff that shows anything but moved lines has gone wrong.

| Lines (before the move) | Destination |
| --- | --- |
| `/* --- shell --- */` → just before `/* --- list --- */` | `styles/shell.css` |
| `/* --- list --- */` and `/* --- pills --- */` | `styles/list.css` |
| `/* --- overlays --- */`, `/* --- dialogs --- */`, `/* --- menu --- */`, `/* --- disposition --- */` | `styles/surfaces.css` |
| `/* --- settings --- */` | `app/settings/settings.css` |
| `/* --- misc --- */` | stays in `globals.css` |

The `@media (max-width: 720px)` block at the end covers several sections: split it, and put each rule in the file that owns its selector.

- [ ] **Step 2: Import them, in the same order they had**

At the top of `globals.css`, after the existing `@import` of the token layer. Order matters: these are unlayered rules and the later one wins a tie.

```css
@import "../styles/shell.css";
@import "../styles/list.css";
@import "../styles/surfaces.css";
```

- [ ] **Step 3: Delete the old `--k-*` palette**

The six `[data-accent]` blocks and the `light-dark()` token block at the top of `globals.css` are now dead: `tokens.css` carries all of it. Delete them, and delete `[data-density="compact"]` there too — Task 1 moved it.

Run: `cd apps/web && grep -rn -- "--k-" src`
Expected: no results.

- [ ] **Step 4: Verify nothing moved visually**

Run: `cd apps/web && pnpm test && pnpm lint && pnpm typecheck`, then `cd .. && pnpm test:e2e`
Expected: green. The app now draws in the new palette — this is the first moment it looks like the design system, and also the moment any rule that quietly depended on a `--k-*` name shows itself.

- [ ] **Step 5: Commit**

```bash
git add -A apps/web/src
git commit -m "refactor(web): one stylesheet per surface, so they can die one at a time"
```

---

### Tasks 6, 7 and 8 run in parallel

Their file sets are disjoint by construction, which is the only reason Task 5 exists. Each one:

1. Reads its reference prototypes from the unzipped bundle, in full, before writing anything. The prototype is the specification for every dimension; this plan does not restate them.
2. Moves its screens to Tailwind utilities **and** to the drawn design in the same edit.
3. Deletes the stylesheet it owns, rule by rule, as each becomes unused. The task is not done while the file still exists.
4. Extracts anything that appears twice into `components/ui/` rather than copying it.
5. Adds no token. A value it needs and cannot find is a question for the coordinator.
6. Ends green on `pnpm test`, `pnpm lint`, `pnpm typecheck` and `pnpm test:e2e`, with the output read.

---

### Task 6: Shell and list

**Files:**
- Modify: `apps/web/src/app/page.tsx`, `components/sidebar.tsx`, `components/tickets.tsx`, `components/pills.tsx`, `components/brand-menu.tsx`, `components/new-menu.tsx`
- Delete: `apps/web/src/styles/shell.css`, `apps/web/src/styles/list.css`
- Reference: `Kanso - Écrans actuels.dc.html`, `Kanso - Écrans.dc.html`

**Interfaces:**
- Consumes: `Seal`, `StatusDot`, `PriorityMark`, every token from Task 1.
- Produces: `<Row>`, `<GroupLabel>` in `components/ui/` — Task 7 renders rows inside the ticket panel and must not draw its own.

- [ ] **Step 1: Read both prototypes end to end.** Note every dimension before writing: sidebar width, topbar height, row grid columns, where the id sits, what the meta column holds.

- [ ] **Step 2: The row.** A `<Row>` in `components/ui/row.tsx`, `h-row px-row-x rounded-md` with **no border**: the space between rows is the separation, and adding a rule is how this design stops being this design. Selected is `bg-accent-soft` plus `shadow-[inset_2px_0_0_var(--primary)]` and `text-foreground`; hover is `bg-accent`.

- [ ] **Step 3: The group label.** 11px, uppercase, `tracking-[0.1em]`, `text-faint`, `pt-group px-row-x pb-2`.

- [ ] **Step 4: `pills.tsx` gives up its drawing.** It renders `StatusDot` and `PriorityMark` from Task 4 and keeps only its labels and its interaction. Delete every dot and glyph it drew itself.

- [ ] **Step 5: Sidebar and topbar** to the prototype, then delete `shell.css` and `list.css`.

Run: `cd apps/web && grep -rn "shell.css\|list.css" src` — expected: no results.

- [ ] **Step 6: Verify.** `pnpm test && pnpm lint && pnpm typecheck` in `apps/web`, `pnpm test:e2e` at the root. e2e selectors reaching for deleted class names move to `data-testid`; a test whose assertion no longer describes the design is rewritten deliberately, with the reason in the commit message.

- [ ] **Step 7: Commit** — `feat(web): the shell and the list, as drawn`

---

### Task 7: Floating surfaces

**Files:**
- Modify: `apps/web/src/components/overlays.tsx`, `composer.tsx`, `menu.tsx`, `dialogs/*.tsx`, `components/ui/*.tsx`
- Delete: `apps/web/src/styles/surfaces.css`
- Reference: `Kanso - Écrans.dc.html` (ticket in panel, ticket in page), `Kanso - Écrans 5.dc.html` (composer, menus, error states)

**Interfaces:**
- Consumes: `Seal`, `StatusDot`, `PriorityMark`, `Row`, `GroupLabel`.
- Produces: `<Kbd>` in `components/ui/kbd.tsx` — the shortcut key, which Tasks 6 and 8 also show.

- [ ] **Step 1: Read both prototypes end to end.**

- [ ] **Step 2: `Kbd` first**, since three screens show one: 11px mono, `bg-accent`, `border` with a 2px bottom, `rounded-[4px]`, `px-[5px] py-[3px]`, `text-muted-foreground`.

- [ ] **Step 3: Panels and popovers.** `rounded-panel`, `bg-popover`, `shadow-panel` for a panel and `shadow-float` for something over the page. Never a third shadow layer.

- [ ] **Step 4: The primitives in `components/ui/`** keep their Radix behaviour and their signatures — what changes is what they are dressed in. The eight keyboard invariants from the previous spec still hold and their tests stay green; if one fails, the code is wrong, not the test.

- [ ] **Step 5: Delete `surfaces.css`.**

Run: `cd apps/web && grep -rn "surfaces.css" src` — expected: no results.

- [ ] **Step 6: Verify.** Same four commands, output read. Pay attention to `composer.test.ts`, `team-dialog.test.ts` and the keyboard tests.

- [ ] **Step 7: Commit** — `feat(web): composer, menus and panels, as drawn`

---

### Task 8: Settings, onboarding, sign-in, timeline

**Files:**
- Modify: `apps/web/src/components/settings/*.tsx`, `components/setup/*.tsx`, `components/login.tsx`, `components/timeline/*.tsx`
- Delete: `apps/web/src/app/settings/settings.css`, `app/setup/setup.css`, `app/timeline.css`
- Reference: `Kanso - Écrans 4.dc.html` (settings, setup wizard, sign-in), `Kanso - Timeline.dc.html`

**Interfaces:**
- Consumes: `Seal`, `StatusDot`, `PriorityMark`, `Row`, `GroupLabel`.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Read both prototypes end to end.**

- [ ] **Step 2: Settings.** The appearance section keeps its six accent swatches and its density control — the design system is built on both, and the earlier spec's plan to withdraw them is overturned by this branch's spec. The swatches are drawn in `Kanso - Écrans 4.dc.html`; copy that.

- [ ] **Step 3: Onboarding** keeps its full preferences step and its live preview. `setup.css:87` derives the progress rail from the step count — do not change the number of steps.

- [ ] **Step 4: Timeline.** `timeline-geometry.ts` computes positions and is not a styling concern: it is not touched. Only what draws the bars, the dependency lines, the critical path and the slack changes.

- [ ] **Step 5: Delete the three stylesheets.**

Run: `cd apps/web && grep -rn "settings.css\|setup.css\|timeline.css" src` — expected: no results.

- [ ] **Step 6: Verify.** Same four commands, output read. `timeline-geometry.test.ts` and `row.test.ts` must stay green untouched — if either fails, the geometry was changed by accident.

- [ ] **Step 7: Commit** — `feat(web): settings, onboarding, sign-in and the timeline, as drawn`

---

### Task 9: Dark, compact, and under 720px

**Files:**
- Modify: whatever Tasks 6, 7 and 8 left wrong
- Reference: `Kanso - Écrans 5.dc.html` (dark theme, mobile), `Kanso - Écrans 6.dc.html` (compact density)

This task writes no new surface. It refuses to let the other three ship half a theme.

- [ ] **Step 1: Read every screen in dark.** Set theme to dark and walk the list, the panel, the composer, every menu, settings, onboarding, sign-in and the timeline. Anything that hard-codes a light value shows itself here.

- [ ] **Step 2: Read every screen in compact.** Rows are 27px, group gaps 11px, and the body text is still 13px. A screen that shrank its type has misread the system: compact removes space, never legibility.

- [ ] **Step 3: Read every screen under 720px**, and with a coarse pointer emulated. No target below 44px.

- [ ] **Step 4: Read the contrast on `/design-system`**, in both schemes, at all six accents: `--primary` under `--primary-foreground`, `--accent-ink` on `--accent-soft`, each status hue on `--background`. 4.5:1 is the floor. The ten derived dark accent values are the most likely thing in this branch to be wrong — a hue that will not clear the floor gets its lightness moved, not its label.

- [ ] **Step 5: Verify.** `pnpm test && pnpm lint && pnpm typecheck` in `apps/web`, `pnpm test:e2e` at the root, and confirm no stylesheet remains: `ls apps/web/src/styles/` shows `tokens.css` and nothing else.

- [ ] **Step 6: Commit** — `feat(web): dark, compact and mobile hold across every screen`

---

## Self-review notes

- **Canceled and the priority hues are not invented.** Both are drawn in `Kanso - Design system.dc.html`: canceled is `oklch(0.75 0.008 262)`, high is `oklch(0.58 0.14 52)`. Only their dark counterparts are derived, and Task 9 Step 4 reads them.
- **`--status-canceled` sits above the five-hue plane on purpose**, which is why the plane test in Task 1 excludes it.
- **The 15 undesigned-for-this-branch screens** (kanban, project page, global search, documents, cycle, triage, saved views, workload, import step 2, shortcut sheet, trash, public roadmap, contributor, marketing page, deck) appear in no task. That is deliberate and the spec lists them under "What does not".
