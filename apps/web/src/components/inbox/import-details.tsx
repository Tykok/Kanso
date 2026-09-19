"use client";

import { useState } from "react";
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
 * **Mounted only when open.** `StepPeople` asks the API which people the plan names as
 * soon as it mounts, and a panel nobody unfolds must not spend that request. The state is
 * the shell's either way, so closing the panel loses nothing the reader typed.
 */
export function ImportDetails({
  bases,
  kept,
  mappings,
  fallbacks,
  teams,
  projects,
  plan,
  onMapping,
  onSeed,
  onFallback,
  onPeople,
}: {
  bases: ImportPlanEntry[];
  kept: ImportMapping;
  mappings: Record<string, BaseMapping>;
  fallbacks: Record<string, Fallback>;
  teams: Team[];
  projects: Project[];
  plan: NotionImportPlanRow[];
  onMapping: (sourceId: string, mapping: BaseMapping) => void;
  onSeed: (sourceId: string, seed: BaseMapping) => void;
  onFallback: (sourceId: string, fallback: Fallback) => void;
  onPeople: (people: Record<string, string | null>) => void;
}) {
  const [open, setOpen] = useState(false);
  const [people, setPeople] = useState<Record<string, string | null>>({});

  /** Told to the shell as well as kept here, because the request carries it. */
  const takePeople = (next: Record<string, string | null>) => {
    setPeople(next);
    onPeople(next);
  };

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

      {open && (
        <div className="flex flex-col gap-4">
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
          />
          <StepPeople plan={plan} onPeople={takePeople} />
        </div>
      )}
    </div>
  );
}
