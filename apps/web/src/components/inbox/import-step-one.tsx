"use client";

import { type NotionImportSource } from "@/lib/api";
import { Button } from "@/components/ui/button";
import { DEFAULT_TARGET } from "./import-map";
import { TARGET_LABELS, pageCount } from "./import-targets";

/**
 * Step 1: what is in this workspace.
 *
 * The step that could not be answered until the server learned to search a workspace,
 * which is why the whole flow never began. Nothing is copied here and nothing in Notion is
 * touched at any step, and the screen says both, because "reading your workspace" is a
 * sentence people are right to be wary of.
 */
export function StepOne({
  loading,
  unavailable,
  sources,
  onNext,
}: {
  loading: boolean;
  unavailable?: string;
  sources: NotionImportSource[];
  onNext: () => void;
}) {
  const total = sources.reduce((sum, source) => sum + source.pages, 0);
  const counted = sources.every((source) => source.pagesExact);

  return (
    <>
      <div className="flex flex-col gap-1.5">
        <span className="text-15 font-medium">What is in this workspace</span>
        <span className="text-12 text-muted-foreground">
          Kanso reads the databases it can see. Nothing is copied at this step, and nothing is
          changed in Notion at any step.
        </span>
      </div>

      {loading && <span className="text-12 text-faint">Reading the workspace…</span>}

      {/*
       * Said plainly rather than as an empty table. "Kanso cannot list your databases
       * yet" is a sentence somebody can act on; a table with no rows is one they will
       * read as their workspace being empty.
       */}
      {unavailable && (
        <div className="flex flex-col gap-1 rounded-md bg-warning/15 px-3 py-3 text-12 text-status-progress">
          <span className="font-medium">Nothing to import from yet</span>
          <span>{unavailable}</span>
        </div>
      )}

      {!loading && !unavailable && (
        <div className="flex flex-col gap-0.5 text-12">
          {sources.map((source) => (
            <div
              key={source.id}
              className="grid h-[30px] grid-cols-[1fr_130px] items-center gap-3 rounded-sm bg-background px-3"
            >
              <span className="truncate">{source.name}</span>
              <span className="text-muted-foreground">
                {pageCount(source.pages, source.pagesExact)}
              </span>
            </div>
          ))}
          <span className="pt-2 text-11 text-faint">
            {sources.length} {sources.length === 1 ? "database" : "databases"},{" "}
            {counted ? `${total} pages in all.` : `at least ${total} pages in all.`}
          </span>
        </div>
      )}

      <div className="flex items-center gap-2.5">
        <Button disabled={sources.length === 0} onClick={onNext}>
          Choose what becomes what
        </Button>
        <span className="text-11 text-faint">
          Everything starts as “{TARGET_LABELS[DEFAULT_TARGET]}”.
        </span>
      </div>
    </>
  );
}
