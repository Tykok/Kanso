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

// Finds the `{...}` that starts at `openIndex` (which must point at the `{`
// itself) by counting brace depth rather than a non-greedy regex, so a `}`
// that closes a template-literal interpolation inside the object's values
// (`${...}`) doesn't get mistaken for the object's own end: it's one open
// brace and one close brace either way, so a running count still lands on
// zero at the real close.
function balancedBraces(source: string, openIndex: number): string {
  let depth = 0;
  for (let i = openIndex; i < source.length; i++) {
    if (source[i] === "{") depth++;
    else if (source[i] === "}" && --depth === 0) return source.slice(openIndex, i + 1);
  }
  return "";
}

/**
 * A .tsx file can hand a custom property to the cascade without ever writing
 * `--name:` in a stylesheet. Two idioms cover every case this codebase uses:
 *  - next/font's `variable` option, which generates a class that sets the
 *    name (`app/layout.tsx`'s `Public_Sans({ variable: "--font-public-sans" })`).
 *  - an object assigned to a `style={}` JSX attribute (that same file's
 *    `sealFontStyle`; `timeline/view.tsx`'s `chart`).
 * The second idiom only counts a name if the object holding it is actually
 * wired to a `style={}` somewhere in the file — an object that still declares
 * `"--font-noto-sans-jp"` but whose `style={sealFontStyle}` got deleted must
 * not count, or this test couldn't tell "the seal has a font" from "the seal
 * used to have a font", which is the exact gap this function exists to close.
 */
function tsxDefinitions(source: string): Set<string> {
  const defined = new Set<string>();
  for (const [, name] of source.matchAll(/variable:\s*["'](--[\w-]+)["']/g)) {
    defined.add(name);
  }
  const wired = new Set(Array.from(source.matchAll(/style=\{(\w+)\}/g), (m) => m[1]));
  for (const m of source.matchAll(/const (\w+)\s*=\s*(\{)/g)) {
    if (!wired.has(m[1])) continue;
    const body = balancedBraces(source, m.index + m[0].length - 1);
    for (const [, name] of body.matchAll(/["'](--[\w-]+)["']\s*:/g)) {
      defined.add(name);
    }
  }
  return defined;
}

/** Tokens the browser defines itself; nothing in this codebase ever will. */
const NOT_OURS = new Set([
  "--radix-popper-available-height",
  "--radix-popper-anchor-width",
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
  const files = walk(SRC).filter((f) => /\.(tsx?|css)$/.test(f));
  const defined = new Set(
    Array.from((tokens + globals).matchAll(/^\s*(--[\w-]+):/gm), (m) => m[1]),
  );
  // See `tsxDefinitions` above: a .tsx file can define a token without any
  // `--name:` at all, via next/font's `variable` option or a `style={}`-wired
  // object. Counting those here means deleting one is exactly as visible to
  // this test as deleting a CSS declaration would be.
  for (const file of files.filter((f) => f.endsWith(".tsx"))) {
    for (const name of tsxDefinitions(readFileSync(file, "utf8"))) {
      defined.add(name);
    }
  }

  it("defines every token the interface reads", () => {
    // tokens.css and globals.css are in the walk, not exempt from it: a `var()`
    // read inside either file is exactly as real as one in a component.
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
