"use client";

import { useCallback, useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notionImportApi, type NotionImportRequest, type NotionImportSource } from "@/lib/api";
import { useProjects, useTeams } from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import { Backdrop } from "@/components/overlays";
import { cn } from "@/lib/utils";
import { importCounts, importPlan, targetOf, type ImportMapping, type ImportTarget } from "./import-map";
import { type BaseMapping, type Fallback } from "./import-columns";
import { ImportConfirm } from "./import-confirm";
import { ImportDetails } from "./import-details";
import { ImportPlan } from "./import-plan";

/**
 * Screen 24. A plan and a confirmation, and nothing written until the second.
 *
 * This file is the shell: the state the two screens share, the requests, and which one is
 * on screen. Three of the five decisions the dialog used to spread across five steps only
 * ever confirmed a guess the server had already made — which bases exist, which column is
 * which field, who these people are — so they are folded into `ImportDetails` instead of
 * costing a screen each. What the two screens left decide:
 *
 * 1. what each database becomes, and where it lands;
 * 2. what all of that would write — the last read before the only write.
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
  const [step, setStep] = useState<1 | 2>(1);
  const [mapping, setMapping] = useState<ImportMapping>({});
  const [teamId, setTeamId] = useState("");
  const [mappings, setMappings] = useState<Record<string, BaseMapping>>({});
  const [fallbacks, setFallbacks] = useState<Record<string, Fallback>>({});
  /**
   * The person correspondence, kept here because the request carries it. `import-step-
   * people.tsx` computes it — `buildAssignments`, not a raw copy of what it shows — and
   * calls `setPeople` with the result on every change; a key this map is missing is one
   * neither a reader nor the correspondence has ever said anything about, and lands
   * unassigned rather than guessed at.
   */
  const [people, setPeople] = useState<Record<string, string | null>>({});
  /** Preview has to stay dead while `StepColumns` or `StepPeople` is still mid-request — see
   *  their own comments for why leaving early would throw away a suggestion nobody chose. */
  const [detailsLoading, setDetailsLoading] = useState(false);

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

  /** The team is only needed by a plan that writes something outside a team of its own. */
  const teamRequired = plan.some((row) => row.target !== "teams" && !row.fallback.teamId);

  const request: NotionImportRequest = { teamId: destination || undefined, people, plan };

  const preview = useMutation({ mutationFn: () => notionImportApi.preview(request) });
  /**
   * The write, and the one call here that makes one. It does not close the dialog: the
   * outcome is the only report the import produces — what it dropped, what it left alone
   * because it had imported it before — and closing on success would throw it away.
   *
   * It does invalidate, though, on every entity an import can create: the reader is left
   * on a screen saying four teams and three hundred tickets were written, and the lists
   * behind it would otherwise still be the ones from before they opened the dialog. By
   * prefix, the way `core.ts`'s own mutations do, so a scope or an archived flag in the
   * key cannot leave one variant stale.
   */
  const client = useQueryClient();
  const confirm = useMutation({
    mutationFn: () => notionImportApi.confirm(request),
    onSuccess: () => {
      for (const entity of ["teams", "projects", "tickets", "docs", "timeline"]) {
        client.invalidateQueries({ queryKey: [entity] });
      }
    },
  });

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

  const toPreview = () => preview.mutate(undefined, { onSuccess: () => setStep(2) });

  /*
   * A height, because the folded panel is as tall as the workspace is wide once it is
   * opened: one section per kept base, each with a row per field and a row per option
   * inside it. `Backdrop` clips what overflows — it is shared with the palette and the
   * help panel, where nothing ever does — so the plan scrolls inside the panel and the
   * header stays put above it. Without this the button of a three-base mapping is off
   * screen.
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
          <span>step {step} of 2</span>
        </div>

        <div className="flex flex-col gap-[18px] overflow-y-auto p-5">
          <div className="flex gap-0.5" aria-hidden>
            {[1, 2].map((mark) => (
              <span
                key={mark}
                className={cn("h-[3px] flex-1", mark <= step ? "bg-primary" : "bg-accent")}
              />
            ))}
          </div>

          {step === 1 && (
            <ImportPlan
              sources={sources}
              loading={discovered.isLoading}
              unavailable={
                discovered.isError
                  ? actionErrorMessage(discovered.error)
                  : discovered.data && !discovered.data.available
                    ? (discovered.data.reason ?? "Kanso cannot read this workspace.")
                    : undefined
              }
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
              onNext={toPreview}
              pending={preview.isPending}
              busy={detailsLoading}
              error={preview.isError ? actionErrorMessage(preview.error) : undefined}
              details={
                <ImportDetails
                  bases={bases}
                  kept={kept}
                  mappings={mappings}
                  fallbacks={fallbacks}
                  teams={destinations}
                  projects={projects.data ?? []}
                  plan={plan}
                  onMapping={setBaseMapping}
                  onSeed={seedMapping}
                  onFallback={setFallback}
                  onPeople={setPeople}
                  onLoading={setDetailsLoading}
                />
              }
            />
          )}

          {step === 2 && (
            <ImportConfirm
              sources={sources}
              mapping={mapping}
              counts={counts}
              preview={preview.data}
              teamName={destinations.find((team) => team.id === destination)?.name}
              result={confirm.data}
              onConfirm={() => confirm.mutate()}
              onClose={onClose}
              onBack={() => setStep(1)}
              pending={confirm.isPending}
              error={confirm.isError ? actionErrorMessage(confirm.error) : undefined}
            />
          )}
        </div>
      </div>
    </Backdrop>
  );
}
