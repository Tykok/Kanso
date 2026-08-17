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

/**
 * Tokens the browser defines, a component scopes to itself, or that come from
 * outside every CSS file entirely. `--font-public-sans` and `--font-noto-sans-jp`
 * are the last kind: next/font and an inline `style` on `<html>` supply them
 * (`app/layout.tsx`), so no `--name:` declaration for either exists in any
 * stylesheet for this test to find on its own. Without this entry both would read
 * as missing the moment `tokens.css` and `app/globals.css` joined the walk below —
 * which is exactly the check that would have caught `layout.tsx` losing
 * `sealFontStyle`: with `--font-noto-sans-jp` undefined, `--font-seal` in
 * `tokens.css` resolves to nothing, and the seal falls back to a system font.
 */
const NOT_OURS = new Set([
  "--radix-popper-available-height",
  "--radix-popper-anchor-width",
  "--font-public-sans",
  "--font-noto-sans-jp",
]);

describe("design tokens", () => {
  const tokens = readFileSync(join(SRC, "styles/tokens.css"), "utf8");
  // Two sources of truth: tokens.css for the current names, and the alias/literal
  // block at the top of globals.css for the pre-migration names some surfaces still
  // read directly (see the comment there) — task 5's own migration, not tasks 6-8's,
  // and the block outlives them: `.button`, the setup wizard and the settings pages
  // are still unmigrated (tracked as a C3 follow-up), so it is not yet empty.
  // `defined` is every `--name:` declaration in either file, not only that block —
  // `--row-h` in tokens.css counts the same as `--gutter` in the alias block below.
  const globals = readFileSync(join(SRC, "app/globals.css"), "utf8");
  const defined = new Set(
    Array.from((tokens + globals).matchAll(/^\s*(--[\w-]+):/gm), (m) => m[1]),
  );

  it("defines every token the interface reads", () => {
    // tokens.css and globals.css are in the walk, not exempt from it: a `var()`
    // read inside either file is exactly as real as one in a component, and this
    // is the test that would have caught tokens.css reaching for
    // `--font-noto-sans-jp` before `NOT_OURS` named where it actually comes from.
    const files = walk(SRC).filter((f) => /\.(tsx?|css)$/.test(f));
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

  it("keeps the five drawn statuses on one lightness plane, per colour scheme", () => {
    // `.dark` restates all five with its own lightnesses — a second, independent
    // plane — so each scheme is checked against its own bound rather than pooling
    // both into one comparison that would mix them.
    const darkStart = tokens.indexOf("\n.dark {");
    const darkEnd = tokens.indexOf("/* Six accents", darkStart);
    const light = tokens.slice(0, darkStart);
    const dark = tokens.slice(darkStart, darkEnd);

    const planeOf = (scope: string) =>
      Array.from(
        scope.matchAll(/--status-(?:backlog|todo|progress|review|done): oklch\(([\d.]+)/g),
        (m) => Number(m[1]),
      );
    // Rounded: binary floats put 0.68 - 0.52 a shade over 0.16 (0.16000000000000003),
    // which is not a lightness difference anyone drew.
    const spreadOf = (plane: number[]) =>
      Math.round((Math.max(...plane) - Math.min(...plane)) * 1000) / 1000;

    const lightPlane = planeOf(light);
    const darkPlane = planeOf(dark);
    expect(lightPlane).toHaveLength(5);
    expect(darkPlane).toHaveLength(5);

    expect(spreadOf(lightPlane)).toBeLessThanOrEqual(0.16);
    // Dark's own bound is wider than light's: the bundle drew dark's five statuses
    // across a wider band, measured off the values as given rather than chosen to
    // make them fit. Widening this further because a new colour trips it is a
    // question for the design bundle, not a reason to loosen the guard.
    expect(spreadOf(darkPlane)).toBeLessThanOrEqual(0.2);
  });
});
