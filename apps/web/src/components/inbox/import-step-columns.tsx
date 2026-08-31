"use client";

import { useEffect } from "react";
import { type Project, type Team } from "@/lib/api";
import { useImportSchema } from "@/lib/queries";
import { Button } from "@/components/ui/button";
import { actionErrorMessage } from "@/lib/errors";
import {
  EMPTY_MAPPING,
  FALLBACK_LABELS,
  answeredFields,
  kansoValues,
  openFallbacks,
  unmappedOptions,
  type BaseMapping,
  type Fallback,
  type NotionImportSchema,
} from "./import-columns";
import type { ImportMapping, ImportPlanEntry } from "./import-map";
import { FIELD_LABELS, TARGET_LABELS } from "./import-targets";

/**
 * Step 3: which column answers which field, and what the words inside it mean.
 *
 * One section per kept base, because three targets have three sets of fields: a base of
 * teams is asked about its parent, a base of projects about its lead and its team, a base
 * of tickets about status, priority and what blocks what. A single table of "fields" across
 * all of them would ask every base every question.
 *
 * Every select is pre-filled from the server's suggestion and every one is overridable —
 * the pre-fill is a default, not a rule. And the options no Kanso value was chosen for are
 * *named* under the field rather than counted: a number tells the reader something was
 * guessed, a list tells them what.
 */
export function StepColumns({
  bases,
  kept,
  mappings,
  fallbacks,
  teams,
  projects,
  hasPeople,
  onMapping,
  onSeed,
  onFallback,
  onNext,
  onBack,
  pending,
  error,
}: {
  bases: ImportPlanEntry[];
  /** What every base becomes, so a relation into an ignored one can be told apart. */
  kept: ImportMapping;
  mappings: Record<string, BaseMapping>;
  fallbacks: Record<string, Fallback>;
  teams: Team[];
  projects: Project[];
  /** A people column is mapped somewhere, so step 4 is on the way to the preview. */
  hasPeople: boolean;
  onMapping: (sourceId: string, mapping: BaseMapping) => void;
  /** Separate from [onMapping] because the shell refuses a second seed, never an edit. */
  onSeed: (sourceId: string, seed: BaseMapping) => void;
  onFallback: (sourceId: string, fallback: Fallback) => void;
  onNext: () => void;
  onBack: () => void;
  pending: boolean;
  error?: string;
}) {
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
        {bases.map((base) => (
          <BaseSection
            key={base.sourceId}
            base={base}
            kept={kept}
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

      {hasPeople && (
        <span className="text-11 text-faint">
          A people column is mapped, so the next step asks who those people are in Kanso.
        </span>
      )}

      <div className="flex items-center gap-2.5">
        <Button disabled={pending} onClick={onNext}>
          {hasPeople ? "Match the people" : "Preview the import"}
        </Button>
        <Button variant="outline" onClick={onBack}>
          Back
        </Button>
      </div>

      {error && <span className="text-12 text-urgent">{error}</span>}
    </>
  );
}

/**
 * One base, and its own request for its own schema.
 *
 * A query per section rather than one for all of them: the schemas are read from Notion one
 * data source at a time, and a workspace where a single base has been unshared would
 * otherwise blank the whole screen. Here that base says so and the rest stay usable — its
 * pages still import, with nothing mapped.
 */
function BaseSection({
  base,
  kept,
  mapping,
  fallback,
  teams,
  projects,
  onMapping,
  onSeed,
  onFallback,
}: {
  base: ImportPlanEntry;
  kept: ImportMapping;
  /** Undefined until the suggestion has seeded it — which is what `seeded` reads. */
  mapping?: BaseMapping;
  fallback: Fallback;
  teams: Team[];
  projects: Project[];
  onMapping: (sourceId: string, mapping: BaseMapping) => void;
  onSeed: (sourceId: string, seed: BaseMapping) => void;
  onFallback: (sourceId: string, fallback: Fallback) => void;
}) {
  const schema = useImportSchema(base.sourceId, base.target);

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

  const setColumn = (field: string, property: string) => {
    const columns = { ...current.columns };
    const values = { ...current.values };
    if (property) columns[field] = property;
    else delete columns[field];
    // The option table belonged to the column that has just been replaced.
    delete values[field];
    onMapping(base.sourceId, { columns, values });
  };

  const setValue = (field: string, option: string, value: string) => {
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
        openFallbacks(view, current, kept).map((key) => (
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
  teamId: "the team chosen in step 2",
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
  field: string;
  candidates: string[];
  onColumn: (field: string, property: string) => void;
  onValue: (field: string, option: string, value: string) => void;
}) {
  const property = mapping.columns[field] ?? "";
  const column = schema.columns.find((candidate) => candidate.name === property);
  const values = kansoValues(field, schema.target);
  // Options are only worth asking about where Kanso has a closed vocabulary to map them
  // onto: a date, a relation, a person or a description has nothing to choose from.
  const options = values.length > 0 ? (column?.options ?? []) : [];
  const fallenBack = unmappedOptions(schema, mapping, field);
  const defaultLabel = values.find((value) => value.value === schema.defaults[field])?.label;

  return (
    <div className="flex flex-col gap-1">
      <label className="grid grid-cols-[170px_1fr] items-center gap-3 text-12">
        <span className="text-muted-foreground">{FIELD_LABELS[field] ?? field}</span>
        <select value={property} onChange={(event) => onColumn(field, event.target.value)}>
          <option value="">— none —</option>
          {candidates.map((name) => (
            <option key={name} value={name}>
              {name}
            </option>
          ))}
        </select>
      </label>

      {options.map((option) => (
        <label
          key={option}
          className="grid grid-cols-[170px_1fr] items-center gap-3 pl-3 text-11"
        >
          <span className="truncate text-faint">{option}</span>
          <select
            value={mapping.values[field]?.[option] ?? ""}
            onChange={(event) => onValue(field, option, event.target.value)}
          >
            <option value="">— {defaultLabel ? `default (${defaultLabel})` : "default"} —</option>
            {values.map((value) => (
              <option key={value.value} value={value.value}>
                {value.label}
              </option>
            ))}
          </select>
        </label>
      ))}

      {options.length > 0 && fallenBack.length > 0 && (
        <span className="pl-3 text-11 text-faint">
          {fallenBack.join(", ")} {fallenBack.length === 1 ? "becomes" : "become"} the default
          {defaultLabel ? ` — ${defaultLabel}` : ""}.
        </span>
      )}
    </div>
  );
}
