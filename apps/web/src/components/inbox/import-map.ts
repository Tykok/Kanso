/**
 * Screen 24's plan: what each Notion database becomes.
 *
 * The arithmetic is here, on its own, because it is the only part of the import that
 * has to be right before anything is written. "396 pages kept out of 1287" is what the
 * reader confirms against, and a count that quietly disagrees with the mapping is how
 * somebody imports an eight-hundred-page archive they had marked as ignored.
 */

/** A database in the workspace, as the discovery step found it. */
export type NotionSource = {
  id: string;
  name: string;
  /** How many pages it holds. Counted by the server, not estimated here. */
  pages: number;
};

export type ImportTarget = "teams" | "projects" | "tickets" | "documents" | "ignore";

/**
 * The fields of a Kanso row a Notion column can be mapped onto — `ImportField`'s fifteen
 * wire strings, spelled out here rather than left as `string`.
 *
 * A closed set on the server has to be a closed set here too, for the reason
 * [ImportTarget] is one: `FIELD_LABELS` is keyed by these, and a `Record<string, string>`
 * would let a sixteenth field ship with no label and render as its raw wire word with
 * nothing failing.
 */
export type ImportField =
  | "status"
  | "priority"
  | "description"
  | "start"
  | "due"
  | "end"
  | "assignees"
  | "lead"
  | "project"
  | "team"
  | "parentTeam"
  | "blockedBy"
  | "tickets"
  | "projects"
  | "subTeams";

/** Source id → what it becomes. Absent means [DEFAULT_TARGET]. */
export type ImportMapping = Record<string, ImportTarget>;

/**
 * Nothing is imported unless it is asked for.
 *
 * The other default — "documents, unless you say otherwise" — makes the confirm button
 * write every page in the workspace for anyone who clicks through, which is the one
 * outcome an import dialog exists to prevent. It also makes the counter useful: it
 * starts at zero and only ever goes up as decisions are made.
 */
export const DEFAULT_TARGET: ImportTarget = "ignore";

export type ImportCounts = {
  /**
   * Pages in the bases the reader kept — the plan screen's number, and the plan screen's
   * only.
   *
   * Not the number beside the confirm button. This is the *discovery* count, so it still
   * holds the pages a row already exists for and the pages that will be refused, neither of
   * which is known before the preview. The confirmation sums the preview's own groups
   * instead; the plan screen has no preview and this is the honest ceiling until it does.
   */
  kept: number;
  /** Pages in the workspace, mapped or not. The denominator. */
  total: number;
  teams: number;
  projects: number;
  tickets: number;
  folders: number;
  ignored: number;
};

export const targetOf = (mapping: ImportMapping, source: NotionSource): ImportTarget =>
  mapping[source.id] ?? DEFAULT_TARGET;

/**
 * Derived from [sources], never from [mapping]'s keys: the workspace is searched again
 * between the steps, so the mapping can name a database that has since been deleted,
 * and counting that would put a number on screen with nothing behind it.
 */
export function importCounts(sources: NotionSource[], mapping: ImportMapping): ImportCounts {
  const counts: ImportCounts = { kept: 0, total: 0, teams: 0, projects: 0, tickets: 0, folders: 0, ignored: 0 };

  for (const source of sources) {
    counts.total += source.pages;
    switch (targetOf(mapping, source)) {
      case "teams":
        counts.kept += source.pages;
        counts.teams++;
        break;
      case "projects":
        counts.kept += source.pages;
        counts.projects++;
        break;
      case "tickets":
        counts.kept += source.pages;
        counts.tickets++;
        break;
      case "documents":
        counts.kept += source.pages;
        counts.folders++;
        break;
      case "ignore":
        counts.ignored++;
        break;
    }
  }

  return counts;
}

export type ImportPlanEntry = {
  sourceId: string;
  name: string;
  target: Exclude<ImportTarget, "ignore">;
  pages: number;
};

/**
 * What gets sent when the preview is confirmed: only the databases being written.
 *
 * An ignored database is absent rather than present with `target: "ignore"`. The
 * request is the instruction, and an instruction listing things not to do is one more
 * thing a server has to be trusted to read correctly.
 */
export function importPlan(sources: NotionSource[], mapping: ImportMapping): ImportPlanEntry[] {
  return sources.flatMap((source) => {
    const target = targetOf(mapping, source);
    return target === "ignore" ? [] : [{ sourceId: source.id, name: source.name, target, pages: source.pages }];
  });
}
