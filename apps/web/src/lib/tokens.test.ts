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
const NOT_OURS = new Set([
  "--radix-popper-available-height",
  "--radix-popper-anchor-width",
  // Task 5 of the design-system rollout repointed every CSS rule in globals.css and
  // its split files (shell.css, list.css, surfaces.css, settings.css) — plus
  // timeline.css and setup.css — from Kanso's pre-migration prefixed palette names
  // onto their tokens.css equivalents, and deleted the block that defined the old
  // names. What's left below is not a renaming gap: these have no tokens.css equivalent at all
  // (--gutter/--bar-pad*/--nav-pad/--nav-gap, the shell/topbar/statusbar's own
  // spacing) or are read from inline styles in components that haven't moved to CSS
  // classes yet (pills.tsx, brand-logo.tsx, overlays.tsx, login.tsx) or, for
  // --row-height, from a runtime `getComputedStyle` read in
  // components/timeline/arrows.tsx. They die with the surface that reads them, in
  // tasks 6-8, rather than moving here.
  "--bar-pad",
  "--bar-pad-sm",
  "--gutter",
  "--high",
  "--low",
  "--medium",
  "--mono",
  "--nav-gap",
  "--nav-pad",
  "--row-height",
  "--text",
  "--text-dim",
  "--text-faint",
]);

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
