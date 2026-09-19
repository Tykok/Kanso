"use client";

import { useEffect, useState } from "react";
import type { NotionImportPlanRow, Project, Team } from "@/lib/api";
import { type BaseMapping, type Fallback } from "./import-columns";
import { detailsSummary } from "./import-details-summary";
import type { ImportMapping, ImportPlanEntry } from "./import-map";
import { StepColumns } from "./import-step-columns";
import { StepPeople } from "./import-step-people";

/**
 * The two steps that only ever confirmed a guess, folded into one panel.
 *
 * `StepColumns` reads the server's schema suggestion and `StepPeople` reads the person
 * correspondence; both arrive filled in, and both were a screen somebody clicked Next on.
 * As steps they cost two screens out of five and a boolean — `hasPeople` — whose whole job
 * was keeping the way forwards and the way back from disagreeing about whether step 4
 * existed.
 *
 * **Always mounted; folding only hides it.** These two are not a detail view of the plan —
 * they are what fills the request. `StepColumns`'s seeding effect is the only thing that
 * carries the server's suggested columns into the shell's `mappings`, and `StepPeople`'s
 * effect is the only thing that carries the standing correspondence into `people`. Neither
 * the preview nor the confirm request falls back to a guess of its own when the client
 * sends nothing: `NotionImportService` takes the request's mapping verbatim, and
 * `TicketImport.resolveAssignees` resolves purely through `people[id]`. So a reader who
 * never unfolds this panel would otherwise import every base on the writer's raw defaults
 * with no assignees at all — worse than the five-step dialog this replaces ever allowed,
 * since that one walked every reader through both before they could reach confirm.
 *
 * Folding hides the children with an inline `display: none` rather than unmounting them —
 * the wrapper carries `flex flex-col gap-4` from a class, which a bare `hidden` attribute
 * loses to — so a close and a reopen on this screen cost nothing. Crossing to the
 * confirmation screen and back is a different kind of unmount, though: `ImportPlan` and
 * everything folded beneath it, this component included, leaves the tree while step 2 is
 * on screen and remounts fresh on Back. `mappings` and `people` survive that because the
 * shell re-derives them from its own state on every render; `StepPeople`'s `edits` cannot
 * be re-derived — it is what the reader typed, not a guess — so it lives in
 * `import-dialog.tsx` instead and arrives here as a prop. See `edits` there for why.
 *
 * The cost is real and worth naming rather than hiding: `StepPeople` fires `peopleSeen` as
 * soon as it mounts, so every plan with a mapped people column pays that request whether or
 * not anybody ever unfolds the panel. That is the same request the old step 3 → step 4
 * transition made on the way to every confirm, and a saved round trip is not worth an
 * import that silently drops every assignee. What the panel holds is the request, so it is
 * mounted for the request's sake and folded only for the reader's.
 */
export function ImportDetails({
  bases,
  kept,
  mappings,
  fallbacks,
  teams,
  projects,
  plan,
  edits,
  onMapping,
  onSeed,
  onFallback,
  onEdit,
  onPeople,
  onLoading,
}: {
  bases: ImportPlanEntry[];
  kept: ImportMapping;
  mappings: Record<string, BaseMapping>;
  fallbacks: Record<string, Fallback>;
  teams: Team[];
  projects: Project[];
  plan: NotionImportPlanRow[];
  /** The person correspondence's edits, lifted above `StepPeople` — see `import-dialog.tsx`. */
  edits: Record<string, string | null>;
  onMapping: (sourceId: string, mapping: BaseMapping) => void;
  onSeed: (sourceId: string, seed: BaseMapping) => void;
  onFallback: (sourceId: string, fallback: Fallback) => void;
  onEdit: (id: string, value: string | null) => void;
  onPeople: (people: Record<string, string | null>) => void;
  /** Whether `StepColumns` or `StepPeople` is still waiting on a request — see
   *  `import-plan.tsx`'s Preview button, which this reaches through the shell. */
  onLoading: (loading: boolean) => void;
}) {
  const [open, setOpen] = useState(false);
  const [people, setPeople] = useState<Record<string, string | null>>({});
  const [columnsLoading, setColumnsLoading] = useState(false);
  const [peopleLoading, setPeopleLoading] = useState(false);

  /** Told to the shell as well as kept here, because the request carries it. */
  const takePeople = (next: Record<string, string | null>) => {
    setPeople(next);
    onPeople(next);
  };

  useEffect(() => {
    onLoading(columnsLoading || peopleLoading);
  }, [columnsLoading, peopleLoading, onLoading]);

  return (
    <div className="flex flex-col gap-2.5 rounded-md border border-border px-3 py-2.5">
      <button
        type="button"
        className="flex items-center gap-2.5 text-left text-12"
        aria-expanded={open}
        onClick={() => setOpen((shown) => !shown)}
      >
        <span className="font-medium">Columns and people</span>
        <span className="flex-1 text-11 text-faint">{detailsSummary({ mappings, people })}</span>
        <span className="text-faint">{open ? "▴" : "▾"}</span>
      </button>

      <div
        data-testid="import-details-body"
        className="flex flex-col gap-4"
        style={open ? undefined : { display: "none" }}
      >
        <StepColumns
          bases={bases}
          kept={kept}
          mappings={mappings}
          fallbacks={fallbacks}
          teams={teams}
          projects={projects}
          onMapping={onMapping}
          onSeed={onSeed}
          onFallback={onFallback}
          onLoading={setColumnsLoading}
        />
        <StepPeople
          plan={plan}
          edits={edits}
          onEdit={onEdit}
          onPeople={takePeople}
          onLoading={setPeopleLoading}
        />
      </div>
    </div>
  );
}
