import type { BaseMapping } from "./import-columns";

/**
 * What the folded panel says about itself, so that folding it costs nothing.
 *
 * Pure and its own module for the reason `import-targets.ts` gives: this is the part that
 * can be wrong in a way nobody notices. Singular and plural are spelled out rather than
 * bolted on with an `s`, because "1 fields guessed" on a one-base workspace is exactly the
 * kind of sloppiness that makes a reader stop trusting the numbers beside it.
 */
export function detailsSummary({
  mappings,
  people,
}: {
  mappings: Record<string, BaseMapping>;
  people: Record<string, string | null>;
}): string {
  const fields = Object.values(mappings).reduce(
    (sum, mapping) => sum + Object.keys(mapping.columns).length,
    0,
  );
  const entries = Object.values(people);
  const matched = entries.filter((id) => id !== null).length;
  const unmatched = entries.length - matched;

  const parts: string[] = [];
  if (fields > 0) parts.push(`${fields} ${fields === 1 ? "field" : "fields"} guessed`);
  if (entries.length > 0) {
    parts.push(`${matched} ${matched === 1 ? "person" : "people"} matched`);
    if (unmatched > 0) parts.push(`${unmatched} unmatched`);
  }

  return parts.length > 0 ? parts.join(" · ") : "nothing mapped yet";
}
