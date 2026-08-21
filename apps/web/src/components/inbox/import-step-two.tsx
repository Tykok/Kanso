"use client";

import { type NotionImportSource, type Team } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { targetOf, type ImportCounts, type ImportMapping } from "./import-map";
import { ROW_GRID, TARGET_DOTS, TARGET_LABELS, pageCount } from "./import-targets";

/**
 * Step 2, and the whole screen: every Notion database becomes a project, a folder of
 * documents, or nothing.
 *
 * The count beside the confirm button is recomputed from the mapping on every change —
 * see `import-map.ts`, where the arithmetic lives and is tested against the drawing's own
 * numbers.
 *
 * The team is here rather than on the step that writes, because it is a decision of the
 * same kind as the three in the table: an imported ticket takes its number from a team,
 * and which team is part of saying what a base becomes. It is required before the preview
 * so that nobody reaches the last step and finds the button dead.
 */
export function StepTwo({
  sources,
  mapping,
  counts,
  planEmpty,
  teams,
  teamId,
  onTeam,
  onCycle,
  onPreview,
  onBack,
  pending,
  error,
}: {
  sources: NotionImportSource[];
  mapping: ImportMapping;
  counts: ImportCounts;
  /** Nothing is mapped, so there is nothing to preview — the count of pages may still be 0. */
  planEmpty: boolean;
  teams: Team[];
  teamId: string;
  onTeam: (id: string) => void;
  onCycle: (source: NotionImportSource) => void;
  onPreview: () => void;
  onBack: () => void;
  pending: boolean;
  error?: string;
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
          Relations between the databases you keep become ticket dependencies. Properties Kanso
          has no column for stay readable in an “imported from Notion” section.
        </span>
      </div>

      <div className="flex items-center gap-2.5">
        <Button disabled={planEmpty || teamId === "" || pending} onClick={onPreview}>
          Preview the import
        </Button>
        <Button variant="outline" onClick={onBack}>
          Back
        </Button>
        <span className="ml-1 text-11 text-faint">
          {counts.kept} of {counts.total} pages kept
        </span>
      </div>

      {error && <span className="text-12 text-urgent">{error}</span>}
    </>
  );
}
