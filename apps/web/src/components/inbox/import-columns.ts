import { TICKET_PRIORITIES, DEFAULT_STATUSES, PROJECT_STATUSES } from "@/lib/api";
import { PRIORITY_LABELS, PROJECT_STATUS_LABELS, STATUS_LABELS } from "@/lib/status";
import type { ImportField, ImportMapping, ImportTarget } from "./import-map";

/**
 * Screen 24's folded columns panel: what the columns screen derives, and nothing else.
 *
 * The pre-fill is not here. It is computed once, server side, in `ImportSchema.of`, and
 * travels with the schema — the plan says so, because two implementations of "what looks
 * like the status column" are two chances for the guess to drift, and the browser needs
 * the per-field candidates anyway to offer the choice when the guess is wrong. So this
 * module reads a schema and a mapping and answers what the screen asks: how many fields
 * have an answer, what the mapping becomes when a column is picked, what one option will
 * become, which options land on the field's own default, which unmapped base a mapped
 * relation is pointing at, and which fallback the reader still owes an answer for.
 *
 * Every one of those is *applied* to the pre-fill rather than a second computation of it:
 * where an option's own label means something, the server said so and this reads it back.
 */

/** One Notion property, as the schema endpoint describes it. */
export type NotionSchemaColumn = { name: string; type: string; options: string[]; relationTo?: string };

export type NotionImportSchema = {
  sourceId: string;
  target: ImportTarget;
  columns: NotionSchemaColumn[];
  /**
   * The fields this target reads, each with the columns whose type could fill it and, per
   * candidate column, the option table Kanso would fill in if that column were chosen.
   *
   * `prefill` is the server's, for every candidate rather than only the suggested one:
   * `MappedPageReader` still matches an option's label where the mapping says nothing, so a
   * column nothing suggested — a status column called `Stage` — has to arrive with what its
   * own labels mean, or this screen has no way to say what picking it does.
   */
  fields: { field: ImportField; candidates: string[]; prefill: Record<string, Record<string, string>> }[];
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

/** What [field]'s mapped column's own labels mean, per the server. Empty where nothing does. */
const prefillFor = (schema: NotionImportSchema, field: ImportField, property: string) =>
  schema.fields.find((entry) => entry.field === field)?.prefill[property] ?? {};

/**
 * The mapping after the reader picks [property] for [field], with that column's option
 * table seeded from the server's pre-fill.
 *
 * Seeded on *every* pick, not only on the schema's first arrival. Before this, choosing a
 * column by hand deleted `values[field]` and left it deleted, so a status column called
 * `Stage` was drawn with every option on "— default —" — and re-picking the very column the
 * suggestion had filled in threw that table away too, since the shell refuses a second
 * seed by design. The table belongs to the column, so it is replaced with the column
 * rather than emptied.
 */
export function withColumn(
  schema: NotionImportSchema,
  mapping: BaseMapping,
  field: ImportField,
  property: string,
): BaseMapping {
  const columns = { ...mapping.columns };
  const values = { ...mapping.values };
  if (property) {
    columns[field] = property;
    values[field] = { ...prefillFor(schema, field, property) };
  } else {
    delete columns[field];
    delete values[field];
  }
  return { columns, values };
}

/**
 * The Kanso value [option] takes when the reader leaves it on "— default —", or null when
 * there is none to name.
 *
 * Not always the *field's* default, which is why this exists: `MappedPageReader` matches an
 * option's own label where the mapping says nothing, so `Done` left unmapped becomes `Done`
 * and not `Todo`. The screen prints this rather than the field default so that the words in
 * front of the reader are what the writer will do — including after they deliberately clear
 * an option back to "— default —", which was the direction that misled in reverse.
 *
 * The label match is the server's answer, read out of [NotionImportSchema.fields], never
 * recomputed here: one authority for "what does this label mean", on the side that also
 * applies it.
 */
export function optionDefault(
  schema: NotionImportSchema,
  mapping: BaseMapping,
  field: ImportField,
  option: string,
): string | null {
  const property = mapping.columns[field];
  const matched = property ? prefillFor(schema, field, property)[option] : undefined;
  return matched ?? schema.defaults[field] ?? null;
}

/**
 * The options of [field]'s mapped column that will take the *field's* default — nothing
 * chose a value for them and their own label lands on none either.
 *
 * Named rather than counted wherever this is drawn: a number tells the reader something
 * was guessed, a list tells them what. An option whose label the server matches is not one
 * of these however empty its select looks: it becomes that value, and listing it here
 * beside "become the default — Todo" is precisely the sentence that was false.
 */
export function defaultedOptions(
  schema: NotionImportSchema,
  mapping: BaseMapping,
  field: ImportField,
): string[] {
  const property = mapping.columns[field];
  if (!property) return [];
  const column = schema.columns.find((candidate) => candidate.name === property);
  const chosen = mapping.values[field] ?? {};
  const prefill = prefillFor(schema, field, property);
  return (column?.options ?? []).filter(
    (option) => (chosen[option] ?? "") === "" && !prefill[option],
  );
}

/**
 * The target a relation's other end has to be kept as for that relation to resolve.
 *
 * `ImportLinks` keys the ends of a link by target — `adopted[parentTarget]` for a child's
 * own column, `adopted[childTarget]` for a parent's inverse one — so "is that base being
 * imported" is the wrong question and "is it being imported as *this*" is the right one. A
 * `project` relation pointing at a base kept as tickets resolves to nothing and is counted
 * as dropped, exactly as one pointing at an ignored base is.
 *
 * `blockedBy` points at the base's own kind, and `projects`/`subTeams`/`tickets` are the
 * parent's own column naming its children — the direction differs, the answer to "kept as
 * what" does not. A field absent from here has no relation to follow.
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

/**
 * The three one-to-one links, each named from the child's side, with the parent's inverse
 * column beside it — the same six lines `ImportLinks.resolve` is built out of.
 *
 * `blockedBy` is not here: it has no inverse column (the mirror only ever writes one side)
 * and it places no row, so no fallback exists for it to be the question about.
 */
const LINK: Partial<
  Record<ImportTarget, { childField: string; parentTarget: ImportTarget; inverseField: string }>
> = {
  teams: { childField: "parentTeam", parentTarget: "teams", inverseField: "subTeams" },
  projects: { childField: "team", parentTarget: "teams", inverseField: "projects" },
  tickets: { childField: "project", parentTarget: "projects", inverseField: "tickets" },
};

/** One kept base as the screen holds it: what its columns are, and what has been said about them. */
export type MappedBase = { schema: NotionImportSchema; mapping: BaseMapping };

/** Which data source [field]'s mapped column points at, or undefined if nothing is mapped. */
const pointsAt = (base: MappedBase, field: string): string | undefined => {
  const property = base.mapping.columns[field];
  return base.schema.columns.find((column) => column.name === property)?.relationTo;
};

/**
 * The bases a mapped relation points at that nothing is importing as the kind that
 * relation needs — the plan screen's suggestion, and the warning beside it.
 *
 * Read from [NotionImportSchema.suggestion], because on the plan screen where this is
 * drawn the server's guess is the only mapping there is; a caller that already has the
 * reader's own mapping substitutes it there, and gets the answer for what the reader
 * actually said.
 *
 * It never blocks. A relation whose other end is not being imported as the right kind is a
 * fact about the plan, not a mistake: the import proceeds, the row lands in its fallback,
 * and the relation is counted as dropped.
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
    if (kept[sourceId] === target) continue;
    if (suggestions.some((suggestion) => suggestion.sourceId === sourceId)) continue;
    suggestions.push({ sourceId, target });
  }

  return suggestions;
}

/**
 * Which of the three fallbacks this base still owes an answer for.
 *
 * A link resolves when *either* end names the other, because that is what
 * `ImportLinks.resolveOneToOne` reads: the child's own column, and the parent's inverse
 * column naming it back. A Notion relation created `single_property` exists on one side
 * only, so a workspace carrying the project link as a `Tâches` column on the projects base
 * and nothing on the tasks base resolves perfectly well — which is why this needs [bases],
 * the sibling mappings, and cannot answer from one base alone.
 *
 * Which fallback belongs to which target is read from the writers: `TeamImport` reads
 * `parentTeamId`, `ProjectImport` reads `teamId`, `TicketImport` reads `projectId` and then
 * `teamId` for a ticket whose project answered nothing, and `DocumentImport` reads `teamId`
 * with no relation to try first.
 *
 * It answers per base, not per page: a mapped column that is empty on some page still
 * leaves that row for the fallback, and no schema can say which pages those are. So this is
 * "can this link resolve at all", which is the question the screen is asking.
 */
export function openFallbacks(
  base: MappedBase,
  kept: ImportMapping,
  bases: MappedBase[],
): (keyof Fallback)[] {
  const link = LINK[base.schema.target];

  const resolves = (): boolean => {
    if (!link) return false;
    // The child's own column, pointing at a base kept as the parent kind.
    const childEnd = pointsAt(base, link.childField);
    if (childEnd !== undefined && kept[childEnd] === link.parentTarget) return true;
    // Or a parent's inverse column, pointing back at this base.
    return bases.some(
      (other) =>
        kept[other.schema.sourceId] === link.parentTarget &&
        pointsAt(other, link.inverseField) === base.schema.sourceId,
    );
  };

  switch (base.schema.target) {
    case "teams":
      return resolves() ? [] : ["parentTeamId"];
    case "projects":
      return resolves() ? [] : ["teamId"];
    case "tickets":
      // The ticket's team follows its project, so an unanswered project leaves both open.
      return resolves() ? [] : ["projectId", "teamId"];
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
export function kansoValues(field: ImportField, target: ImportTarget): { value: string; label: string }[] {
  if (field === "priority") {
    return TICKET_PRIORITIES.map((value) => ({ value, label: PRIORITY_LABELS[value] }));
  }
  if (field === "status" && target === "tickets") {
    return DEFAULT_STATUSES.map((value) => ({ value, label: STATUS_LABELS[value] }));
  }
  if (field === "status" && target === "projects") {
    return PROJECT_STATUSES.map((value) => ({ value, label: PROJECT_STATUS_LABELS[value] }));
  }
  return [];
}
