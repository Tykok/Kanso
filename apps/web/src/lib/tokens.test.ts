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
  // Kanso's pre-migration `--k-*` palette (globals.css) and its neighbours: still
  // read by the surfaces that have not moved to this token layer yet (settings,
  // setup, timeline, pills, the sidebar brand, status colour lookups). They die
  // with the stylesheet that defines them, in tasks 6-8, rather than moving here.
  "--bar-pad-sm",
  "--bg",
  "--gutter",
  "--high",
  "--k-accent",
  "--k-accent-contrast",
  "--k-accent-soft",
  "--k-border",
  "--k-radius",
  "--low",
  "--medium",
  "--mono",
  "--row-height",
  "--surface",
  "--surface-hover",
  "--text",
  "--text-dim",
  "--text-faint",
  "--warn",
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

  it("keeps the five drawn statuses on one lightness plane", () => {
    // `:root` only: `.dark` restates all five with its own lightnesses, and matching
    // the whole file would compare across colour schemes rather than within one.
    const light = tokens.slice(0, tokens.indexOf("\n.dark"));
    const plane = Array.from(
      light.matchAll(/--status-(?:backlog|todo|progress|review|done): oklch\(([\d.]+)/g),
      (m) => Number(m[1]),
    );
    expect(plane).toHaveLength(5);
    // Rounded: binary floats put 0.68 - 0.52 a shade over 0.16 (0.16000000000000003),
    // which is not a lightness difference anyone drew.
    const spread = Math.round((Math.max(...plane) - Math.min(...plane)) * 1000) / 1000;
    expect(spread).toBeLessThanOrEqual(0.16);
  });
});
