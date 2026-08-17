"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { notionImportApi, type NotionImportSource } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { Backdrop } from "@/components/overlays";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import {
  DEFAULT_TARGET,
  importCounts,
  importPlan,
  targetOf,
  type ImportMapping,
  type ImportTarget,
} from "./import-map";

const TARGET_LABELS: Record<ImportTarget, string> = {
  project: "Project",
  documents: "Documents",
  ignore: "Ignore",
};

/** The dot beside a target, from the closed status palette rather than a new colour. */
const TARGET_DOTS: Record<ImportTarget, string> = {
  project: "bg-status-review",
  documents: "bg-status-done",
  ignore: "bg-transparent",
};

/**
 * Screen 24. Three steps, and nothing written until the third.
 *
 * The second step is the whole screen: every Notion database becomes a project, a
 * document folder, or nothing, and the count beside the confirm button is recomputed
 * from that mapping on every change — see `import-map.ts`, where the arithmetic lives and
 * is tested. `DEFAULT_TARGET` is `ignore`, so the count starts at zero and only rises as
 * decisions are made; the other default writes the whole workspace for anybody who
 * clicks through.
 */
export function ImportDialog({ onClose }: { onClose: () => void }) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [mapping, setMapping] = useState<ImportMapping>({});

  const discovered = useQuery({
    queryKey: ["notion-import-sources"],
    queryFn: notionImportApi.sources,
    retry: false,
  });

  const sources = useMemo<NotionImportSource[]>(() => discovered.data?.sources ?? [], [discovered.data]);
  const counts = useMemo(() => importCounts(sources, mapping), [sources, mapping]);
  const plan = useMemo(
    () => importPlan(sources, mapping).map(({ sourceId, target }) => ({ sourceId, target })),
    [sources, mapping],
  );

  const preview = useMutation({ mutationFn: () => notionImportApi.preview(plan) });
  const confirm = useMutation({
    mutationFn: () => notionImportApi.confirm(plan),
    onSuccess: onClose,
  });

  const cycle = (source: NotionImportSource) => {
    const order: ImportTarget[] = ["project", "documents", "ignore"];
    const next = order[(order.indexOf(targetOf(mapping, source)) + 1) % order.length];
    setMapping((current) => ({ ...current, [source.id]: next }));
  };

  return (
    <Backdrop onClose={onClose} panelClassName="w-[min(710px,94vw)]">
      <div role="dialog" aria-modal="true" aria-label="Import from Notion" className="flex flex-col">
        <div className="flex items-center gap-2.5 bg-background px-5 py-3 text-12 text-faint">
          <span className="font-medium text-muted-foreground">Import from Notion</span>
          <span className="flex-1" />
          <span>step {step} of 3</span>
        </div>

        <div className="flex flex-col gap-[18px] p-5">
          <div className="flex gap-0.5" aria-hidden>
            {[1, 2, 3].map((mark) => (
              <span
                key={mark}
                className={cn("h-[3px] flex-1", mark <= step ? "bg-primary" : "bg-accent")}
              />
            ))}
          </div>

          {step === 1 && (
            <StepOne
              loading={discovered.isLoading}
              unavailable={
                discovered.isError
                  ? actionErrorMessage(discovered.error)
                  : discovered.data && !discovered.data.available
                    ? (discovered.data.reason ?? "Kanso cannot read this workspace.")
                    : undefined
              }
              sources={sources}
              onNext={() => setStep(2)}
            />
          )}

          {step === 2 && (
            <>
              <div className="flex flex-col gap-1.5">
                <span className="text-15 font-medium">Choose what becomes what</span>
                <span className="text-12 text-muted-foreground">
                  Each Notion database becomes a project or a folder of documents. Nothing is
                  written before you confirm.
                </span>
              </div>

              <div className="flex flex-col gap-0.5">
                <div className="grid grid-cols-[1fr_130px_150px] items-center gap-3 px-3 py-1.5 text-11 tracking-[0.08em] text-faint uppercase">
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
                        "grid h-[38px] grid-cols-[1fr_130px_150px] items-center gap-3 rounded-md bg-background px-3 text-12",
                        ignored && "text-faint",
                      )}
                    >
                      <span className="truncate">{source.name}</span>
                      <span className={ignored ? undefined : "text-muted-foreground"}>
                        {source.pages} pages
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
                        onClick={() => cycle(source)}
                      >
                        {!ignored && (
                          <span className={cn("size-1.5 rounded-sm", TARGET_DOTS[target])} />
                        )}
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
                  Relations between the databases you keep become ticket dependencies.
                  Properties Kanso has no column for stay readable in an “imported from
                  Notion” section.
                </span>
              </div>

              <div className="flex items-center gap-2.5">
                <Button
                  disabled={plan.length === 0 || preview.isPending}
                  onClick={() => {
                    preview.mutate(undefined, { onSuccess: () => setStep(3) });
                  }}
                >
                  Preview the import
                </Button>
                <Button variant="outline" onClick={() => setStep(1)}>
                  Back
                </Button>
                <span className="ml-1 text-11 text-faint">
                  {counts.kept} of {counts.total} pages kept
                </span>
              </div>

              {preview.isError && (
                <span className="text-12 text-urgent">{actionErrorMessage(preview.error)}</span>
              )}
            </>
          )}

          {step === 3 && (
            <>
              <div className="flex flex-col gap-1.5">
                <span className="text-15 font-medium">
                  {counts.projects} {counts.projects === 1 ? "project" : "projects"},{" "}
                  {counts.folders} {counts.folders === 1 ? "folder" : "folders"}
                </span>
                <span className="text-12 text-muted-foreground">
                  {counts.kept} pages out of {counts.total}. Confirming is the first thing that
                  writes anything.
                </span>
              </div>

              <div className="flex flex-col gap-0.5 text-12">
                {importPlan(sources, mapping).map((entry) => (
                  <div
                    key={entry.sourceId}
                    className="grid h-[30px] grid-cols-[1fr_130px_150px] items-center gap-3 rounded-sm bg-background px-3"
                  >
                    <span className="truncate">{entry.name}</span>
                    <span className="text-muted-foreground">{entry.pages} pages</span>
                    <span className="text-muted-foreground">{TARGET_LABELS[entry.target]}</span>
                  </div>
                ))}
              </div>

              {preview.data && (
                <span className="text-11 text-faint">
                  {preview.data.linkedSources > 0
                    ? `${preview.data.linkedSources} of them are linked to each other; those relations become dependencies. `
                    : ""}
                  {preview.data.unmappedProperties.length > 0
                    ? `Unmapped properties: ${preview.data.unmappedProperties.join(", ")}.`
                    : "Every property has a column here."}
                </span>
              )}

              <div className="flex items-center gap-2.5">
                <Button disabled={confirm.isPending} onClick={() => confirm.mutate()}>
                  Import {counts.kept} pages
                </Button>
                <Button variant="outline" onClick={() => setStep(2)}>
                  Back
                </Button>
              </div>

              {confirm.isError && (
                <span className="text-12 text-urgent">{actionErrorMessage(confirm.error)}</span>
              )}
            </>
          )}
        </div>
      </div>
    </Backdrop>
  );
}

function StepOne({
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
              <span className="text-muted-foreground">{source.pages} pages</span>
            </div>
          ))}
          <span className="pt-2 text-11 text-faint">
            {sources.length} {sources.length === 1 ? "database" : "databases"}, {total} pages in all.
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
