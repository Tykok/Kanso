"use client";

import { useCallback, useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { notionImportApi, type NotionImportRequest, type NotionImportSource } from "@/lib/api";
import { useProjects, useTeams } from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import { Backdrop } from "@/components/overlays";
import { Button } from "@/components/ui/button";
import { cn } from "@/lib/utils";
import { importCounts, importPlan, targetOf, type ImportMapping, type ImportTarget } from "./import-map";
import { type BaseMapping, type Fallback } from "./import-columns";
import { PEOPLE_FIELDS } from "./import-targets";
import { StepOne } from "./import-step-one";
import { StepTwo } from "./import-step-two";
import { StepColumns } from "./import-step-columns";
import { StepThree } from "./import-step-three";

/**
 * Screen 24. Five steps, and nothing written until the fifth.
 *
 * This file is the shell: the state the steps share, the requests, and which step is on
 * screen. Each step is its own file. What each step decides:
 *
 * 1. which databases are there at all;
 * 2. what each one becomes;
 * 3. which of its columns answers which field, and what the words inside them mean;
 * 4. who the people those columns name are, in Kanso;
 * 5. what all of that would write — the last read before the only write.
 *
 * Step 4 is skipped in both directions when no people column is mapped, and [hasPeople] is
 * the single boolean that decides it, so the way forwards and the way back cannot disagree
 * about whether that step exists.
 *
 * `DEFAULT_TARGET` is `ignore`, so the count starts at zero and only rises as decisions
 * are made; the other default writes the whole workspace for anybody who clicks through.
 */
/** One key gone, the rest untouched. */
const without = <T,>(record: Record<string, T>, key: string): Record<string, T> => {
  const rest = { ...record };
  delete rest[key];
  return rest;
};

export function ImportDialog({ onClose }: { onClose: () => void }) {
  const [step, setStep] = useState<1 | 2 | 3 | 4 | 5>(1);
  const [mapping, setMapping] = useState<ImportMapping>({});
  const [teamId, setTeamId] = useState("");
  const [mappings, setMappings] = useState<Record<string, BaseMapping>>({});
  const [fallbacks, setFallbacks] = useState<Record<string, Fallback>>({});
  /**
   * The person correspondence, kept here because the request carries it and step 4 is the
   * screen that fills it. No setter yet: the rows that match a Notion person to a Kanso
   * account are step 4's own, and an empty map is what the request means by "nobody has
   * said" — every mapped person's rows land unassigned rather than guessed at.
   */
  const [people] = useState<Record<string, string | null>>({});

  const discovered = useQuery({
    queryKey: ["notion-import-sources"],
    queryFn: notionImportApi.sources,
    retry: false,
  });

  const teams = useTeams();
  const projects = useProjects();

  /**
   * One team, so no choice to make: an instance with a single team is the common shape and
   * the destination is not in question there.
   *
   * Derived rather than pushed into the state by an effect. The teams arrive after the
   * first render, and an effect that copied them into `teamId` would be a second
   * definition of the same fact — one that has to be kept in step with a list that can
   * still change while the dialog is open.
   */
  const destinations = useMemo(() => teams.data?.filter((team) => !team.archived) ?? [], [teams.data]);
  const destination = teamId || (destinations.length === 1 ? destinations[0].id : "");

  const sources = useMemo<NotionImportSource[]>(() => discovered.data?.sources ?? [], [discovered.data]);
  const counts = useMemo(() => importCounts(sources, mapping), [sources, mapping]);
  const bases = useMemo(() => importPlan(sources, mapping), [sources, mapping]);

  /**
   * What is being imported, and as what. Derived from [bases] rather than from [mapping],
   * so a target still named for a database the workspace search no longer finds cannot make
   * a relation into it look resolvable.
   */
  const kept = useMemo<ImportMapping>(
    () => Object.fromEntries(bases.map((base) => [base.sourceId, base.target])),
    [bases],
  );

  /**
   * The request, built from the three things the steps decided. `columns` and `values` are
   * empty for a base whose schema never arrived — an unmapped base still imports, with
   * every field on its writer's default.
   */
  const plan = useMemo<NotionImportRequest["plan"]>(
    () =>
      bases.map(({ sourceId, target }) => ({
        sourceId,
        target,
        columns: mappings[sourceId]?.columns ?? {},
        values: mappings[sourceId]?.values ?? {},
        fallback: fallbacks[sourceId] ?? {},
      })),
    [bases, mappings, fallbacks],
  );

  /**
   * Does anything in this plan name a person? One boolean, read by the way forwards and by
   * the way back, so the skip cannot disagree with itself. Nothing else can put step 4 on
   * screen: a workspace whose columns name nobody has nobody to match.
   */
  const hasPeople = plan.some((row) => PEOPLE_FIELDS.some((field) => row.columns[field]));

  /** The team is only needed by a plan that writes something outside a team of its own. */
  const teamRequired = plan.some((row) => row.target !== "teams" && !row.fallback.teamId);

  const request: NotionImportRequest = { teamId: destination || undefined, people, plan };

  const preview = useMutation({ mutationFn: () => notionImportApi.preview(request) });
  /**
   * The write, and the one call here that makes one. It does not close the dialog: the
   * outcome is the only report the import produces — what it dropped, what it left alone
   * because it had imported it before — and closing on success would throw it away.
   */
  const confirm = useMutation({ mutationFn: () => notionImportApi.confirm(request) });

  /**
   * Changing what a base becomes drops what was said about its columns.
   *
   * The fields belong to the target: a base read as tickets was asked about status and
   * priority, and the same base read as teams is asked about its parent. Keeping the old
   * answers would send a mapping naming fields the new target never reads, and would leave
   * a header counting them. The schema for the new target arrives and seeds the pre-fill
   * again, which is the answer the reader wanted by changing it.
   */
  const cycle = (source: NotionImportSource) => {
    const order: ImportTarget[] = ["teams", "projects", "tickets", "documents", "ignore"];
    const next = order[(order.indexOf(targetOf(mapping, source)) + 1) % order.length];
    setMapping((current) => ({ ...current, [source.id]: next }));
    setMappings((current) => without(current, source.id));
    setFallbacks((current) => without(current, source.id));
  };

  /**
   * The suggestion seeds a base's mapping the first time its schema arrives, and never
   * again: the guard is here rather than only in the section that calls it, so a second
   * arrival — a refetch, a step walked back into — cannot overwrite what the reader said.
   *
   * `useCallback` because the section seeds from an effect, and a new identity every render
   * would make that effect fire again on every keystroke elsewhere in the dialog.
   */
  const seedMapping = useCallback((sourceId: string, seed: BaseMapping) => {
    setMappings((current) => (current[sourceId] ? current : { ...current, [sourceId]: seed }));
  }, []);

  const setBaseMapping = useCallback((sourceId: string, next: BaseMapping) => {
    setMappings((current) => ({ ...current, [sourceId]: next }));
  }, []);

  const setFallback = useCallback((sourceId: string, next: Fallback) => {
    setFallbacks((current) => ({ ...current, [sourceId]: next }));
  }, []);

  const toPreview = () => preview.mutate(undefined, { onSuccess: () => setStep(5) });

  /*
   * A height, because step 3 is as tall as the workspace is wide: one section per kept
   * base, each with a row per field and a row per option inside it. `Backdrop` clips what
   * overflows — it is shared with the palette and the help panel, where nothing ever does
   * — so the steps scroll inside the panel and the header stays put above them. Without
   * this the buttons of a three-base mapping are off screen.
   */
  return (
    <Backdrop onClose={onClose} panelClassName="flex max-h-[76vh] w-[min(710px,94vw)] flex-col">
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Import from Notion"
        className="flex min-h-0 flex-col"
      >
        <div className="flex items-center gap-2.5 bg-background px-5 py-3 text-12 text-faint">
          <span className="font-medium text-muted-foreground">Import from Notion</span>
          <span className="flex-1" />
          <span>step {step} of 5</span>
        </div>

        <div className="flex flex-col gap-[18px] overflow-y-auto p-5">
          <div className="flex gap-0.5" aria-hidden>
            {[1, 2, 3, 4, 5].map((mark) => (
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
            <StepTwo
              sources={sources}
              mapping={mapping}
              kept={kept}
              mappings={mappings}
              counts={counts}
              planEmpty={plan.length === 0}
              teams={destinations}
              teamId={destination}
              teamRequired={teamRequired}
              onTeam={setTeamId}
              onCycle={cycle}
              onSuggest={(sourceId, target) =>
                setMapping((current) => ({ ...current, [sourceId]: target }))
              }
              onNext={() => setStep(3)}
              onBack={() => setStep(1)}
            />
          )}

          {step === 3 && (
            <StepColumns
              bases={bases}
              kept={kept}
              mappings={mappings}
              fallbacks={fallbacks}
              teams={destinations}
              projects={projects.data ?? []}
              hasPeople={hasPeople}
              onMapping={setBaseMapping}
              onSeed={seedMapping}
              onFallback={setFallback}
              onNext={() => (hasPeople ? setStep(4) : toPreview())}
              onBack={() => setStep(2)}
              pending={preview.isPending}
              error={preview.isError ? actionErrorMessage(preview.error) : undefined}
            />
          )}

          {/*
           * Step 4, the people the mapped columns name. The rows that match them to Kanso
           * accounts are the next task's; what is here is the step itself, so the skip in
           * both directions is real and testable — and so that a reader who mapped an
           * assignee column is told, before the preview, that the question exists.
           */}
          {step === 4 && (
            <>
              <div className="flex flex-col gap-1.5">
                <span className="text-15 font-medium">Who these people are</span>
                <span className="text-12 text-muted-foreground">
                  The columns you mapped name people in Notion. Matching each of them to a Kanso
                  account is filled in once and holds for every later import; anyone left
                  unmatched leaves their rows unassigned rather than guessed at.
                </span>
              </div>
              <div className="flex items-center gap-2.5">
                <Button disabled={preview.isPending} onClick={toPreview}>
                  Preview the import
                </Button>
                <Button variant="outline" onClick={() => setStep(3)}>
                  Back
                </Button>
              </div>
              {preview.isError && (
                <span className="text-12 text-urgent">{actionErrorMessage(preview.error)}</span>
              )}
            </>
          )}

          {step === 5 && (
            <StepThree
              sources={sources}
              mapping={mapping}
              counts={counts}
              preview={preview.data}
              teamName={destinations.find((team) => team.id === destination)?.name}
              result={confirm.data}
              onConfirm={() => confirm.mutate()}
              onClose={onClose}
              onBack={() => setStep(hasPeople ? 4 : 3)}
              pending={confirm.isPending}
              error={confirm.isError ? actionErrorMessage(confirm.error) : undefined}
            />
          )}
        </div>
      </div>
    </Backdrop>
  );
}
