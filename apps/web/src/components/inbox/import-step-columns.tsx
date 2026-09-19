"use client";

import { useEffect } from "react";
import { useQueries, type UseQueryResult } from "@tanstack/react-query";
import { type Project, type Team } from "@/lib/api";
import { importSchemaQuery } from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import {
  EMPTY_MAPPING,
  FALLBACK_LABELS,
  answeredFields,
  defaultedOptions,
  kansoValues,
  openFallbacks,
  optionDefault,
  withColumn,
  type BaseMapping,
  type Fallback,
  type MappedBase,
  type NotionImportSchema,
} from "./import-columns";
import type { ImportField, ImportMapping, ImportPlanEntry } from "./import-map";
import { FIELD_LABELS, TARGET_LABELS } from "./import-targets";

/**
 * Which column answers which field, and what the words inside it mean.
 *
 * One half of the folded panel `import-details.tsx` builds — the other is `StepPeople` —
 * so there is no Next of its own here: the panel it lives in is not a step to leave, only
 * one to unfold.
 *
 * One section per kept base, because three targets have three sets of fields: a base of
 * teams is asked about its parent, a base of projects about its lead and its team, a base
 * of tickets about status, priority and what blocks what. A single table of "fields" across
 * all of them would ask every base every question.
 *
 * Every select is pre-filled from the server's suggestion and every one is overridable —
 * the pre-fill is a default, not a rule. And the options that will take the field's own
 * default are *named* under the field rather than counted: a number tells the reader
 * something was guessed, a list tells them what.
 *
 * The pre-fill travels per candidate column, so picking a column by hand fills its option
 * table exactly as the suggestion would have. What each option is drawn as is what the
 * writer will do to it and nothing looser — including for an option whose own label the
 * server matches, which is the case this screen used to describe backwards.
 */
export function StepColumns({
  bases,
  kept,
  mappings,
  fallbacks,
  teams,
  projects,
  onMapping,
  onSeed,
  onFallback,
}: {
  bases: ImportPlanEntry[];
  /** What every base becomes, so a relation into an ignored one can be told apart. */
  kept: ImportMapping;
  mappings: Record<string, BaseMapping>;
  fallbacks: Record<string, Fallback>;
  teams: Team[];
  projects: Project[];
  onMapping: (sourceId: string, mapping: BaseMapping) => void;
  /** Separate from [onMapping] because the shell refuses a second seed, never an edit. */
  onSeed: (sourceId: string, seed: BaseMapping) => void;
  onFallback: (sourceId: string, fallback: Fallback) => void;
}) {
  /**
   * Every kept base's schema, asked for here rather than inside each section.
   *
   * `openFallbacks` needs the whole set rather than one at a time, because a
   * `single_property` relation can live on the parent's base alone — a base asked about its
   * own schema in isolation would have nothing there to point at.
   *
   * `useQueries`, so a base whose schema Notion refuses still fails alone: each entry keeps
   * its own status, and the section below draws it.
   */
  const schemas = useQueries({
    queries: bases.map((base) => importSchemaQuery(base.sourceId, base.target)),
  });

  /** The bases whose schema has arrived, with what has been said about each. */
  const mapped: MappedBase[] = bases.flatMap((base, index) => {
    const schema = schemas[index].data;
    return schema ? [{ schema, mapping: mappings[base.sourceId] ?? schema.suggestion }] : [];
  });

  return (
    <>
      <div className="flex flex-col gap-1.5">
        <span className="text-15 font-medium">Say which column is which</span>
        <span className="text-12 text-muted-foreground">
          Kanso has filled in what it recognised. Change anything: a workspace built by
          somebody else names its columns in its own words, and the guess is only a default.
        </span>
      </div>

      <div className="flex flex-col gap-3">
        {bases.map((base, index) => (
          <BaseSection
            key={base.sourceId}
            base={base}
            schema={schemas[index]}
            kept={kept}
            mapped={mapped}
            mapping={mappings[base.sourceId]}
            fallback={fallbacks[base.sourceId] ?? {}}
            teams={teams}
            projects={projects}
            onMapping={onMapping}
            onSeed={onSeed}
            onFallback={onFallback}
          />
        ))}
      </div>
    </>
  );
}

/**
 * One base, drawn from its own entry in the parent's [useQueries].
 *
 * One query per base rather than one for all of them: the schemas are read from Notion one
 * data source at a time, and a workspace where a single base has been unshared would
 * otherwise blank the whole screen. Here that base says so and the rest stay usable — its
 * pages still import, with nothing mapped.
 */
function BaseSection({
  base,
  schema,
  kept,
  mapped,
  mapping,
  fallback,
  teams,
  projects,
  onMapping,
  onSeed,
  onFallback,
}: {
  base: ImportPlanEntry;
  schema: UseQueryResult<NotionImportSchema>;
  kept: ImportMapping;
  /** Every base whose schema has arrived — `openFallbacks` reads both ends of a link. */
  mapped: MappedBase[];
  /** Undefined until the suggestion has seeded it — which is what `seeded` reads. */
  mapping?: BaseMapping;
  fallback: Fallback;
  teams: Team[];
  projects: Project[];
  onMapping: (sourceId: string, mapping: BaseMapping) => void;
  onSeed: (sourceId: string, seed: BaseMapping) => void;
  onFallback: (sourceId: string, fallback: Fallback) => void;
}) {
  /**
   * The suggestion seeds this base's mapping once, on first arrival, and the reader's
   * edits win from then on: `mapping` is undefined only before the seed, and every edit
   * writes a defined one back — so a reader who empties every select is not re-seeded.
   *
   * An effect rather than a derivation, because the mapping is what the request carries:
   * the shell has to hold the pre-fill to send it, not merely draw it.
   */
  const seeded = mapping !== undefined;
  useEffect(() => {
    if (schema.data && !seeded) onSeed(base.sourceId, schema.data.suggestion);
  }, [schema.data, seeded, base.sourceId, onSeed]);

  const view = schema.data;
  const current = mapping ?? view?.suggestion ?? EMPTY_MAPPING;

  /**
   * The option table travels with the column: `withColumn` seeds it from the server's
   * pre-fill for whichever column was just picked, rather than emptying it. Emptying it was
   * what drew a hand-picked `Stage` column's every option as "— default —" while the reader
   * on the server still matched their labels.
   *
   * The `view` guard is unreachable rather than defensive: the selects that call this are
   * drawn from `view.fields`, so there is no field row to change before the schema arrives.
   */
  const setColumn = (field: ImportField, property: string) => {
    if (!view) return;
    onMapping(base.sourceId, withColumn(view, current, field, property));
  };

  const setValue = (field: ImportField, option: string, value: string) => {
    const forField = { ...(current.values[field] ?? {}) };
    if (value) forField[option] = value;
    else delete forField[option];
    onMapping(base.sourceId, { columns: current.columns, values: { ...current.values, [field]: forField } });
  };

  return (
    <section className="flex flex-col gap-2 rounded-md bg-background px-3 py-3">
      <div className="flex items-center gap-2.5 text-12">
        <span className="truncate font-medium">{base.name}</span>
        <span className="text-muted-foreground">{TARGET_LABELS[base.target]}</span>
        <span className="flex-1" />
        {view && (
          <span className="text-11 text-faint">
            {answeredFields(view, current)} of {view.fields.length}{" "}
            {view.fields.length === 1 ? "field" : "fields"} mapped
          </span>
        )}
      </div>

      {schema.isLoading && <span className="text-11 text-faint">Reading its columns…</span>}

      {schema.isError && (
        <span className="text-11 text-status-progress">
          Kanso could not read this base’s columns ({actionErrorMessage(schema.error)}). Its
          pages still import, with nothing mapped.
        </span>
      )}

      {view?.fields.length === 0 && (
        <span className="text-11 text-faint">
          Nothing to map: a folder of documents keeps its page as it is written.
        </span>
      )}

      {view?.fields.map(({ field, candidates }) => (
        <FieldRow
          key={field}
          schema={view}
          mapping={current}
          field={field}
          candidates={candidates}
          onColumn={setColumn}
          onValue={setValue}
        />
      ))}

      {view &&
        openFallbacks({ schema: view, mapping: current }, kept, mapped).map((key) => (
          <label key={key} className="grid grid-cols-[170px_1fr] items-center gap-3 text-12">
            <span className="text-muted-foreground">{FALLBACK_LABELS[key]}</span>
            <select
              value={fallback[key] ?? ""}
              onChange={(event) => onFallback(base.sourceId, { ...fallback, [key]: event.target.value || undefined })}
            >
              <option value="">— {FALLBACK_NONE[key]} —</option>
              {(key === "projectId" ? projects : teams).map((row) => (
                <option key={row.id} value={row.id}>
                  {row.name}
                </option>
              ))}
            </select>
          </label>
        ))}
    </section>
  );
}

/**
 * What happens when a fallback is left unanswered — read from the writers, so the sentence
 * cannot promise something else. `TicketImport` makes one project named after the base,
 * `TeamImport` leaves a team at the top level, and everything else lands in the team the
 * import itself was given.
 */
const FALLBACK_NONE: Record<keyof Fallback, string> = {
  teamId: "the team already chosen above",
  parentTeamId: "no parent team",
  projectId: "one project named after the base",
};

/** One field: the column that answers it, and what that column's own words mean. */
function FieldRow({
  schema,
  mapping,
  field,
  candidates,
  onColumn,
  onValue,
}: {
  schema: NotionImportSchema;
  mapping: BaseMapping;
  field: ImportField;
  candidates: string[];
  onColumn: (field: ImportField, property: string) => void;
  onValue: (field: ImportField, option: string, value: string) => void;
}) {
  const property = mapping.columns[field] ?? "";
  const column = schema.columns.find((candidate) => candidate.name === property);
  const values = kansoValues(field, schema.target);
  // Options are only worth asking about where Kanso has a closed vocabulary to map them
  // onto: a date, a relation, a person or a description has nothing to choose from.
  const options = values.length > 0 ? (column?.options ?? []) : [];
  const fallenBack = defaultedOptions(schema, mapping, field);
  /** The word for a Kanso value, so "— default —" can name what it actually means. */
  const labelOf = (value: string | null) => values.find((known) => known.value === value)?.label;
  const defaultLabel = labelOf(schema.defaults[field]);

  return (
    <div className="flex flex-col gap-1">
      <label className="grid grid-cols-[170px_1fr] items-center gap-3 text-12">
        <span className="text-muted-foreground">{FIELD_LABELS[field]}</span>
        <select value={property} onChange={(event) => onColumn(field, event.target.value)}>
          <option value="">— none —</option>
          {candidates.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>

      {options.map((option) => {
        /*
         * Per option, not per field: leaving `Done` alone makes it `Done`, because the
         * reader on the server matches an option's own label where the mapping says
         * nothing. Printing the field's default here for every option is what made this
         * select say "Todo" about an option that becomes `Done` — and made clearing one
         * back to "default" a decision the server ignored without saying so.
         */
        const leftAlone = labelOf(optionDefault(schema, mapping, field, option));
        return (
          <label
            key={option}
            className="grid grid-cols-[170px_1fr] items-center gap-3 pl-3 text-11"
          >
            <span className="truncate text-faint">{option}</span>
            <select
              value={mapping.values[field]?.[option] ?? ""}
              onChange={(event) => onValue(field, option, event.target.value)}
            >
              <option value="">— {leftAlone ? `default (${leftAlone})` : "default"} —</option>
              {values.map((value) => (
                <option key={value.value} value={value.value}>
                  {value.label}
                </option>
              ))}
            </select>
          </label>
        );
      })}

      {options.length > 0 && fallenBack.length > 0 && (
        <span className="pl-3 text-11 text-faint">
          {fallenBack.join(", ")} {fallenBack.length === 1 ? "becomes" : "become"} the default
          {defaultLabel ? ` — ${defaultLabel}` : ""}.
        </span>
      )}
    </div>
  );
}
