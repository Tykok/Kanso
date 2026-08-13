# Design System Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Install Tailwind v4 and shadcn/ui alongside Kanso's existing stylesheet, publish a `/design-system` page, and move the dropdown menu onto Radix without losing any of its keyboard behaviour.

**Architecture:** The new token layer is **added, not substituted**. Kanso's four hand-written sheets keep working untouched; the three legacy token names that collide with shadcn's (`--accent`, `--border`, `--radius`) are namespaced to `--k-*` first, so both systems coexist and every surface can migrate on its own later. The dropdown menu moves to Radix behind Kanso's existing `Menu` signature, guarded by characterisation tests written *before* the rewrite.

**Tech Stack:** Next.js 16.3 (App Router), React 19.2, TypeScript strict, Tailwind CSS v4, shadcn/ui, Radix UI, pnpm 10.30, Playwright, vitest.

**Spec:** `docs/superpowers/specs/2026-08-13-design-system-shadcn-design.md`

## Global Constraints

- Every package command runs **from `apps/web`**, which carries its own lockfile and `pnpm-workspace.yaml`. The repository root is the Playwright harness and is not a workspace root.
- Use `pnpm dlx`, never `npx`.
- Tailwind v4 needs **no `tailwind.config.js`**; it is configured in CSS through `@theme`. Verified in `node_modules/next/dist/docs/01-app/01-getting-started/11-css.md`.
- `apps/web/postcss.config.mjs` is the correct location. `turbopackLocalPostcssConfig` is not needed — Turbopack resolves PostCSS from the Next project root first.
- **No Kotlin, no Flyway migration, no API contract change** in this plan.
- **Kanso's own triggers stay text glyphs** (`⋯`, `▁▄█`, the status and priority pills). `lucide-react` is installed and used only for the chrome *inside* generated shadcn components — a Dialog's close cross, a DropdownMenu's check mark. Do not replace Kanso's glyphs with icons, and do not strip icon imports out of generated components.
- **Never run `shadcn init`.** CLI 4.17's `init` is a scaffolder that prompts for a preset, defaults to Base UI rather than Radix, and wants to overwrite `globals.css`. `components.json` and `src/lib/utils.ts` are hand-written (Task 2, Step 6); components come from `shadcn add`, which reads that file.
- Accent and density keep working in this plan. They are withdrawn in Plan 2, step 7.
- Code comments are written in English, and explain *why* rather than *what* — match the density of the surrounding files.
- Commits follow Conventional Commits with a descriptive lowercase subject: `feat(web):`, `fix(web):`, `test(web):`, `docs:`.
- The eight keyboard invariants in the spec's "The eight keyboard invariants" section are requirements of Task 5, not suggestions.
- **Rebuilding the web image drops `KANSO_AUTH_MODE`.** `docker compose up -d --build web` recreates the `api` container too, and that variable lives in no repo file — only in the shell that first started the stack. Losing it silently makes every visitor render as a member, which turned a clean run into 8/13 during Task 2. Always rebuild as `KANSO_AUTH_MODE=dev docker compose up -d --build web`, and if a permissions scenario fails unexpectedly, suspect this before suspecting your code.

## Why the tests here are not written failing

Tasks 1 and 5 are refactors of behaviour that already exists. A test that fails first would be testing the wrong thing: what is needed is a **characterisation test** that passes against today's code, locks the behaviour down, and still passes after the rewrite. `e2e/keyboard.spec.ts:16-22` is the precedent in this repository, and says so:

> *"The registry rewrites the keyboard path: this test is what says whether behaviour moved with it. Every assertion describes what the key does today, before the switch — not what one would like it to do."*

Tasks 2, 3 and 4 add new things, and their tests are written failing in the usual way.

## File Structure

**Created:**
- `apps/web/postcss.config.mjs` — the single Tailwind PostCSS plugin registration.
- `apps/web/components.json` — shadcn CLI configuration, hand-written. `init` is never run; see Task 2, Step 6.
- `apps/web/src/lib/utils.ts` — `cn()` only, hand-written.
- `apps/web/src/styles/tokens.css` — the new shadcn token layer. **Its own file**, imported by `globals.css`, so the design tokens someone tunes are not buried in 1269 lines of component rules.
- `apps/web/src/components/ui/*.tsx` — shadcn components, CLI-generated, not hand-edited.
- `apps/web/src/app/design-system/page.tsx` — the styleguide route.
- `apps/web/src/app/design-system/sections.tsx` — the styleguide's client-side demos (dialog and menu need state; the page stays a server component).
- `e2e/14-menu-keyboard.spec.ts` — the eight keyboard invariants.

**Modified:**
- `apps/web/src/app/globals.css` — legacy tokens renamed; two `@import` lines added at the top. Component rules untouched.
- `apps/web/src/app/timeline.css`, `settings/settings.css`, `setup/setup.css` — legacy token renames only.
- `apps/web/src/lib/theme.ts:17-23` and `:78-84` — also toggle `.dark`.
- `apps/web/src/components/menu.tsx` — internals replaced by Radix; exported signature unchanged.
- `apps/web/package.json` — dependencies.

**Deliberately not touched:** `page.tsx`, `lib/actions.ts`, everything under `src/components/timeline/`, and every existing `.test.ts`.

---

### Task 1: Namespace the legacy tokens that collide with shadcn

shadcn defines `--accent` as a *pale hover background*. Kanso's `--accent` **is** the brand colour, used 28 times. Left alone, installing shadcn turns every accented element grey. `--border` (43 uses) and `--radius` (20 uses) collide the same way, less dramatically.

This task is a pure rename with no visual effect, which is exactly what makes it reviewable on its own.

**Files:**
- Modify: `apps/web/src/app/globals.css` (48 usages, and the declarations at `:22`, `:27-30`, `:47`, `:78-105`)
- Modify: `apps/web/src/app/timeline.css` (26 usages)
- Modify: `apps/web/src/app/setup/setup.css` (21 usages)
- Modify: `apps/web/src/app/settings/settings.css` (7 usages)
- Test: `e2e/` (the existing suite, unchanged — it is the proof of no-op)

**Interfaces:**
- Consumes: nothing.
- Produces: the token names `--k-accent`, `--k-accent-soft`, `--k-accent-contrast`, `--k-border`, `--k-radius`. Every later task refers to legacy colour by these names, and to new colour by shadcn's unprefixed names.

Sizing, for checking the rename landed whole: **102 usages** (globals 48, timeline 26, setup 21, settings 7) and **17 declarations** — `--k-accent` and `--k-accent-soft` seven times each, once in `:root` and once per `[data-accent]` block, plus one each of `--k-accent-contrast`, `--k-border` and `--k-radius`.

- [ ] **Step 1: Confirm the rename set is confined to CSS**

Run from `apps/web`:

```bash
grep -rn 'var(--accent-soft)\|var(--accent-contrast)\|var(--accent)\|var(--border)\|var(--radius)' src --include='*.tsx' --include='*.ts'
```

Expected: **no output**. If anything prints, a `.tsx` inline style uses one of these and must be included in the rename below.

- [ ] **Step 2: Rename usages and declarations**

Order matters: the longer names go first, or `--accent` would rewrite the prefix of `--accent-soft`.

```bash
cd apps/web
for f in src/app/globals.css src/app/timeline.css src/app/settings/settings.css src/app/setup/setup.css; do
  perl -pi -e 's/--accent-contrast/--k-accent-contrast/g; s/--accent-soft/--k-accent-soft/g; s/--accent\b/--k-accent/g; s/--border\b/--k-border/g; s/--radius\b/--k-radius/g' "$f"
done
```

`\b` keeps `--k-accent` from being produced twice and protects nothing else — there are no other tokens starting with these strings.

- [ ] **Step 3: Verify no old name survives and no name got doubled**

```bash
cd apps/web
grep -rn '\-\-accent\b\|\-\-border\b\|\-\-radius\b' src/app | grep -v '\-\-k-'
grep -rn '\-\-k-k-' src/app
```

Expected: **no output from either**. The first would mean a missed usage, the second a double substitution.

Note: `[data-accent="indigo"]` and the other five attribute selectors at `globals.css:77-105` are **attribute names, not tokens** — they must still read `data-accent`. Confirm with:

```bash
grep -c 'data-accent' apps/web/src/app/globals.css
```

Expected: `6`.

- [ ] **Step 4: Typecheck and build**

```bash
cd apps/web && pnpm typecheck && pnpm build
```

Expected: both pass. CSS custom property renames cannot break TypeScript, so this is a smoke test that nothing else was touched.

- [ ] **Step 5: Prove the no-op against the running stack**

Bring the stack up from the repository root, per `e2e/README.md`:

```bash
KANSO_AUTH_MODE=dev docker compose up -d --build --wait
pnpm test:e2e
```

Expected: the whole suite passes, exactly as before. Also open <http://localhost:3000> and confirm by eye that the accent colour, borders and corner radii are unchanged, and that switching accent in Settings still recolours the interface — the `[data-accent]` blocks are the part a bad rename would silently kill.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/app
git commit -m "refactor(web): namespace the tokens shadcn is about to claim

shadcn's --accent is a pale hover background; Kanso's is the brand colour.
--border and --radius collide the same way. Renaming ours to --k-* lets both
token systems live in one document while the interface migrates surface by
surface, instead of requiring one big-bang rewrite.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 2: Tailwind v4 and the shadcn token layer

**Files:**
- Create: `apps/web/postcss.config.mjs`
- Create: `apps/web/src/styles/tokens.css`
- Create: `apps/web/components.json`
- Create: `apps/web/src/lib/utils.ts`
- Modify: `apps/web/src/app/globals.css` (top of file only)
- Modify: `apps/web/src/lib/theme.ts`
- Modify: `apps/web/package.json`

**Interfaces:**
- Consumes: `--k-*` from Task 1.
- Produces: utility classes usable anywhere (`bg-background`, `text-foreground`, `rounded-lg`, `border-border`); the tokens `--background --foreground --card --card-foreground --popover --popover-foreground --primary --primary-foreground --secondary --secondary-foreground --muted --muted-foreground --accent --accent-foreground --destructive --destructive-foreground --success --success-foreground --warning --warning-foreground --border --input --ring --radius`; `cn(...classes)` from `@/lib/utils`; and a `.dark` class on `<html>` that tracks the theme preference.

- [ ] **Step 1: Commit the current stylesheet as the record it is**

`globals.css` carries 115 lines of comments recording decisions that are not re-derivable from the CSS. Task 1 already committed the file, so this is a check rather than an action — confirm the pre-shadcn state is in history:

```bash
git log --oneline -1 -- apps/web/src/app/globals.css
```

Expected: Task 1's commit. If `globals.css` has uncommitted changes, stop and commit them first: `shadcn init` overwrites this file.

- [ ] **Step 2: Install Tailwind v4**

```bash
cd apps/web
pnpm add -D tailwindcss @tailwindcss/postcss
```

- [ ] **Step 3: Register the PostCSS plugin**

Create `apps/web/postcss.config.mjs`:

```js
// Tailwind v4 ships its own PostCSS plugin and needs no other entry — no
// autoprefixer, no tailwind.config.js. Turbopack resolves this from the Next
// project root, which is this directory.
export default {
  plugins: {
    "@tailwindcss/postcss": {},
  },
};
```

- [ ] **Step 4: Write the token layer**

Create `apps/web/src/styles/tokens.css`. A file of its own, so the values someone tunes are not buried in the component rules:

```css
/*
 * The design tokens, and the only file to edit to change how Kanso looks.
 *
 * Two token systems live in this document while the interface migrates. The ones
 * below are shadcn's, named as shadcn names them so a component copied from the
 * documentation works unmodified. Kanso's original palette is the `--k-*` family in
 * globals.css, and it still dresses every surface that has not moved yet. Both are
 * driven from the same preference, so they cannot disagree: `data-theme` narrows the
 * legacy `color-scheme`, and `.dark` selects the block below. See lib/theme.ts.
 */

/* Dark mode by class, the shadcn standard. */
@custom-variant dark (&:is(.dark *));

/*
 * ═══════════════════ ADJUST HERE ═══════════════════
 *
 * --primary is the brand colour: change this one line and every button, focus ring
 * and selected state follows. --radius is the corner of every component; 0.5rem is
 * the moderate Linear-like setting, 0.375rem is tighter, 0.75rem softer.
 *
 * Neutrals are pure greys (chroma 0) on purpose: a tinted grey reads as a colour
 * decision at large areas, and Notion and Linear both keep their surfaces neutral so
 * the one saturated colour in view means something.
 */
:root {
  --radius: 0.5rem;

  --primary: oklch(0.53 0.16 277);
  --primary-foreground: oklch(0.985 0 0);

  --background: oklch(1 0 0);
  --foreground: oklch(0.145 0 0);

  --card: oklch(1 0 0);
  --card-foreground: oklch(0.145 0 0);
  --popover: oklch(1 0 0);
  --popover-foreground: oklch(0.145 0 0);

  --secondary: oklch(0.97 0 0);
  --secondary-foreground: oklch(0.205 0 0);
  --muted: oklch(0.97 0 0);
  --muted-foreground: oklch(0.556 0 0);

  /* shadcn's --accent is a hover background, not a brand colour. Kanso's brand
     colour is --primary above; the legacy one is --k-accent. */
  --accent: oklch(0.97 0 0);
  --accent-foreground: oklch(0.205 0 0);

  --destructive: oklch(0.577 0.245 27.325);
  --destructive-foreground: oklch(0.985 0 0);

  /* Not shipped by shadcn, and load-bearing here: globals.css:39 records why amber
     is not red — "a dependency that is broken but still repairable". Dropping the
     distinction would flatten .disposition-warning into an error it is not. */
  --success: oklch(0.60 0.13 150);
  --success-foreground: oklch(0.985 0 0);
  --warning: oklch(0.70 0.15 75);
  --warning-foreground: oklch(0.205 0 0);

  --border: oklch(0.922 0 0);
  --input: oklch(0.922 0 0);
  --ring: oklch(0.53 0.16 277 / 45%);
}

.dark {
  --primary: oklch(0.68 0.15 277);
  --primary-foreground: oklch(0.145 0 0);

  --background: oklch(0.145 0 0);
  --foreground: oklch(0.985 0 0);

  --card: oklch(0.205 0 0);
  --card-foreground: oklch(0.985 0 0);
  --popover: oklch(0.205 0 0);
  --popover-foreground: oklch(0.985 0 0);

  --secondary: oklch(0.269 0 0);
  --secondary-foreground: oklch(0.985 0 0);
  --muted: oklch(0.269 0 0);
  --muted-foreground: oklch(0.708 0 0);

  --accent: oklch(0.269 0 0);
  --accent-foreground: oklch(0.985 0 0);

  --destructive: oklch(0.704 0.191 22.216);
  --destructive-foreground: oklch(0.145 0 0);

  --success: oklch(0.70 0.14 150);
  --success-foreground: oklch(0.145 0 0);
  --warning: oklch(0.78 0.14 75);
  --warning-foreground: oklch(0.145 0 0);

  --border: oklch(1 0 0 / 10%);
  --input: oklch(1 0 0 / 15%);
  --ring: oklch(0.68 0.15 277 / 50%);
}

/*
 * The bridge that turns the variables above into utilities: `bg-background`,
 * `text-muted-foreground`, `rounded-lg`, `border-border`. `inline` resolves the
 * var() at use, which is what lets .dark re-point the same utility.
 */
@theme inline {
  --color-background: var(--background);
  --color-foreground: var(--foreground);
  --color-card: var(--card);
  --color-card-foreground: var(--card-foreground);
  --color-popover: var(--popover);
  --color-popover-foreground: var(--popover-foreground);
  --color-primary: var(--primary);
  --color-primary-foreground: var(--primary-foreground);
  --color-secondary: var(--secondary);
  --color-secondary-foreground: var(--secondary-foreground);
  --color-muted: var(--muted);
  --color-muted-foreground: var(--muted-foreground);
  --color-accent: var(--accent);
  --color-accent-foreground: var(--accent-foreground);
  --color-destructive: var(--destructive);
  --color-destructive-foreground: var(--destructive-foreground);
  --color-success: var(--success);
  --color-success-foreground: var(--success-foreground);
  --color-warning: var(--warning);
  --color-warning-foreground: var(--warning-foreground);
  --color-border: var(--border);
  --color-input: var(--input);
  --color-ring: var(--ring);

  --radius-sm: calc(var(--radius) - 2px);
  --radius-md: var(--radius);
  --radius-lg: var(--radius);
  --radius-xl: calc(var(--radius) + 4px);
}
```

- [ ] **Step 5: Import Tailwind and the tokens, keeping every existing rule**

Add these two lines at the very top of `apps/web/src/app/globals.css`, **above** the existing opening comment, and change nothing else in the file:

```css
@import "tailwindcss";
@import "../styles/tokens.css";
```

Do **not** let `shadcn init` rewrite this file. If it already has, recover with `git checkout apps/web/src/app/globals.css` and re-add the two lines by hand.

Tailwind's preflight will now reset elements this sheet also styles. That is expected and is why the whole existing suite runs at Step 9: preflight lands *before* these rules in the cascade, so the sheet still wins on everything it declares. The gaps show up where it relied on a browser default.

- [ ] **Step 6: Configure shadcn by hand — do not run `init`**

`shadcn@latest` is CLI **4.17.0**, and its `init` is no longer an initialiser: it is `init|create`, it scaffolds from `--template` and `--preset`, it prompts for a preset even under `-y` (a probe hung for seven minutes without writing a file), and its `-d/--defaults` resolve to `--preset=base-nova` — Base UI, not Radix. It also wants to write `globals.css`, which holds 1269 hand-written lines.

None of that is needed. `add` reads `components.json`; it does not care whether `init` produced it. The two files `init` would have given us are small and fully known, so we write them ourselves and skip the scaffolder entirely.

```bash
cd apps/web
pnpm add class-variance-authority clsx tailwind-merge lucide-react
```

**Runtime dependencies, not dev.** The components import them in shipped code, so `-D` would leave them out of the production install.

`lucide-react` is deliberate: shadcn's Dialog and DropdownMenu import icons for their own chrome — a close cross, a check mark. Kanso keeps text glyphs for its *own* triggers (`⋯`, `▁▄█`, the pills); lucide dresses only the inside of the generated components. Removing those imports by hand would mean repeating the cleanup on every future `add`.

Create `apps/web/src/lib/utils.ts`:

```ts
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

/** Joins class names and lets a later Tailwind utility beat an earlier conflicting one,
 *  which is what makes a `className` prop able to override a component's own defaults. */
export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

Create `apps/web/components.json`. `add` takes no `--base` flag, so the component library is declared here — `radix` is what the spec's whole keyboard analysis rests on:

```json
{
  "$schema": "https://ui.shadcn.com/schema.json",
  "style": "new-york",
  "base": "radix",
  "rsc": true,
  "tsx": true,
  "tailwind": {
    "config": "",
    "css": "src/app/globals.css",
    "baseColor": "neutral",
    "cssVariables": true,
    "prefix": ""
  },
  "iconLibrary": "lucide",
  "aliases": {
    "components": "@/components",
    "utils": "@/lib/utils",
    "ui": "@/components/ui",
    "lib": "@/lib",
    "hooks": "@/hooks"
  }
}
```

- [ ] **Step 6b: Probe the schema before trusting it**

This `components.json` is written against a CLI whose exact schema we have not read, so verify it resolves before Task 3 depends on it. `--dry-run` writes nothing:

```bash
cd apps/web && pnpm dlx shadcn@latest add button --dry-run
```

Expected: it resolves `button` and reports the files it *would* write, without a schema or validation error.

If it rejects the file, the error names the offending field. Fix that field and re-run — the likely culprits are `base` (drop it, or use the value the error suggests) and `style` (4.x may have retired `new-york`). Do **not** fall back to running `init` to escape this. Record in your report the final shape that worked, since Task 3 and Task 5 both build on it.

`add --view <component>` prints a component's source without writing it, which is the cheap way to answer "what does the generated file actually export" — useful now, and required by Task 5.

- [ ] **Step 7: Make the theme preference drive `.dark` as well**

Both token systems must follow one preference. In `apps/web/src/lib/theme.ts`, replace the body of `applyPreferences` (currently lines 17-23):

```ts
export function applyPreferences(preferences: Preferences) {
  const root = document.documentElement;
  root.dataset.theme = preferences.theme;
  root.dataset.accent = preferences.accent;
  root.dataset.density = preferences.density;
  root.dataset.syncBadges = preferences.showSyncBadges ? "on" : "off";
  // The legacy palette resolves light-dark() off `color-scheme`, which data-theme
  // narrows; the shadcn tokens select on .dark. Two mechanisms, one source, set
  // together here so they cannot drift while the interface migrates between them.
  root.classList.toggle("dark", prefersDark(preferences.theme));
}

/** `system` is not a palette but a deferral, so it has to be resolved before use. */
export function prefersDark(theme: Theme): boolean {
  if (theme === "dark") return true;
  if (theme === "light") return false;
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}
```

Add `type Theme` to the existing import from `./api` at line 1-7.

- [ ] **Step 8: Set `.dark` before first paint too**

The bootstrap script exists so a dark-mode user never sees a white page (`theme.ts:67-77`). It has to set the class for the same reason it sets the attribute. Replace the `PREFERENCE_BOOTSTRAP_SCRIPT` template (lines 78-84):

```ts
export const PREFERENCE_BOOTSTRAP_SCRIPT = `(function(){try{
var p=JSON.parse(localStorage.getItem(${JSON.stringify(PREFERENCES_STORAGE_KEY)})||"{}"),d=document.documentElement;
var t=p.theme||${JSON.stringify(DEFAULT_PREFERENCES.theme)};
d.dataset.theme=t;
d.dataset.accent=p.accent||${JSON.stringify(DEFAULT_PREFERENCES.accent)};
d.dataset.density=p.density||${JSON.stringify(DEFAULT_PREFERENCES.density)};
d.dataset.syncBadges=p.showSyncBadges===false?"off":"on";
d.classList.toggle("dark",t==="dark"||(t!=="light"&&window.matchMedia("(prefers-color-scheme: dark)").matches));
}catch(e){}})()`;
```

Note the inverted test: `t !== "light"` rather than `t === "system"`, so a corrupted value falls back to the OS preference instead of forcing light. The surrounding `try/catch` already covers a broken JSON payload; this covers a valid payload with a nonsense theme.

- [ ] **Step 9: Verify nothing regressed and utilities actually apply**

```bash
cd apps/web && pnpm typecheck && pnpm build && pnpm test
```

Expected: all pass.

Then, with the stack up:

```bash
pnpm test:e2e
```

Expected: the full suite passes. Preflight is the thing under test here — if a scenario fails, the cause is almost certainly a browser default that a hand-written rule was relying on. Fix it in the sheet that owns the element, not by disabling preflight.

Finally, confirm a utility resolves at all: in devtools on <http://localhost:3000>, run `getComputedStyle(document.documentElement).getPropertyValue('--primary')`. Expected: a non-empty `oklch(...)`. Then toggle the theme in Settings and confirm `<html>` gains and loses `class="dark"` while `data-theme` changes alongside.

- [ ] **Step 10: Commit**

```bash
git add apps/web/package.json apps/web/pnpm-lock.yaml apps/web/postcss.config.mjs \
        apps/web/components.json apps/web/src/styles apps/web/src/lib/utils.ts \
        apps/web/src/lib/theme.ts apps/web/src/app/globals.css
git commit -m "feat(web): add Tailwind v4 and a shadcn token layer beside the old sheet

Added rather than substituted: the existing rules still dress every surface, so
the interface can migrate one at a time instead of in one unreviewable commit.
The tokens live in their own file because they are the thing people tune.

Both palettes follow one preference — data-theme narrows color-scheme for the
legacy light-dark() colours, .dark selects the new ones — set together in
theme.ts so they cannot drift.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 3: The six components and the `/design-system` page

Built now rather than at the end, so it grows with the migration and gives every later task somewhere to check a component in isolation.

**Files:**
- Create: `apps/web/src/components/ui/{button,card,input,dialog,dropdown-menu,badge}.tsx` (CLI-generated)
- Create: `apps/web/src/app/design-system/page.tsx`
- Create: `apps/web/src/app/design-system/sections.tsx`
- Test: `e2e/15-design-system.spec.ts`

**Interfaces:**
- Consumes: the tokens and `cn()` from Task 2.
- Produces: `Button` (variants `default | secondary | destructive | outline | ghost | link`), `Card`, `Input`, `Dialog`, `DropdownMenu`, `Badge` (variants `default | secondary | destructive | outline`, plus `success` and `warning` added below) — all from `@/components/ui/*`. Task 5 consumes `DropdownMenu`.

- [ ] **Step 1: Install the components**

```bash
cd apps/web
pnpm dlx shadcn@latest add button card input dialog dropdown-menu badge
```

`class-variance-authority`, `clsx`, `tailwind-merge` and `lucide-react` are already installed by Task 2. This step adds the Radix primitives the components wrap — `@radix-ui/react-dialog`, `@radix-ui/react-dropdown-menu`, and whatever else the generated files import — and possibly `tw-animate-css`. If `tw-animate-css` appears in `package.json`, confirm `globals.css` or `tokens.css` imports it; add `@import "tw-animate-css";` next to the Tailwind import if the CLI did not.

If `add` writes into `globals.css` — 4.x may try to append token blocks of its own — check with `git diff apps/web/src/app/globals.css` and revert anything beyond the two `@import` lines. The tokens are Task 2's, in `tokens.css`, and a second competing set in `globals.css` would win by source order and silently override them.

- [ ] **Step 2: Read what the CLI actually generated**

Before writing anything against these components, read them:

```bash
cd apps/web && cat src/components/ui/badge.tsx src/components/ui/dropdown-menu.tsx
```

The exported names and the `cva` variant keys are the contract Tasks 3 and 5 depend on, and they change between shadcn releases. If any name below differs from the file, the file wins — adjust the plan's code, not the generated component.

- [ ] **Step 3: Add the `success` and `warning` badge variants**

`Badge` ships `default | secondary | destructive | outline`. Kanso needs two more: `SyncBadge` has a `pending`, a `failed` and a `synced` state (`pills.tsx:95-110`), and amber is deliberately not red. In `src/components/ui/badge.tsx`, add to the `variants.variant` object:

```ts
        success:
          "border-transparent bg-success text-success-foreground [a&]:hover:bg-success/90",
        warning:
          "border-transparent bg-warning text-warning-foreground [a&]:hover:bg-warning/90",
```

Match the surrounding entries' exact shape — if the generated file does not use the `[a&]:hover:` prefix, drop it here too.

- [ ] **Step 4: Write the failing test**

Create `e2e/15-design-system.spec.ts`. It asserts the page is reachable and that a token actually resolved — a styleguide rendering with unresolved variables is the failure mode worth catching:

```ts
import { expect, test } from "@playwright/test";

/**
 * Scenario 15 — the workbench itself.
 *
 * Deliberately thin: this page has no behaviour to test. What it does catch is a
 * token layer that failed to load, which renders as unstyled markup rather than an
 * error, and which every later migration task would then be building against.
 */
test("scenario 15 — the design system page renders its tokens and components", async ({
  page,
}) => {
  await page.goto("/design-system");

  await expect(page.getByRole("heading", { name: "Design system", level: 1 })).toBeVisible();

  // Every button variant is drawn, including the two badge variants Kanso adds.
  await expect(page.getByRole("button", { name: "default", exact: true })).toBeVisible();
  await expect(page.getByText("warning", { exact: true })).toBeVisible();

  // The tokens resolved. Deliberately not asserted on the token's literal text:
  // Lightning CSS downlevels oklch() to lab() when no browserslist is configured, so
  // the format is not ours to predict. What an unloaded layer actually produces is an
  // empty variable and a button whose background falls back to transparent, and that
  // is what these two assertions catch between them.
  const primary = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue("--primary").trim(),
  );
  expect(primary).not.toBe("");

  const buttonBackground = await page
    .getByRole("button", { name: "default", exact: true })
    .evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(buttonBackground).not.toBe("rgba(0, 0, 0, 0)");
  expect(buttonBackground).not.toBe("transparent");

  // The dialog opens and closes, which is the one interaction on this page.
  await page.getByRole("button", { name: "Open dialog" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
```

- [ ] **Step 5: Run it and watch it fail**

```bash
pnpm test:e2e e2e/15-design-system.spec.ts
```

Expected: **FAIL**. The route does not exist yet, so `/design-system` serves Next's 404 and the `level: 1` heading assertion is what reports it. If this passes, something is wrong with the test — check that it is really hitting this route and not a redirect.

- [ ] **Step 6: Write the styleguide's interactive demos**

Dialog and dropdown need state, so they live in a client component while the page itself stays a server component. Create `apps/web/src/app/design-system/sections.tsx`:

```tsx
"use client";

import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export function DialogDemo() {
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="outline">Open dialog</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Archive this project?</DialogTitle>
          <DialogDescription>
            Its tickets stay where they are. Archiving only hides the project from the
            sidebar.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline">Cancel</Button>
          <Button variant="destructive">Archive</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

export function DropdownDemo() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline">Open menu</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel>KAN-14</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem>Rename</DropdownMenuItem>
        <DropdownMenuItem>Archive</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive">Delete</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
```

If `DropdownMenuItem` has no `variant` prop in the generated file, use `className="text-destructive"` instead.

- [ ] **Step 7: Write the styleguide page**

Create `apps/web/src/app/design-system/page.tsx`:

```tsx
import type { Metadata } from "next";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Input } from "@/components/ui/input";
import { DialogDemo, DropdownDemo } from "./sections";

export const metadata: Metadata = { title: "Design system — Kanso" };

/**
 * The tokens and every installed component on one screen.
 *
 * Not linked from the interface: it is a workbench, not a feature. It exists so a
 * change to --primary or --radius can be judged against every component at once
 * rather than discovered later on one screen that happened to use it.
 */

/** Paired so each swatch can show its foreground on its own background — the only
 *  check that matters for a colour token is whether text on it stays legible. */
const SURFACES = [
  { name: "background", fg: "text-foreground", bg: "bg-background" },
  { name: "card", fg: "text-card-foreground", bg: "bg-card" },
  { name: "popover", fg: "text-popover-foreground", bg: "bg-popover" },
  { name: "primary", fg: "text-primary-foreground", bg: "bg-primary" },
  { name: "secondary", fg: "text-secondary-foreground", bg: "bg-secondary" },
  { name: "muted", fg: "text-muted-foreground", bg: "bg-muted" },
  { name: "accent", fg: "text-accent-foreground", bg: "bg-accent" },
  { name: "destructive", fg: "text-destructive-foreground", bg: "bg-destructive" },
  { name: "success", fg: "text-success-foreground", bg: "bg-success" },
  { name: "warning", fg: "text-warning-foreground", bg: "bg-warning" },
];

const BUTTON_VARIANTS = ["default", "secondary", "destructive", "outline", "ghost", "link"] as const;
const BADGE_VARIANTS = ["default", "secondary", "destructive", "outline", "success", "warning"] as const;
const RADII = [
  { name: "rounded-sm", cls: "rounded-sm" },
  { name: "rounded-md", cls: "rounded-md" },
  { name: "rounded-lg", cls: "rounded-lg" },
  { name: "rounded-xl", cls: "rounded-xl" },
];

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="flex flex-col gap-4">
      <h2 className="text-sm font-semibold tracking-tight">{title}</h2>
      {children}
    </section>
  );
}

export default function DesignSystemPage() {
  return (
    <main className="mx-auto flex max-w-4xl flex-col gap-12 bg-background p-10 text-foreground">
      <header className="flex flex-col gap-2">
        <h1 className="text-2xl font-semibold tracking-tight">Design system</h1>
        <p className="text-sm text-muted-foreground">
          Every token and component, in the current theme. Tune the values in{" "}
          <code className="rounded-sm bg-muted px-1.5 py-0.5">src/styles/tokens.css</code>.
        </p>
      </header>

      <Section title="Surfaces">
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3">
          {SURFACES.map((surface) => (
            <div
              key={surface.name}
              className={`${surface.bg} ${surface.fg} rounded-lg border border-border p-4 text-xs`}
            >
              {surface.name}
            </div>
          ))}
        </div>
      </Section>

      <Section title="Radius">
        <div className="flex flex-wrap gap-3">
          {RADII.map((radius) => (
            <div key={radius.name} className="flex flex-col items-center gap-2">
              <div className={`size-16 border border-border bg-muted ${radius.cls}`} />
              <span className="text-xs text-muted-foreground">{radius.name}</span>
            </div>
          ))}
        </div>
      </Section>

      <Section title="Buttons">
        <div className="flex flex-wrap items-center gap-3">
          {BUTTON_VARIANTS.map((variant) => (
            <Button key={variant} variant={variant}>
              {variant}
            </Button>
          ))}
          <Button disabled>disabled</Button>
        </div>
      </Section>

      <Section title="Badges">
        <div className="flex flex-wrap items-center gap-3">
          {BADGE_VARIANTS.map((variant) => (
            <Badge key={variant} variant={variant}>
              {variant}
            </Badge>
          ))}
        </div>
      </Section>

      <Section title="Input">
        <div className="flex max-w-sm flex-col gap-3">
          <Input placeholder="Filter tickets…" />
          <Input placeholder="Disabled" disabled />
        </div>
      </Section>

      <Section title="Card">
        <Card className="max-w-sm">
          <CardHeader>
            <CardTitle>Notion mirror</CardTitle>
            <CardDescription>Pushed a moment ago.</CardDescription>
          </CardHeader>
          <CardContent className="text-sm text-muted-foreground">
            The mirror runs behind by design, so its state is shown per row.
          </CardContent>
          <CardFooter>
            <Badge variant="success">Notion</Badge>
          </CardFooter>
        </Card>
      </Section>

      <Section title="Overlays">
        <div className="flex flex-wrap items-center gap-3">
          <DialogDemo />
          <DropdownDemo />
        </div>
      </Section>
    </main>
  );
}
```

- [ ] **Step 8: Rebuild and run it green**

The stack serves a built image, so the page has to be rebuilt into it:

```bash
docker compose up -d --build --wait web
pnpm test:e2e e2e/15-design-system.spec.ts
```

Expected: PASS.

- [ ] **Step 9: Look at it**

Open <http://localhost:3000/design-system> and check both themes — switch the OS appearance, or set `kanso.preferences` to `{"theme":"dark"}` in localStorage and reload. Every swatch's label must stay legible on its own background; that is the one thing the test cannot judge. Adjust `tokens.css` if a pair is too close, and note that `--warning`'s foreground is dark on purpose, since amber cannot carry white at 4.5:1.

- [ ] **Step 10: Confirm the rest of the app is untouched**

```bash
cd apps/web && pnpm typecheck && pnpm build && pnpm test
cd .. && pnpm test:e2e
```

Expected: all pass. This task added a route and touched nothing else, so a failure elsewhere means a component install changed a shared file.

- [ ] **Step 11: Commit**

```bash
git add apps/web/package.json apps/web/pnpm-lock.yaml apps/web/src/components/ui \
        apps/web/src/app/design-system e2e/15-design-system.spec.ts
git commit -m "feat(web): a design system page, and the six components it shows

Built now rather than last, so it grows with the migration and every later task
has somewhere to judge a component on its own. Badge gains success and warning
variants shadcn does not ship: globals.css:39 records why amber is not red, and
SyncBadge has three states to tell apart.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 4: Lock the dropdown's keyboard behaviour before touching it

Characterisation tests. They must pass against today's hand-written `menu.tsx`, and still pass after Task 5 replaces its internals. See "Why the tests here are not written failing".

jsdom is not an option here: it does not implement Tab's native focus-moving default action at all, which is precisely what invariants 2 and 3 turn on. `vitest.config.mts` also stays `environment: "node"` by an argued choice, and this task gives no reason to overturn it.

**Files:**
- Create: `e2e/14-menu-keyboard.spec.ts`
- Test: itself

**Interfaces:**
- Consumes: `openAs`, `seedInstance`, `seedTeam`, `seedTicket`, `apiAs`, `unique`, `uniqueKey`, `ticketRow`, `ADMIN`, `MEMBER`, `userIdOf`, `seedMember` from `e2e/support.ts`.
- Produces: the gate Task 5 must pass.

- [ ] **Step 1: Write the characterisation test**

Create `e2e/14-menu-keyboard.spec.ts`:

```ts
import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedMember,
  seedTeam,
  seedTicket,
  ticketRow,
  unique,
  uniqueKey,
  userIdOf,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 14 — the dropdown's keyboard, written down before Radix replaces it.
 *
 * Same intent as scenario 5 and the same rule: every assertion describes what the
 * menu does *today*. Six of these behaviours are things Radix does not provide for
 * free, and each one was a deliberate decision in menu.tsx — the arrow keys not
 * leaking to the window listener, the focus target that survives its own popover
 * unmounting, the ⋯ that does not exist at all without permissions.
 */
test("scenario 14 — the row menu's keyboard survives the move to Radix", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Menus"), key: uniqueKey() });
  const first = unique("Alpha ticket");
  const second = unique("Beta ticket");
  await seedTicket(api, { teamId: team.id, title: first });
  await seedTicket(api, { teamId: team.id, title: second });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();

  const selected = page.locator('.row[data-selected="true"]');
  // The list orders most-recently-updated first, so `second` is on top.
  await ticketRow(page, second).click();
  await expect(selected).toContainText(second);

  const trigger = ticketRow(page, second).getByRole("button", { name: /^Actions for/ });

  // Invariant 5 — clicking the trigger must not let the row underneath change the
  // scope. The selection stays where it was.
  await trigger.click();
  const menu = page.getByRole("menu");
  await expect(menu).toBeVisible();
  await expect(selected).toContainText(second);

  // Invariant 1 — arrows walk the menu and do NOT reach the window handler in
  // page.tsx. Without the popover's stopPropagation, each press would also move the
  // list cursor off `second`.
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await expect(selected).toContainText(second);
  await expect(menu.getByRole("menuitem").nth(2)).toBeFocused();

  // Home and End reach the ends of the list.
  await page.keyboard.press("End");
  await expect(menu.getByRole("menuitem").last()).toBeFocused();
  await page.keyboard.press("Home");
  await expect(menu.getByRole("menuitem").first()).toBeFocused();

  // Invariant 7 — Escape closes the menu, once, and hands focus back to the trigger.
  // It must not also close anything behind it.
  await page.keyboard.press("Escape");
  await expect(menu).toHaveCount(0);
  await expect(trigger).toBeFocused();

  // Invariant 2 — Tab out of an open menu lands on what follows the row, not at the
  // top of the document. Asserted as "not body", because the exact next focusable
  // is a layout detail this test should not freeze.
  await trigger.click();
  await expect(page.getByRole("menu")).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("menu")).toHaveCount(0);
  const tabbed = await page.evaluate(() => document.activeElement?.tagName ?? "NONE");
  expect(tabbed).not.toBe("BODY");

  // Invariant 3 — an entry that opens a dialog leaves a focus-return target that
  // still exists once the popover is gone. Rename opens the in-place editor; closing
  // it must not drop focus to the body.
  await trigger.click();
  await page.getByRole("menuitem", { name: /Rename/ }).click();
  await expect(page.locator(".row-title-input")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.locator(".row-title-input")).toHaveCount(0);
  const afterDialog = await page.evaluate(() => document.activeElement?.tagName ?? "NONE");
  expect(afterDialog).not.toBe("BODY");

  // Invariant 6 is NOT asserted here, on purpose. Only brand-menu.tsx passes a
  // `header`; a row's ⋯ has none, so there would be nothing on this menu to check.
  // It is already covered where the header actually exists — mouse.spec.ts:216-217
  // asserts `.menu-header` and `.menu-footer` are absent from inside the role="menu"
  // element, which is exactly the invariant. Task 5 must keep that passing, and must
  // repair the `.brand .menu-popover` locator at mouse.spec.ts:203, which stops
  // matching once Radix portals the popover out of `.brand`.

  await page.close();
});

/**
 * Invariant 4, and the one with teeth: an empty item list renders no trigger at all.
 * A plain member has no action on a team row, so there is no ⋯ to find — not a
 * disabled one, not an empty popover. Callers rely on this instead of each asking
 * about permissions themselves (menu.tsx:79).
 */
test("scenario 14 — a member sees no trigger where they have no actions", async ({ browser }) => {
  const admin = await apiAs(ADMIN);
  const team = await seedTeam(admin, { name: unique("NoActions"), key: uniqueKey() });
  await seedMember(admin, team.id, await userIdOf(MEMBER));
  await admin.dispose();

  const page = await openAs(browser, MEMBER);
  const row = page.locator(".nav-item").filter({
    has: page.getByRole("button", { name: team.name, exact: true }),
  });
  await expect(row).toBeVisible();
  await expect(row.getByRole("button", { name: /^Actions for/ })).toHaveCount(0);

  await page.close();
});
```

- [ ] **Step 2: Run it against today's implementation**

```bash
pnpm test:e2e e2e/14-menu-keyboard.spec.ts
```

Expected: **PASS**, both tests. This is a characterisation test, so a failure here means one of two things — read the failure before changing anything:

- the assertion misdescribes what the menu does today, and the assertion is wrong; or
- the behaviour the spec claims is not actually there, and the spec's invariant list needs correcting.

Do not "fix" the menu to satisfy this test. Its only job is to record the present.

- [ ] **Step 3: Record what the run taught you**

If any assertion had to be relaxed or corrected in Step 2, amend the spec's "The eight keyboard invariants" section to match, in the same commit. A spec that disagrees with the passing test is worse than no spec.

- [ ] **Step 4: Commit**

```bash
git add e2e/14-menu-keyboard.spec.ts docs/superpowers/specs/2026-08-13-design-system-shadcn-design.md
git commit -m "test(e2e): write down the dropdown's keyboard before Radix replaces it

A characterisation test, passing against the current hand-written menu, in the
same spirit as scenario 5. Six of these are behaviours Radix does not provide for
free; without them written down, the rewrite would lose them quietly. In
Playwright rather than vitest because jsdom does not implement Tab's default
action, which is exactly what two of the invariants turn on.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

### Task 5: Move `Menu` onto Radix behind its own signature

**Files:**
- Modify: `apps/web/src/components/menu.tsx` (all 204 lines; the exported `Menu` and `MenuItem` signatures do not change)
- Modify: `apps/web/src/app/globals.css` — delete `.status-menu > .menu` / `.priority-menu` block at `598-632`
- Test: `e2e/14-menu-keyboard.spec.ts` (from Task 4, unchanged), plus the whole suite

**Interfaces:**
- Consumes: `DropdownMenu*` from `@/components/ui/dropdown-menu` (Task 3); the gate from Task 4.
- Produces: the same `Menu({ label, items, trigger, header, footer })` and `MenuItem` exports the six existing callers already import — `tickets.tsx`, `sidebar.tsx`, `new-menu.tsx`, `brand-menu.tsx`, and `pills.tsx` twice. **No call site changes.**

- [ ] **Step 1: Read the generated component, and confirm what is already known about it**

This step was written as an open question. It has since been answered by reading the CLI's own output (`pnpm dlx shadcn@latest view dropdown-menu`), so it is now a confirmation rather than an investigation. Read the installed file and check the three facts below still hold — a CLI release could move underneath us:

```bash
cd apps/web && cat src/components/ui/dropdown-menu.tsx
```

**Fact 1 — it is genuinely Radix.** The import is `import { DropdownMenu as DropdownMenuPrimitive } from "radix-ui"`: the unified package, not the old per-primitive `@radix-ui/react-dropdown-menu`. Every keyboard behaviour the spec reasons about is therefore Radix's. This is what `components.json` gets us without a `base` field, which CLI 4.17's schema does not have.

**Fact 2 — invariant 6 is settled, and it goes against us.** `DropdownMenuContent` renders `DropdownMenuPrimitive.Content`, which carries `role="menu"`, and `DropdownMenuLabel` renders `DropdownMenuPrimitive.Label` as a **child** of that content. So a label sits *inside* the `menu` role — exactly what `menu.tsx:30-36` avoids, since a `menu` may only own `menuitem`, `group` and `separator`, and assistive technology may drop the identity block the header carries.

Take the first of the spec's three routes: **render `header` and `footer` outside the entries' `role="menu"`.** In practice, since `role="menu"` is fixed on `DropdownMenuContent`, that means putting the header and footer in the content but giving the *entries* their own nested element, mirroring today's structure — or, if Radix's item components refuse to work through a plain wrapper, keeping the header outside `DropdownMenuContent` entirely. Try the nested-list shape first, verify with the assertion Task 4 wrote, and record which shape you ended up with in the comment at the top of `menu.tsx`.

If neither shape holds without fighting Radix, that is the spec's third route: accept the regression, record it in `docs/follow-ups.md` with the reasoning from `menu.tsx:30-36`, and say so in your report. Do not silently drop the header.

**Fact 3 — the content is PORTALLED, and this is the part the plan originally missed.** `DropdownMenuContent` wraps its content in `DropdownMenuPrimitive.Portal`, so the open menu is rendered at `document.body`, **not inside the row that triggered it**. Three consequences, all of which you must handle:

- **`.menu-popover` cannot be reused as-is.** That class is `position: absolute; top: calc(100% + 4px); right: 0` relative to `.menu` (`globals.css:872-885`). Portalled to the body, those offsets are meaningless and fight Radix's own positioning, which it computes and applies itself. See Step 2 for what to pass instead.
- **Descendant selectors scoping a popover under an ancestor stop matching.** `e2e/support.ts:203` locates `.brand .menu-popover`; once portalled, the popover is not a descendant of `.brand`. Step 5 covers the fix.
- **Key events cross a portal boundary.** React routes portal events through the React tree, but `page.tsx` listens natively on `window`, and whether a synthetic `stopPropagation` on portalled content still shields it is not something to reason about from first principles. Task 4's test is what answers it. Step 3 lists the remedy if it does not.

- [ ] **Step 2: Rewrite the internals**

Replace the body of `apps/web/src/components/menu.tsx`, keeping the `MenuItem` type and the `Menu` signature exactly. Adjust the header/footer placement to whatever Step 1 settled:

```tsx
"use client";

import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";

export type MenuItem = {
  id: string;
  label: string;
  /**
   * The key that fires the same action from anywhere. Shown against the entry because
   * the menus are where a keyboard-first application's keyboard is discovered: nobody
   * reads the help overlay to find out that `e` renames.
   */
  hint?: string;
  /** Last, detached, never the default choice. */
  danger?: boolean;
  onSelect: () => void;
};

/**
 * The dropdown every mouse path in the application goes through, now on Radix.
 *
 * The signature is unchanged from the hand-written version, so all six callers are
 * untouched: a row's `⋯`, the sidebar's rows, the top bar's `New`, the brand block's
 * account menu, and the status and priority pills — the last two passing
 * `trigger={null}`, which now means "the pill is the trigger" through `asChild`
 * rather than through an absolutely positioned button over it.
 *
 * Three of the behaviours below are not Radix defaults and are load-bearing. They are
 * asserted in e2e/14-menu-keyboard.spec.ts; read that file before changing any of them.
 */
export function Menu({
  label,
  items,
  trigger = "⋯",
  header,
  footer,
  asChild = false,
}: {
  label: string;
  items: MenuItem[];
  trigger?: React.ReactNode;
  header?: React.ReactNode;
  footer?: React.ReactNode;
  /**
   * When set, `trigger` is not wrapped in a button of the menu's own — it *is* the
   * button. The status and priority pills use this: what you are already reading is
   * what you click, which used to be faked with an invisible button laid over the
   * pill (globals.css:598-632, deleted in this commit).
   */
  asChild?: boolean;
}) {
  // An empty list renders nothing at all — not a disabled trigger, not an empty
  // popover. This is what makes a plain member see no `⋯` on a team row without any
  // caller having to ask about permissions.
  if (items.length === 0) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger
        asChild={asChild}
        className={asChild ? undefined : "menu-trigger"}
        aria-label={label}
        // The row underneath changes the scope when it is clicked.
        onClick={(event) => event.stopPropagation()}
      >
        {trigger}
      </DropdownMenuTrigger>
      <DropdownMenuContent
        align="end"
        // No `.menu-popover`: that class positions a popover absolutely against
        // `.menu`, and this content is portalled to the body, where Radix computes
        // and applies its own position. Passing it would fight the library. What the
        // class also carried — surface, border, radius, shadow — the generated
        // component already applies with its own utilities.
        //
        // `role="presentation"` moves the menu role off the popover and onto the list
        // of entries below, which is where menu.tsx:30-36 argues it belongs: a `menu`
        // may only own menuitem, group and separator, so the header would otherwise
        // sit inside one and risk being dropped by assistive technology.
        role="presentation"
        // page.tsx listens for keys on window. Radix does not shield it, so without
        // this every arrow inside the menu would also move the list cursor.
        onKeyDown={(event) => event.stopPropagation()}
      >
        {header && <div className="menu-header">{header}</div>}
        <div className="menu-list" role="menu" aria-label={label}>
          {items.map((item) => (
            <DropdownMenuItem
              key={item.id}
              className="menu-item"
              data-danger={item.danger ? "true" : undefined}
              variant={item.danger ? "destructive" : "default"}
              onSelect={() => item.onSelect()}
            >
              <span className="menu-label">{item.label}</span>
              {item.hint && (
                <>
                  {/* A real space, not a CSS gap: it is what separates label from hint
                      in the accessible name, so both read as "Rename ticket e". */}
                  {" "}
                  <span className="menu-hint">{item.hint}</span>
                </>
              )}
            </DropdownMenuItem>
          ))}
        </div>
        {footer && <div className="menu-footer">{footer}</div>}
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
```

Two things this deliberately does *not* do, because Radix already does them and duplicating them causes double handling: the roving `tabIndex`, and the focus-return on close. Radix restores focus to the trigger itself, which is what invariants 2 and 3 needed the manual `triggerRef.current?.focus()` for.

**The nested `role="menu"` is the risky part of this file.** Radix manages focus, typeahead and arrow navigation over its items through a collection, and putting a plain `<div>` between the content and the items may or may not disturb that. Task 4's test and `mouse.spec.ts:216-217` between them tell you: the former proves the keyboard still works, the latter proves the header stayed outside the menu role. If the wrapper breaks Radix's navigation, try `asChild` on the content with the wrapper as its child before giving up. If you must abandon the nested shape, take the spec's third route — accept the regression, record it in `docs/follow-ups.md` citing `menu.tsx:30-36`, and relax `mouse.spec.ts:216-217` in this commit rather than leaving a failing test.

- [ ] **Step 3: Run the characterisation test**

```bash
pnpm test:e2e e2e/14-menu-keyboard.spec.ts
```

Expected: PASS, unchanged from Task 4.

The likely failures, and where to look:

- *arrows move the list cursor* → invariant 1; the `onKeyDown` above is missing or Radix stops propagation before it.
- *focus lands on `BODY` after Tab or after the rename editor closes* → invariants 2 and 3; wire `onCloseAutoFocus` on `DropdownMenuContent` to refocus the trigger explicitly.
- *`Rename` fires but the editor never focuses* → Radix runs `onSelect` before closing, and the editor's own autofocus loses to Radix's focus restoration. Defer with `event.preventDefault()` on the item's `onSelect` plus an explicit close, or move the action into `onCloseAutoFocus`.

- [ ] **Step 4: Let the pill actually be the trigger**

`pills.tsx` passes `trigger={null}`, and `globals.css:598-632` compensates with `display: contents` plus a `position: absolute; inset: 0` button over the pill — 35 lines, the subtlest passage in the sheet. `asChild` makes it unnecessary.

`Menu` already takes `asChild` from Step 2. Radix clones the child and hands it the trigger's props, so **the child has to be a real `<button>`** — a `<span>` would receive `aria-haspopup` and an `onClick` without being focusable or firing on Enter. That is the one substantive change here: the interactive pill becomes a button, and the non-interactive one stays a `<span>`.

In `apps/web/src/components/pills.tsx`, replace `StatusPill`'s interactive branch (lines 45-54):

```tsx
  return (
    <Menu
      label={`Status: ${STATUS_LABELS[status]}`}
      asChild
      trigger={
        <button
          type="button"
          className="status"
          data-status={status}
          style={{ color: STATUS_COLORS[status] }}
        >
          {body}
        </button>
      }
      items={menuItems(ctx, STATUS_ACTIONS)}
    />
  );
```

and `PriorityMark`'s (lines 78-87):

```tsx
  return (
    <Menu
      label={`Priority: ${label}`}
      asChild
      trigger={
        <button type="button" className="priority" style={{ color }} title={label}>
          <span aria-hidden="true">{glyph}</span>
        </button>
      }
      items={menuItems(ctx, PRIORITY_ACTIONS)}
    />
  );
```

`.status-menu` and `.priority-menu` are gone from both — those classes existed only to anchor the invisible button. `aria-hidden` stays on the glyph: the accessible name comes from the `aria-label` Radix merges in, and `▄` read aloud is noise.

Then delete `globals.css:598-632` — the `.status-menu`/`.priority-menu` block, from its comment through the `.menu-popover` overrides.

Two things to check by eye afterwards, both of which that block existed to prevent:

- An interactive pill and a non-interactive one (the setup wizard's preview renders the latter, `pills.tsx:26-27`) must still align. The old bug was a 6px flex gap counting an empty box as an item, pushing the dot of an interactive pill right of a static one.
- The pill must still show its focus ring and open on Enter and Space, now that it is genuinely the button rather than a box with one laid over it.

- [ ] **Step 5: Run everything**

```bash
cd apps/web && pnpm typecheck && pnpm build && pnpm test
cd .. && docker compose up -d --build --wait web && pnpm test:e2e
```

Expected: all pass, including scenarios 1-13, which exercise these menus through `openRowMenu` on every path.

Two e2e repairs are expected here, and both are consequences of the portal rather than accidents:

- **`e2e/mouse.spec.ts:203`** locates the brand menu as `.brand .menu-popover` — a descendant selector. Radix portals the content to `document.body`, so it is no longer inside `.brand` and this stops matching. Rescope it to the portalled popover: locate it by its role and name and work down from there, rather than through `.brand`. The assertions that follow at `:212`, `:216-217` and `:239` are invariant 6 and must keep passing unchanged — they are the reason the nested `role="menu"` in Step 2 exists.
- **`openRowMenu` (`e2e/support.ts:199-204`)** asserts `getByRole("menu", { name: "Actions for ${name}" })`. Step 2 keeps `aria-label` on the nested `role="menu"` precisely so this helper keeps working. If it needs changing anyway, that is a real finding — put it in the commit message rather than editing it silently.

Every scenario from 1 to 15 goes through `openRowMenu`, so a helper that quietly matches the wrong element would turn a broad regression into a green suite.

- [ ] **Step 6: Commit**

```bash
git add apps/web/src/components/menu.tsx apps/web/src/components/pills.tsx \
        apps/web/src/app/globals.css e2e/support.ts
git commit -m "refactor(web): the dropdown moves to Radix behind its own signature

All six callers untouched: the exported Menu keeps its shape, and the empty-list
rule that hides a ⋯ from a member with no actions moves with it.

Three behaviours are not Radix defaults and are kept by hand — the popover
stopping keys from reaching page.tsx's window listener chief among them.
Scenario 14 is the gate.

With asChild the pill genuinely is its own trigger, which retires the 35 lines of
display:contents and absolute positioning that used to fake it.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>"
```

---

## What this plan does not cover

Spec steps 4 to 10 — overlays and dialogs, the command palette, the shell and list, settings, onboarding, the timeline's retokenisation, and the final CSS deletion — are **Plan 2**, written once this plan lands.

That split is deliberate rather than tidy-minded. Every one of those surfaces has to make the same three decisions this plan settles by doing: where Radix puts `role="menu"` and whether a header can live beside it, whether `onCloseAutoFocus` is enough to hold the focus contract, and what preflight broke that a hand-written rule was relying on. Writing precise steps for seven surfaces against unverified answers would mean inventing detail, and detail invented in a plan is worse than a gap — it reads as decided.

Two things the spec asks for that this plan deliberately defers rather than skips:

- **Invariant 8** — the `stopPropagation` on text fields that keeps typing from firing global shortcuts (`overlays.tsx:69`, `:207`). Untested here because it belongs to the overlays, which this plan does not touch. It becomes the first assertion of Plan 2's characterisation test, written before `overlays.tsx` moves to `Dialog`.
- **`color-scheme` restored per theme.** The spec says class-based dark mode costs the automatic `color-scheme` and that it is restored explicitly. Not yet: the legacy palette resolves `light-dark()` *off* `color-scheme`, which `[data-theme]` narrows (`globals.css:63-69`), so redeclaring it now would repaint every unmigrated surface. The legacy mechanism stays in charge while both systems coexist, and `color-scheme` moves onto `.dark` in Plan 2's final cleanup, once nothing reads `light-dark()` any more.

Two open questions to settle before Plan 2 is written, both already flagged to the user:

- **Native `<select>` versus Radix `Select`.** The composer's context bar has four that wrap when space runs short (`globals.css:726`), and a native `<select>` opens the OS picker on mobile where a Radix `Select` opens a popover. A behaviour change, not a styling one.
- **`data-testid` versus role-based selectors.** `follow-ups.md:55` wants the e2e suite off private CSS classes. This plan leaves the 17 selectors alone — `.row`, `.nav-item` and `.menu-popover` are still load-bearing in scenarios 1-15 — and Plan 2 replaces each as its surface migrates. Whether the replacement is `data-testid` or an accessible role is worth deciding once rather than seventeen times.
