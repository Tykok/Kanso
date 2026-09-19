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
  peopleTotal,
}: {
  mappings: Record<string, BaseMapping>;
  people: Record<string, string | null>;
  /**
   * Every row `StepPeople` drew — `rows.length`, reported straight from there rather than
   * read off `people`'s own keys. `buildAssignments` omits a row with neither an edit nor
   * a confirmed link, which on a workspace Kanso has never matched before is every row —
   * so counting `people`'s keys as the denominator is what let this lid say "N fields
   * guessed" and never mention a single unmatched person on exactly the import this fold
   * exists to warn about.
   */
  peopleTotal: number;
}): string {
  const fields = Object.values(mappings).reduce(
    (sum, mapping) => sum + Object.keys(mapping.columns).length,
    0,
  );
  const matched = Object.values(people).filter((id) => id !== null).length;
  const unmatched = peopleTotal - matched;

  const parts: string[] = [];
  if (fields > 0) parts.push(`${fields} ${fields === 1 ? "field" : "fields"} guessed`);
  if (peopleTotal > 0) {
    parts.push(`${peopleTotal} ${peopleTotal === 1 ? "person" : "people"} met`);
    parts.push(`${matched} matched`);
    if (unmatched > 0) parts.push(`${unmatched} unmatched`);
  }

  return parts.length > 0 ? parts.join(" · ") : "nothing mapped yet";
}
