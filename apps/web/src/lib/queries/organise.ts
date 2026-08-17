"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  organiseApi,
  type BulkEdit,
  type TriageDecision,
  type ViewFilters,
  type ViewGroupBy,
  type ViewSortBy,
} from "@/lib/api";

/**
 * Slice C's keys, in their own object rather than added to `core`'s `keys`.
 *
 * `queries/index.ts` re-exports both files with `export *`, so a second `keys` would
 * shadow one of them silently. A separate name is also what lets an invalidation say which
 * slice it is about — `organiseKeys.cycles(teamId)` reads as a cycle question, and
 * `["cycles"]` alone would not.
 */
export const organiseKeys = {
  cycles: (teamId: string) => ["cycles", teamId] as const,
  cycleReport: (cycleId: string) => ["cycleReport", cycleId] as const,
  currentCycle: (teamId: string) => ["cycleReport", "current", teamId] as const,
  cycleByNumber: (teamId: string, number: number) => ["cycleReport", teamId, number] as const,
  triage: (teamId: string) => ["triage", teamId] as const,
  triageDecisions: (teamId: string) => ["triage", teamId, "decisions"] as const,
  similar: (ticketId: string) => ["similar", ticketId] as const,
  views: (teamId: string) => ["views", teamId] as const,
  view: (id: string) => ["views", "one", id] as const,
  viewTickets: (id: string) => ["views", "one", id, "tickets"] as const,
  workload: (teamId: string, cycleId?: string) => ["workload", teamId, cycleId ?? ""] as const,
};

/**
 * Everything slice C writes touches the ticket list too — a triage decision changes a
 * status, a bulk edit changes six, a cycle move changes what the cycle contains. Rather
 * than have every mutation name every key, they all call this: the four organising
 * families plus the two `core` families that draw the same rows.
 *
 * Invalidating on the first segment only, which is what `applyEvent` in `core` already
 * does, so a key can grow a segment without this having to learn about it.
 */
function invalidateOrganise(queryClient: ReturnType<typeof useQueryClient>) {
  // `labels` because the strip's sixth button changes what the selected rows wear, and a
  // ticket page left open beside the list would otherwise keep drawing the old pills.
  for (const family of [
    "cycles",
    "cycleReport",
    "triage",
    "views",
    "workload",
    "tickets",
    "timeline",
    "labels",
  ]) {
    queryClient.invalidateQueries({ queryKey: [family] });
  }
}

// --- cycles ---------------------------------------------------------------

export const useCycles = (teamId?: string) =>
  useQuery({
    queryKey: organiseKeys.cycles(teamId ?? ""),
    queryFn: () => organiseApi.cycles(teamId!),
    enabled: Boolean(teamId),
  });

/**
 * The cycle the URL asked for. `/cycles/current` is a real destination — the sidebar links
 * to it — so "which number" is a question only the server can answer, and `number` being
 * the literal `current` is not an error case.
 */
export function useCycleReport(teamId: string | undefined, number: string | undefined) {
  const parsed = number === undefined || number === "current" ? undefined : Number(number);
  return useQuery({
    queryKey:
      parsed === undefined
        ? organiseKeys.currentCycle(teamId ?? "")
        : organiseKeys.cycleByNumber(teamId ?? "", parsed),
    queryFn: () =>
      parsed === undefined
        ? organiseApi.currentCycle(teamId!)
        : organiseApi.cycleByNumber(teamId!, parsed),
    enabled: Boolean(teamId) && (parsed === undefined || Number.isFinite(parsed)),
    // A cycle with no work in it 404s rather than rendering an empty chart, and retrying a
    // 404 only delays the empty state the page has to draw anyway.
    retry: false,
  });
}

export function usePlaceInCycle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ cycleId, ticketIds }: { cycleId: string; ticketIds: string[] }) =>
      organiseApi.placeInCycle(cycleId, ticketIds),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

// --- triage ---------------------------------------------------------------

export const useTriageQueue = (teamId?: string) =>
  useQuery({
    queryKey: organiseKeys.triage(teamId ?? ""),
    queryFn: () => organiseApi.triage(teamId!),
    enabled: Boolean(teamId),
  });

/**
 * The similarity panel for one ticket. Keyed on the ticket rather than on the queue, so
 * moving to the next one is a cache hit the second time somebody walks back with `k`.
 */
export const useSimilar = (ticketId?: string) =>
  useQuery({
    queryKey: organiseKeys.similar(ticketId ?? ""),
    queryFn: () => organiseApi.similar(ticketId!),
    enabled: Boolean(ticketId),
  });

export function useDecide() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { ticketId: string; decision: TriageDecision; duplicateOfId?: string }) =>
      organiseApi.decide(body),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

// --- saved views ----------------------------------------------------------

export const useSavedViews = (teamId?: string) =>
  useQuery({
    queryKey: organiseKeys.views(teamId ?? ""),
    queryFn: () => organiseApi.views(teamId!),
    enabled: Boolean(teamId),
  });

export const useSavedView = (id?: string) =>
  useQuery({
    queryKey: organiseKeys.view(id ?? ""),
    queryFn: () => organiseApi.view(id!),
    enabled: Boolean(id),
  });

export const useViewTickets = (id?: string) =>
  useQuery({
    queryKey: organiseKeys.viewTickets(id ?? ""),
    queryFn: () => organiseApi.viewTickets(id!),
    enabled: Boolean(id),
  });

export function useCreateView() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      teamId,
      ...body
    }: {
      teamId: string;
      name: string;
      shared?: boolean;
      filters?: ViewFilters;
      groupBy?: ViewGroupBy;
      sortBy?: ViewSortBy;
    }) => organiseApi.createView(teamId, body),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

export function usePatchView() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      ...body
    }: {
      id: string;
      name?: string;
      shared?: boolean;
      filters?: ViewFilters;
      groupBy?: ViewGroupBy;
      sortBy?: ViewSortBy;
    }) => organiseApi.patchView(id, body),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

export function useDeleteView() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => organiseApi.deleteView(id),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

// --- bulk edit ------------------------------------------------------------

/**
 * Not optimistic, unlike `usePatchTicket`. A bulk edit is refused whole when one row in
 * the selection is forbidden, so painting six rows as changed and then snapping all six
 * back would be a worse lie than a moment of latency.
 */
export function useBulkEdit() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (edit: BulkEdit) => organiseApi.bulkEdit(edit),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

export function useBulkDelete() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (ticketIds: string[]) => organiseApi.bulkDelete(ticketIds),
    onSuccess: () => invalidateOrganise(queryClient),
  });
}

// --- workload -------------------------------------------------------------

export const useWorkload = (teamId?: string, cycleId?: string) =>
  useQuery({
    queryKey: organiseKeys.workload(teamId ?? "", cycleId),
    queryFn: () => organiseApi.workload(teamId!, cycleId),
    enabled: Boolean(teamId),
  });
