"use client";

import { type NotionImportPreview, type NotionImportSource, type Team } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { importPlan, type ImportCounts, type ImportMapping } from "./import-map";
import { ROW_GRID, TARGET_LABELS, pageCount } from "./import-targets";

/**
 * Step 3: the last read, and then the only write.
 *
 * Everything on it comes from the preview the server just answered, so what is confirmed
 * is what was described. The two sentences the drawing puts here — the linked bases whose
 * relations become dependencies, and the properties with no column — are the server's
 * answer, not this component's guess at one.
 */
export function StepThree({
  sources,
  mapping,
  counts,
  preview,
  teamName,
  onConfirm,
  onBack,
  pending,
  error,
}: {
  sources: NotionImportSource[];
  mapping: ImportMapping;
  counts: ImportCounts;
  preview?: NotionImportPreview;
  teamName?: Team["name"];
  onConfirm: () => void;
  onBack: () => void;
  pending: boolean;
  error?: string;
}) {
  // Which counts were bounded rather than finished, so a row here says "2000+" wherever
  // the two steps before it did. `ImportPlanEntry` carries the count but not that flag,
  // and `import-map.ts` is arithmetic against the drawing — not the place to widen.
  const exact = new Map(sources.map((source) => [source.id, source.pagesExact]));
  const allExact = sources.every((source) => source.pagesExact);

  return (
    <>
      <div className="flex flex-col gap-1.5">
        <span className="text-15 font-medium">
          {/* "tickets", not "projects": a base mapped to tickets is what still becomes one
           * project full of tickets. `counts.projects` counts the other shape, refused at
           * the writer until it exists. */}
          {counts.tickets} {counts.tickets === 1 ? "project" : "projects"}, {counts.folders}{" "}
          {counts.folders === 1 ? "folder" : "folders"}
          {teamName ? ` in ${teamName}` : ""}
        </span>
        <span className="text-12 text-muted-foreground">
          {counts.kept} pages out of {counts.total}. Confirming is the first thing that writes
          anything.
        </span>
      </div>

      <div className="flex flex-col gap-0.5 text-12">
        {importPlan(sources, mapping).map((entry) => (
          <div
            key={entry.sourceId}
            className={cn("grid h-[30px] items-center gap-3 rounded-sm bg-background px-3", ROW_GRID)}
          >
            <span className="truncate">{entry.name}</span>
            <span className="text-muted-foreground">
              {pageCount(entry.pages, exact.get(entry.sourceId) ?? true)}
            </span>
            <span className="text-muted-foreground">{TARGET_LABELS[entry.target]}</span>
          </div>
        ))}
      </div>

      {preview && (
        <span className="text-11 text-faint">
          {preview.linkedSources > 0
            ? `${preview.linkedSources} of them are linked to each other; those relations become dependencies. `
            : ""}
          {preview.unmappedProperties.length > 0
            ? `Unmapped properties: ${preview.unmappedProperties.join(", ")}.`
            : "Every property has a column here."}
          {/*
           * Said here rather than left to the result, because it is the one number that
           * changes what somebody would decide: a page with no title at all cannot be
           * named, so it is reported instead of imported as another "Untitled".
           */}
          {preview.skipped > 0
            ? ` ${preview.skipped} page${preview.skipped === 1 ? "" : "s"} cannot be adopted and will be reported instead.`
            : ""}
        </span>
      )}

      <div className="flex items-center gap-2.5">
        <Button disabled={pending} onClick={onConfirm}>
          Import {pageCount(counts.kept, allExact)}
        </Button>
        <Button variant="outline" onClick={onBack}>
          Back
        </Button>
      </div>

      {error && <span className="text-12 text-urgent">{error}</span>}
    </>
  );
}
