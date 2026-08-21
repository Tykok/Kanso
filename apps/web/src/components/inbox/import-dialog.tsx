"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { notionImportApi, type NotionImportSource } from "@/lib/api";
import { useTeams } from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import { Backdrop } from "@/components/overlays";
import { cn } from "@/lib/utils";
import { importCounts, importPlan, targetOf, type ImportMapping, type ImportTarget } from "./import-map";
import { StepOne } from "./import-step-one";
import { StepTwo } from "./import-step-two";
import { StepThree } from "./import-step-three";

/**
 * Screen 24. Three steps, and nothing written until the third.
 *
 * This file is the shell: the state the three steps share, the three requests, and which
 * step is on screen. Each step is its own file — the second one is the drawing's whole
 * screen and the other two exist to make it safe to press.
 *
 * `DEFAULT_TARGET` is `ignore`, so the count starts at zero and only rises as decisions
 * are made; the other default writes the whole workspace for anybody who clicks through.
 */
export function ImportDialog({ onClose }: { onClose: () => void }) {
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [mapping, setMapping] = useState<ImportMapping>({});
  const [teamId, setTeamId] = useState("");

  const discovered = useQuery({
    queryKey: ["notion-import-sources"],
    queryFn: notionImportApi.sources,
    retry: false,
  });

  const teams = useTeams();

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
  const plan = useMemo(
    () => importPlan(sources, mapping).map(({ sourceId, target }) => ({ sourceId, target })),
    [sources, mapping],
  );

  const preview = useMutation({ mutationFn: () => notionImportApi.preview(plan) });
  const confirm = useMutation({
    mutationFn: () => notionImportApi.confirm(destination, plan),
    onSuccess: onClose,
  });

  const cycle = (source: NotionImportSource) => {
    const order: ImportTarget[] = ["teams", "projects", "tickets", "documents", "ignore"];
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
            <StepTwo
              sources={sources}
              mapping={mapping}
              counts={counts}
              planEmpty={plan.length === 0}
              teams={destinations}
              teamId={destination}
              onTeam={setTeamId}
              onCycle={cycle}
              onPreview={() => preview.mutate(undefined, { onSuccess: () => setStep(3) })}
              onBack={() => setStep(1)}
              pending={preview.isPending}
              error={preview.isError ? actionErrorMessage(preview.error) : undefined}
            />
          )}

          {step === 3 && (
            <StepThree
              sources={sources}
              mapping={mapping}
              counts={counts}
              preview={preview.data}
              teamName={destinations.find((team) => team.id === destination)?.name}
              onConfirm={() => confirm.mutate()}
              onBack={() => setStep(2)}
              pending={confirm.isPending}
              error={confirm.isError ? actionErrorMessage(confirm.error) : undefined}
            />
          )}
        </div>
      </div>
    </Backdrop>
  );
}
