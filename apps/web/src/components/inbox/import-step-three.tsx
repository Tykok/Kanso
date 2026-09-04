"use client";

import {
  type NotionImportPreview,
  type NotionImportResult,
  type NotionImportSource,
  type Team,
} from "@/lib/api";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { importPlan, type ImportCounts, type ImportMapping } from "./import-map";
import { ROW_GRID, TARGET_LABELS, pageCount } from "./import-targets";

/**
 * Step 5: the last read, and then the only write.
 *
 * Everything on it comes from the preview the server just answered, so what is confirmed
 * is what was described. The sentences the drawing puts here — the linked bases, the rows a
 * relation would place, the properties with no column, and the pages a row already exists
 * for — are the server's answer, not this component's guess at one.
 *
 * It stays on screen after the write to say what the write did. The numbers are the only
 * report there is: `droppedRelations`, `linkConflicts` and `droppedAssignees` are things
 * the import decided on its own, and a dialog that closed on success would have decided
 * them silently.
 */
export function StepThree({
  sources,
  mapping,
  counts,
  preview,
  teamName,
  result,
  onConfirm,
  onClose,
  onBack,
  pending,
  error,
}: {
  sources: NotionImportSource[];
  mapping: ImportMapping;
  counts: ImportCounts;
  preview?: NotionImportPreview;
  teamName?: Team["name"];
  /** Present once the import has run. Until then this screen is the last read. */
  result?: NotionImportResult;
  onConfirm: () => void;
  onClose: () => void;
  onBack: () => void;
  pending: boolean;
  error?: string;
}) {
  // Which counts were bounded rather than finished, so a row here says "2000+" wherever
  // the two steps before it did. `ImportPlanEntry` carries the count but not that flag,
  // and `import-map.ts` is arithmetic against the drawing — not the place to widen.
  const exact = new Map(sources.map((source) => [source.id, source.pagesExact]));
  const allExact = sources.every((source) => source.pagesExact);
  const plan = importPlan(sources, mapping);

  /**
   * What will exist afterwards — and no single source can say it.
   *
   * `ImportPreview.projects` deliberately merges the two shapes that produce a project: a
   * base whose pages *are* projects, and a base of tickets that becomes one project named
   * after it. Summing its pages would report a base of three hundred tickets as three
   * hundred projects, so the shapes are counted apart here, from the plan.
   *
   * Teams do come from the preview: one page becomes one team, and the preview counts the
   * pages that actually come over — adoptable ones, after the read — where the plan only
   * has the discovery count, which may still be a bound.
   */
  const teams = preview
    ? preview.teams.reduce((all, group) => all + group.pages, 0)
    : plan.reduce((all, entry) => all + (entry.target === "teams" ? entry.pages : 0), 0);
  const projects = plan.reduce(
    (all, entry) =>
      all + (entry.target === "projects" ? entry.pages : entry.target === "tickets" ? 1 : 0),
    0,
  );
  /** A base of projects whose own count stopped at the discovery bound makes this a floor. */
  const projectsBounded = plan.some(
    (entry) => entry.target === "projects" && !(exact.get(entry.sourceId) ?? true),
  );

  /**
   * The number on the confirm button: the pages that will actually become rows.
   *
   * `counts.kept` is the *discovery* count of every kept base — it includes the pages a row
   * already exists for and the pages the reader will be told could not be adopted, both of
   * which the sentence above the button names. Putting that number on the button
   * contradicted the screen's own sentence, which is the one thing `pagesExact` exists to
   * prevent.
   *
   * Once the preview has arrived the honest number is in hand: every group of it counts
   * `PlannedBase.adoptable`, which is pages minus refused minus already-imported, and the
   * three groups together cover all four targets. Until then — the preview is still in
   * flight, or it failed — `counts.kept` is the only number there is, and it is a
   * ceiling rather than a wrong answer.
   */
  const willWrite = preview
    ? [...preview.teams, ...preview.projects, ...preview.folders].reduce(
        (all, group) => all + group.pages,
        0,
      )
    : counts.kept;

  return (
    <>
      <div className="flex flex-col gap-1.5">
        <span className="text-15 font-medium">
          {teams > 0 ? `${teams} ${teams === 1 ? "team" : "teams"}, ` : ""}
          {projects}
          {projectsBounded ? "+" : ""} {projects === 1 ? "project" : "projects"},{" "}
          {counts.folders} {counts.folders === 1 ? "folder" : "folders"}
          {teamName ? ` in ${teamName}` : ""}
        </span>
        <span className="text-12 text-muted-foreground">
          {/* "pages" heads `kept` in this phrasing, so that is the number [pageCount] is
              given. The total takes the bound too, spelled out rather than borrowed: a `+` on
              the first number alone reads as more kept than found, and none on either puts a
              walk's stopping point in front of the confirm button as though it were a count —
              which is the one thing `pagesExact` is on the wire to prevent. */}
          {`${pageCount(counts.kept, allExact)} out of ${counts.total}${allExact ? "" : "+"}.`}
          {result ? "" : " Confirming is the first thing that writes anything."}
        </span>
      </div>

      <div className="flex flex-col gap-0.5 text-12">
        {plan.map((entry) => (
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

      {preview && !result && (
        <span className="text-11 text-faint">
          {/*
           * "take part in a link", not "become dependencies": `linkedSources` counts the
           * bases on either end of any resolved relation, and most of those relations place
           * a row rather than draw an arrow — a `Projet` column puts a ticket in a project.
           * `linkedByRelation` is the count of rows actually placed that way.
           */}
          {preview.linkedSources > 0
            ? `${preview.linkedSources} of them are linked to each other; ${preview.linkedByRelation} rows are placed by those relations, and the ones between two tickets become dependencies. `
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
          {/*
           * The sentence that stops somebody importing the same workspace twice: Kanso
           * leaves those pages alone. The button below already excludes them — `willWrite`
           * counts the preview's own groups — so this says *why* its number is smaller than
           * the count of the bases, which is the question a reader would otherwise have.
           */}
          {preview.alreadyImported > 0
            ? ` ${preview.alreadyImported} have been imported before and will be left as they are.`
            : ""}
        </span>
      )}

      {result ? (
        <>
          <span className="text-12">
            Imported {result.teams} {result.teams === 1 ? "team" : "teams"}, {result.projects}{" "}
            {result.projects === 1 ? "project" : "projects"}, {result.tickets}{" "}
            {result.tickets === 1 ? "ticket" : "tickets"}, {result.docs}{" "}
            {result.docs === 1 ? "document" : "documents"} in {result.folders}{" "}
            {result.folders === 1 ? "folder" : "folders"}, and {result.dependencies}{" "}
            {result.dependencies === 1 ? "dependency" : "dependencies"}.
          </span>
          <span className="text-11 text-faint">
            {result.alreadyImported > 0
              ? `${result.alreadyImported} page${result.alreadyImported === 1 ? " was" : "s were"} already imported and left alone. `
              : ""}
            {result.droppedRelations > 0
              ? `${result.droppedRelations} relation${result.droppedRelations === 1 ? "" : "s"} pointed at nothing here and were dropped. `
              : ""}
            {result.linkConflicts > 0
              ? `${result.linkConflicts} relation${result.linkConflicts === 1 ? " had two sides that" : "s had two sides that"} disagreed; the child's answer won. `
              : ""}
            {result.droppedAssignees > 0
              ? `${result.droppedAssignees} assignee${result.droppedAssignees === 1 ? "" : "s"} named an account that no longer exists, so those rows are unassigned. `
              : ""}
            {/*
             * The reasons, not one per page: there are two of them in the whole server —
             * no title, and Notion's trash — so four hundred skipped pages printed the same
             * sentence four hundred times. The count already says how many.
             */}
            {result.skipped.length > 0
              ? `${result.skipped.length} page${result.skipped.length === 1 ? "" : "s"} could not be adopted: ${[...new Set(result.skipped.map((skip) => skip.reason))].join(", ")}.`
              : ""}
          </span>
          <div className="flex items-center gap-2.5">
            <Button onClick={onClose}>Done</Button>
          </div>
        </>
      ) : (
        <div className="flex items-center gap-2.5">
          <Button disabled={pending} onClick={onConfirm}>
            Import {pageCount(willWrite, allExact)}
          </Button>
          <Button variant="outline" onClick={onBack}>
            Back
          </Button>
        </div>
      )}

      {error && <span className="text-12 text-urgent">{error}</span>}
    </>
  );
}
