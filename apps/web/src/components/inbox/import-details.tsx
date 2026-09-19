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
 * **Mounted on first unfold, and never unmounted again.** `StepPeople` asks the API which
 * people the plan names as soon as it mounts, so a panel nobody has ever opened must not
 * spend that request — hence waiting for the first `open`. But `StepPeople` also keeps
 * `edits` in a `useState` of its own, on purpose (see its own comment: a seeded guess must
 * not be written in until somebody has looked at it), and unmounting on every fold would
 * throw that state away with it — a correction the reader made would vanish the moment
 * they closed the panel to glance at something else. So once opened, the children stay
 * mounted and folding back up only hides them with `display: none`; the wrapper carries
 * `flex flex-col gap-4` from a class, which a bare `hidden` attribute loses to, so the
 * display is set inline instead. Both halves matter: nothing is requested before the
 * reader has looked, and nothing they typed is lost once they stop looking.
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
  /** Once true, never false again — see the doc comment above for why. */
  const [everOpened, setEverOpened] = useState(false);
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
        onClick={() => {
          setOpen((shown) => !shown);
          setEverOpened(true);
        }}
      >
        <span className="font-medium">Columns and people</span>
        <span className="flex-1 text-11 text-faint">{detailsSummary({ mappings, people })}</span>
        <span className="text-faint">{open ? "▴" : "▾"}</span>
      </button>

      {everOpened && (
        <div className="flex flex-col gap-4" style={open ? undefined : { display: "none" }}>
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
