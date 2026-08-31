import { TICKET_PRIORITIES, TICKET_STATUSES, PROJECT_STATUSES } from "@/lib/api";
import { PRIORITY_LABELS, PROJECT_STATUS_LABELS, STATUS_LABELS } from "@/lib/status";
import type { ImportMapping, ImportTarget } from "./import-map";

/**
 * Screen 24's third step: what the columns screen derives, and nothing else.
 *
 * The pre-fill is not here. It is computed once, server side, in `ImportSchema.of`, and
 * travels with the schema — the plan says so, because two implementations of "what looks
 * like the status column" are two chances for the guess to drift, and the browser needs
 * the per-field candidates anyway to offer the choice when the guess is wrong. So this
 * module reads a schema and a mapping and answers four questions the screen asks: how many
 * fields have an answer, which options are still falling on a Kanso default, which
 * unmapped base a mapped relation is pointing at, and which fallback the reader still owes
 * an answer for.
 */

/** One Notion property, as the schema endpoint describes it. */
export type NotionSchemaColumn = { name: string; type: string; options: string[]; relationTo?: string };

export type NotionImportSchema = {
  sourceId: string;
  target: ImportTarget;
  columns: NotionSchemaColumn[];
  /** The fields this target reads, each with the columns whose type could fill it. */
  fields: { field: string; candidates: string[] }[];
  /** Kanso's first guess: field → property, and per field, option → Kanso value. */
  suggestion: BaseMapping;
  /** What a field falls back to when nothing fills it. Null where there is no default. */
  defaults: Record<string, string | null>;
};

/** One base's answer, as the request carries it. Keyed by the field's wire string. */
export type BaseMapping = { columns: Record<string, string>; values: Record<string, Record<string, string>> };

/** Where a row lands when no relation answers. All three optional; all three per base. */
export type Fallback = { teamId?: string; parentTeamId?: string; projectId?: string };

export const EMPTY_MAPPING: BaseMapping = { columns: {}, values: {} };

/**
 * The number in a section's header: how many of this target's fields have been answered.
 *
 * Counted over [NotionImportSchema.fields] rather than over the mapping's own keys, so a
 * mapping naming a field this target does not read — a stale one, or one carried over from
 * a target the reader changed their mind about — cannot inflate it.
 */
export function answeredFields(schema: NotionImportSchema, mapping: BaseMapping): number {
  return schema.fields.filter(({ field }) => (mapping.columns[field] ?? "") !== "").length;
}

/**
 * The options of [field]'s mapped column that no Kanso value has been chosen for.
 *
 * Named rather than counted wherever this is drawn: a number tells the reader something
 * was guessed, a list tells them what. An option set to the empty string is one of these —
 * choosing "— default —" is a decision to let the writer's default stand, and the sentence
 * has to keep saying which words that covers.
 */
export function unmappedOptions(
  schema: NotionImportSchema,
  mapping: BaseMapping,
  field: string,
): string[] {
  const property = mapping.columns[field];
  if (!property) return [];
  const column = schema.columns.find((candidate) => candidate.name === property);
  const chosen = mapping.values[field] ?? {};
  return (column?.options ?? []).filter((option) => (chosen[option] ?? "") === "");
}

/**
 * The target implied by a relation field, which is what makes "import the base this points
 * at" a suggestion rather than a question.
 *
 * `blockedBy` points at the base's own kind and `projects`/`subTeams`/`tickets` read the
 * parent's own column naming its children — the direction differs, the target does not.
 * A field absent from here has no relation to follow.
 */
const RELATION_TARGET: Record<string, Exclude<ImportTarget, "ignore">> = {
  project: "projects",
  projects: "projects",
  team: "teams",
  parentTeam: "teams",
  subTeams: "teams",
  tickets: "tickets",
  blockedBy: "tickets",
};

const isKept = (kept: ImportMapping, sourceId: string) =>
  kept[sourceId] !== undefined && kept[sourceId] !== "ignore";

/**
 * The bases a mapped relation points at that nobody is importing — step 2's suggestion,
 * and the warning beside it.
 *
 * Read from [NotionImportSchema.suggestion], because on the step where this is drawn the
 * server's guess is the only mapping there is; a caller that already has the reader's own
 * mapping substitutes it there, and gets the answer for what the reader actually said.
 *
 * It never blocks. A relation into an ignored base is a fact about the plan, not a
 * mistake: the import proceeds, the row lands in its fallback, and the relation is counted
 * as dropped.
 */
export function suggestionsFrom(
  schema: NotionImportSchema,
  kept: ImportMapping,
): { sourceId: string; target: Exclude<ImportTarget, "ignore"> }[] {
  const suggestions: { sourceId: string; target: Exclude<ImportTarget, "ignore"> }[] = [];

  for (const [field, property] of Object.entries(schema.suggestion.columns)) {
    const target = RELATION_TARGET[field];
    const sourceId = schema.columns.find((column) => column.name === property)?.relationTo;
    if (!target || !sourceId) continue;
    if (isKept(kept, sourceId)) continue;
    if (suggestions.some((suggestion) => suggestion.sourceId === sourceId)) continue;
    suggestions.push({ sourceId, target });
  }

  return suggestions;
}

/**
 * Which of the three fallbacks this base still owes an answer for.
 *
 * A link is resolvable when its column is mapped *and* points at a base being imported:
 * a relation into an ignored base resolves to nothing, which is the case the fallback
 * exists for. Read from the writers rather than from a spec — `TeamImport` reads
 * `parentTeamId`, `ProjectImport` reads `teamId`, `TicketImport` reads `projectId` and
 * then `teamId` for a ticket whose project answered nothing, and `DocumentImport` reads
 * `teamId` with no relation to try first.
 */
export function openFallbacks(
  schema: NotionImportSchema,
  mapping: BaseMapping,
  kept: ImportMapping,
): (keyof Fallback)[] {
  const resolves = (field: string) => {
    const property = mapping.columns[field];
    const relationTo = schema.columns.find((column) => column.name === property)?.relationTo;
    return relationTo !== undefined && isKept(kept, relationTo);
  };

  switch (schema.target) {
    case "teams":
      return resolves("parentTeam") ? [] : ["parentTeamId"];
    case "projects":
      return resolves("team") ? [] : ["teamId"];
    case "tickets":
      // The ticket's team follows its project, so an unanswered project leaves both open.
      return resolves("project") ? [] : ["projectId", "teamId"];
    default:
      return ["teamId"];
  }
}

/** How the three fallbacks are asked for, in the reader's words. */
export const FALLBACK_LABELS: Record<keyof Fallback, string> = {
  teamId: "Unlinked rows land in team",
  parentTeamId: "Teams with no parent land under",
  projectId: "Tickets with no project land in",
};

/**
 * The Kanso values a select-backed field can be mapped onto, or none for a field with no
 * closed vocabulary — a date, a relation, a person or a description has nothing to offer
 * here. `status` reads a different vocabulary per target: a base of tickets and a base of
 * projects share one column name and not one set of words, the same split
 * `ImportSchema.vocabulary` makes server side.
 */
export function kansoValues(field: string, target: ImportTarget): { value: string; label: string }[] {
  if (field === "priority") {
    return TICKET_PRIORITIES.map((value) => ({ value, label: PRIORITY_LABELS[value] }));
  }
  if (field === "status" && target === "tickets") {
    return TICKET_STATUSES.map((value) => ({ value, label: STATUS_LABELS[value] }));
  }
  if (field === "status" && target === "projects") {
    return PROJECT_STATUSES.map((value) => ({ value, label: PROJECT_STATUS_LABELS[value] }));
  }
  return [];
}
