"use client";

import { type NotionImportSource, type Team } from "@/lib/api";
import { useImportSchema } from "@/lib/queries";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { suggestionsFrom, type BaseMapping } from "./import-columns";
import {
  importPlan,
  targetOf,
  type ImportCounts,
  type ImportMapping,
  type ImportPlanEntry,
  type ImportTarget,
} from "./import-map";
import { ROW_GRID, TARGET_DOTS, TARGET_LABELS, pageCount } from "./import-targets";

/**
 * Step 2: every Notion database becomes teams, projects, tickets, a folder of documents,
 * or nothing.
 *
 * The count beside the button is recomputed from the mapping on every change — see
 * `import-map.ts`, where the arithmetic lives and is tested against the drawing's own
 * numbers.
 *
 * The team is here rather than on the step that writes, because it is a decision of the
 * same kind as the ones in the table: an imported ticket takes its number from a team, and
 * which team is part of saying what a base becomes. It is required before leaving this
 * step so that nobody reaches the last one and finds the button dead — and only when the
 * plan needs one at all, since a plan of teams alone has no destination to ask about.
 */
export function StepTwo({
  sources,
  mapping,
  kept,
  mappings,
  counts,
  planEmpty,
  teams,
  teamId,
  teamRequired,
  onTeam,
  onCycle,
  onSuggest,
  onNext,
  onBack,
}: {
  sources: NotionImportSource[];
  mapping: ImportMapping;
  /** What is being imported and as what — [mapping] minus the databases nobody kept. */
  kept: ImportMapping;
  /** What the reader has said about each base's columns, where they have said anything. */
  mappings: Record<string, BaseMapping>;
  counts: ImportCounts;
  /** Nothing is mapped, so there is nothing to map columns for — the page count may still be 0. */
  planEmpty: boolean;
  teams: Team[];
  teamId: string;
  /** A plan of teams alone needs no destination; anything else does. */
  teamRequired: boolean;
  onTeam: (id: string) => void;
  onCycle: (source: NotionImportSource) => void;
  onSuggest: (sourceId: string, target: ImportTarget) => void;
  onNext: () => void;
  onBack: () => void;
}) {
  return (
    <>
      <div className="flex flex-col gap-1.5">
        <span className="text-15 font-medium">Choose what becomes what</span>
        <span className="text-12 text-muted-foreground">
          Each Notion database becomes a team, a set of projects, a set of tickets, or a folder
          of documents. Nothing is written before you confirm.
        </span>
      </div>

      <label className="flex items-center gap-2.5 text-12">
        <span className="text-muted-foreground">Into team</span>
        <select
          className="min-w-[180px]"
          value={teamId}
          onChange={(event) => onTeam(event.target.value)}
        >
          <option value="">— choose a team —</option>
          {/* Already narrowed to the possible destinations by the shell; only sorted here. */}
          {[...teams]
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
        </select>
        <span className="text-11 text-faint">
          Imported tickets take their number from it, like any other.
        </span>
      </label>

      <div className="flex flex-col gap-0.5">
        <div
          className={cn(
            "grid items-center gap-3 px-3 py-1.5 text-11 tracking-[0.08em] text-faint uppercase",
            ROW_GRID,
          )}
        >
          <span>Notion database</span>
          <span>Items</span>
          <span>Becomes</span>
        </div>

        {sources.map((source) => {
          const target = targetOf(mapping, source);
          const ignored = target === "ignore";
          return (
            <div
              key={source.id}
              data-testid="import-row"
              className={cn(
                "grid h-[38px] items-center gap-3 rounded-md bg-background px-3 text-12",
                ROW_GRID,
                ignored && "text-faint",
              )}
            >
              <span className="truncate">{source.name}</span>
              <span className={ignored ? undefined : "text-muted-foreground"}>
                {pageCount(source.pages, source.pagesExact)}
              </span>
              {/*
               * One button that cycles the three, not a `<select>`: there are
               * exactly three answers, the choice is made once per row, and a
               * dropdown costs two clicks to say the same thing.
               */}
              <button
                type="button"
                className={cn(
                  "inline-flex items-center gap-1.5 justify-self-start rounded-md px-2.5 py-1",
                  ignored
                    ? "border border-dashed border-border"
                    : "border border-border bg-card text-muted-foreground hover:bg-accent",
                )}
                aria-label={`${source.name} becomes: ${TARGET_LABELS[target]}`}
                onClick={() => onCycle(source)}
              >
                {!ignored && <span className={cn("size-1.5 rounded-sm", TARGET_DOTS[target])} />}
                {TARGET_LABELS[target]}
                <span className="text-faint">▾</span>
              </button>
            </div>
          );
        })}
      </div>

      <div className="flex items-start gap-2.5 rounded-md bg-warning/15 px-3 py-3 text-12 text-status-progress">
        <span className="mt-1 size-[7px] shrink-0 rounded-full bg-status-progress" />
        <span>
          Relations between the databases you keep place a row where it belongs — a ticket in
          its project, a team under its parent — and the ones between two tickets become
          dependencies. Properties Kanso has no column for stay readable in an “imported from
          Notion” section.
        </span>
      </div>

      {importPlan(sources, mapping).map((base) => (
        <RelationHint
          key={base.sourceId}
          base={base}
          kept={kept}
          mapping={mappings[base.sourceId]}
          sources={sources}
          onSuggest={onSuggest}
        />
      ))}

      <div className="flex items-center gap-2.5">
        <Button disabled={planEmpty || (teamRequired && teamId === "")} onClick={onNext}>
          Say which column is which
        </Button>
        <Button variant="outline" onClick={onBack}>
          Back
        </Button>
        <span className="ml-1 text-11 text-faint">
          {counts.kept} of {counts.total} pages kept
        </span>
      </div>
    </>
  );
}

/**
 * "This base points at one you are ignoring" — one line per base, and never a blocker.
 *
 * The import proceeds either way: a relation whose other end is not being imported resolves
 * to nothing, so the row lands in the fallback its base was given and the relation is
 * counted as dropped. That is a fact about the plan, not a mistake, and the reader is the
 * one who decides whether it matters.
 *
 * It reads the same schema step 3 does, from the same cache, and substitutes the reader's
 * own mapping for the server's suggestion wherever they have made one — so walking back here
 * from step 3 does not warn about a relation they have since unmapped.
 */
function RelationHint({
  base,
  kept,
  mapping,
  sources,
  onSuggest,
}: {
  base: ImportPlanEntry;
  kept: ImportMapping;
  mapping?: BaseMapping;
  sources: NotionImportSource[];
  onSuggest: (sourceId: string, target: ImportTarget) => void;
}) {
  const schema = useImportSchema(base.sourceId, base.target);
  if (!schema.data) return null;

  const suggestions = suggestionsFrom(
    { ...schema.data, suggestion: mapping ?? schema.data.suggestion },
    kept,
  );

  return suggestions.map(({ sourceId, target }) => {
    const pointed = sources.find((source) => source.id === sourceId);
    return (
      <span key={sourceId} className="flex items-center gap-2.5 text-11 text-faint">
        <span>
          {base.name} is related to {pointed ? pointed.name : "a database Kanso cannot see"}, which
          nothing is importing. Those relations will be dropped.
        </span>
        {pointed && (
          <button
            type="button"
            className="shrink-0 rounded-md border border-border px-2 py-0.5 text-muted-foreground hover:bg-accent"
            onClick={() => onSuggest(sourceId, target)}
          >
            Import it as {TARGET_LABELS[target].toLowerCase()}
          </button>
        )}
      </span>
    );
  });
}
