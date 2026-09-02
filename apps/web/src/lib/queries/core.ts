"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useMemo } from "react";
import { patchedTicket, removedTicket, ticketGuesses } from "@/lib/optimistic";
import { queryCache } from "@/lib/realtime-events";
import { useUi, type Scope } from "@/store/ui";
import {
  api,
  filterParams,
  organiseApi,
  DEFAULT_PREFERENCES,
  type EffortPoints,
  type KansoInstant,
  type Me,
  type MemberRole,
  type Preferences,
  type Project,
  type Team,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
  type ViewFilters,
} from "../api";

export const keys = {
  authMode: ["authMode"] as const,
  me: ["me"] as const,
  setupState: ["setupState"] as const,
  users: ["users"] as const,
  people: ["people"] as const,
  invitations: ["invitations"] as const,
  sync: ["sync"] as const,

  // Everything below is keyed on what was asked for, so two different answers
  // never share one cache entry. `applyEvents` finds them on the first segment,
  // which is what lets these keys grow without it having to know about them; the
  // segments after it are how it tells a scoped list from anything else stored there.
  teams: (includeArchived: boolean) => ["teams", includeArchived] as const,
  /** All projects, one query — the sidebar needs the whole set to draw its tree. */
  projects: (includeArchived: boolean) => ["projects", includeArchived] as const,
  /**
   * `asked` is the composed filter set, already spelled as a query string — the whole of
   * what makes two entries under this key different questions.
   *
   * It defaults to the empty string, which is the unfiltered list, so every caller that
   * predates the filter control keys the same entry it always did. `queries/views.ts`
   * relies on exactly that: a project page and the list scoped to that project are
   * deliberately one cache entry, and they would silently stop being one if this
   * segment had no default.
   */
  tickets: (scope: Scope, includeArchived: boolean, asked = "") =>
    ["tickets", scope.kind, scope.kind === "all" ? "" : scope.id, includeArchived, asked] as const,
  contents: (kind: "team" | "project", id: string) => ["contents", kind, id] as const,
  teamMembers: (id: string) => ["teams", id, "members"] as const,
  /** Per team, because a velocity measured against another team's fortnights is a different number. */
  velocity: (teamId: string) => ["velocity", teamId] as const,
  /** Per team for the same reason `velocity` is: it is measured against that calendar. */
  progress: (teamId: string) => ["progress", teamId] as const,
  ticketDuration: (id: string) => ["ticketDuration", id] as const,
  /** No archived flag: the timeline endpoint never returns archived work. */
  timeline: (scope: Scope) =>
    ["timeline", scope.kind, scope.kind === "all" ? "" : scope.id] as const,
};

/**
 * The cache as `lib/realtime-events.ts` and `lib/optimistic.ts` want it.
 *
 * The ticket mutations below write through those two rather than naming a key, because
 * the row they change is in more places than the list on screen — other scopes' lists
 * the reader has visited, and the ticket page's own single-row entry. Naming one key was
 * why a status change on the board left the same ticket's page showing the old status
 * until something refetched it.
 */
const useEventCache = () => {
  const queryClient = useQueryClient();
  return useMemo(() => queryCache(queryClient), [queryClient]);
};

/**
 * Stops the refetches that could land on top of a guess about to be painted.
 *
 * Every ticket key, not just the list on screen, because the guess is painted into all
 * of them — but only the ones that already hold rows. A query still loading for the
 * first time has nothing for the guess to overwrite and nothing to overwrite the guess
 * with, so cancelling it would strand a first load to buy nothing: `writeTickets` skips
 * an entry with no data anyway.
 */
const cancelTicketRefetches = (queryClient: ReturnType<typeof useQueryClient>) =>
  queryClient.cancelQueries({
    queryKey: ["tickets"],
    predicate: (query) => query.state.data !== undefined,
  });

/**
 * A project scope is a `project` filter, because that is the only way this door can
 * express one: `ticketsMatching` takes a team and its descendants as scope, and a
 * project belongs to no team in particular.
 *
 * It overwrites rather than joining what was composed, and cannot collide with it: the
 * filter control does not offer `project` while the list is scoped to one, since the
 * scope has already answered that question. Joining would be worse than either — the
 * server reads two values of one facet as "either", so a project chip added inside a
 * project scope would widen the list past the project whose name is in the header.
 */
const scopedFilters = (filters: ViewFilters, scope: Scope): ViewFilters =>
  scope.kind === "project" ? { ...filters, project: [scope.id] } : filters;

/**
 * The composed filter set as the wire spells it, which is also what tells one cached
 * answer from another.
 *
 * A string rather than the object: a query key is compared structurally and the store
 * hands back a new object on every `setFilters`, so keying on the object would be one
 * cache entry per keystroke of the filter control. `URLSearchParams` also fixes the
 * order, so `status` then `priority` and `priority` then `status` are one question.
 */
const useAskedFilters = () => filterParams(useUi((state) => state.filters)).toString();

export const useAuthMode = () => useQuery({ queryKey: keys.authMode, queryFn: api.authMode });

export const useMe = () =>
  useQuery({ queryKey: keys.me, queryFn: api.me, retry: false });

/**
 * Public and answered before there is a session, so the sign-in screen and the
 * routing guard can both ask whether this instance has ever been set up. `retry:
 * false` because the interesting failure — a backend that predates the wizard —
 * is a 404, and retrying a 404 only delays the answer.
 */
export const useSetupState = () =>
  useQuery({
    queryKey: keys.setupState,
    queryFn: api.setupState,
    retry: false,
    // The wizard watches this change step by step, so the client-wide 30s
    // staleTime would show it a stale answer to the question it just resolved.
    staleTime: 0,
  });

/**
 * Preferences ride along with /api/me, so this reads that cache instead of spending
 * a second round trip on data already in hand; /api/me/preferences exists for the
 * write. Always resolves to a complete object — appearance has no loading state,
 * only a default.
 */
export function usePreferences(): Preferences {
  const me = useMe();
  return me.data?.preferences ?? DEFAULT_PREFERENCES;
}

export type SavePreferencesInput = Partial<Preferences> & {
  onboarded?: boolean;
  /**
   * Names the fields to clear. JSON cannot tell an omitted key from an explicit null, so
   * withdrawing a declared velocity is impossible without saying which field it is.
   */
  unset?: string[];
};

/** The optimistic half of `unset`: every named field, gone. */
const cleared = (unset: string[] | undefined): Partial<Preferences> =>
  Object.fromEntries((unset ?? []).map((field) => [field, undefined]));

/**
 * Optimistic save, for the same reason ticket patches are: a theme that takes a
 * round trip to change feels broken. The cache is the single source of truth for
 * appearance, so writing it here is what repaints the interface; a failure puts the
 * previous object back and the interface visibly reverts.
 */
export function useSavePreferences() {
  const queryClient = useQueryClient();

  return useMutation({
    mutationFn: api.savePreferences,

    onMutate: async ({ unset, ...patch }: SavePreferencesInput) => {
      await queryClient.cancelQueries({ queryKey: keys.me });
      const previous = queryClient.getQueryData<Me>(keys.me);
      if (previous) {
        queryClient.setQueryData<Me>(keys.me, {
          ...previous,
          // `unset` is a command, not a field, so it is applied rather than merged: the
          // guess has to clear what the request clears, or the interface shows the old
          // value until the response lands and then jumps.
          preferences: { ...previous.preferences, ...cleared(unset), ...patch },
        });
      }
      return { previous };
    },

    onError: (_error, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(keys.me, context.previous);
    },

    // The response is the whole saved row, so take it rather than invalidate: a
    // refetch of /api/me on every keystroke-sized change would be pure noise.
    onSuccess: (saved, patch) => {
      queryClient.setQueryData<Me>(keys.me, (current) =>
        current ? { ...current, preferences: saved } : current,
      );
      // The declared velocity is the one preference that is an *input* to reads living in
      // other caches: which velocity is in force, every ticket's duration, and now the
      // progress page — where it decides both the headline sentence and how many days the
      // plate is said to weigh. All three are computed on the server from it, so none can
      // be patched from the response here.
      // Conditional rather than a blanket invalidate, because a theme change moves nothing.
      if ("declaredVelocity" in patch || (patch.unset ?? []).includes("declaredVelocity")) {
        queryClient.invalidateQueries({ queryKey: ["velocity"] });
        queryClient.invalidateQueries({ queryKey: ["ticketDuration"] });
        queryClient.invalidateQueries({ queryKey: ["progress"] });
      }
    },
  });
}

/**
 * Which velocity Kanso will plan this person's dates with, and where it came from.
 *
 * Not folded into `/api/me` the way preferences are: the answer depends on how many of a
 * team's cycles have closed, so it is per team and it changes without this person touching
 * anything. A field on the session object would be stale the morning after a cycle closes.
 */
export const useVelocity = (teamId?: string) =>
  useQuery({
    queryKey: keys.velocity(teamId ?? ""),
    queryFn: () => api.velocity(teamId!),
    enabled: Boolean(teamId),
  });

/**
 * The whole of one person's progress page, in one key.
 *
 * Not composed out of `useVelocity` and a workload read. The pace, the delivered cycles
 * and the plate are one question asked of one snapshot, and three caches refetching
 * independently would eventually draw a chart of three closed cycles under a sentence
 * saying the number stands on two. One request, one cache entry, one moment.
 */
export const useProgress = (teamId?: string) =>
  useQuery({
    queryKey: keys.progress(teamId ?? ""),
    queryFn: () => api.progress(teamId!),
    enabled: Boolean(teamId),
  });

/**
 * How long one ticket should take. Detail surfaces only — the list never asks.
 *
 * Its own key rather than a field on the ticket cache, because it moves for reasons the
 * ticket does not: an assignee closing a cycle changes this number and nothing about the
 * row. Left to the client-wide staleTime, since a duration nobody can read to the day does
 * not need to be fresh to the second.
 */
export const useTicketDuration = (id?: string) =>
  useQuery({
    queryKey: keys.ticketDuration(id ?? ""),
    queryFn: () => api.ticketDuration(id!),
    enabled: Boolean(id),
  });

export const useTeams = () => {
  const showArchived = useUi((state) => state.showArchived);
  return useQuery({ queryKey: keys.teams(showArchived), queryFn: () => api.teams(showArchived) });
};

export const useUsers = () => useQuery({ queryKey: keys.users, queryFn: api.users });

export const usePeople = () => useQuery({ queryKey: keys.people, queryFn: api.people });

/**
 * Only the owner and admins may read this, so it is asked for explicitly rather
 * than mounted everywhere: a 403 in the background of the inbox is noise.
 */
export const usePendingInvitations = (enabled: boolean) =>
  useQuery({
    queryKey: keys.invitations,
    queryFn: api.pendingInvitations,
    enabled,
    retry: false,
  });

export const useProjects = () => {
  const showArchived = useUi((state) => state.showArchived);
  return useQuery({
    queryKey: keys.projects(showArchived),
    queryFn: () => api.projects({ includeArchived: showArchived }),
  });
};

/**
 * The caller's own drafts, folded into the unscoped list and into nothing else.
 *
 * The server keeps tickets with no team out of every list it serves, and that is right for
 * all but one of them: a board, a saved view, a cycle, a triage queue and a timeline are
 * each a room a team owns, and a draft is in none of those rooms. "All" is not a room. It
 * is the only place a draft can be seen at all, so leaving it out would make the feature
 * write-only — you could file a thought and never find it again.
 *
 * Merged here rather than widened on the server, because the two halves answer to two
 * different rules: the list is scoped by team, and the drafts are scoped by who wrote them.
 * One query cannot honestly be both, and a predicate that tried would be one every other
 * caller of it inherits.
 *
 * Not for a composed question: the facets are validated and run server-side, and a client
 * that appended rows the filter never saw would show a list that does not match its chips.
 */
const withOwnDrafts =
  (scope: Scope, asked: string) =>
  async (rows: Ticket[]): Promise<Ticket[]> => {
    if (scope.kind !== "all" || asked !== "") return rows;
    const drafts = await api.drafts().catch(() => [] as Ticket[]);
    // Newest first, ahead of the rest: a draft is the thing most recently typed and least
    // likely to be found by scrolling for a name it does not have.
    return [...drafts, ...rows];
  };

/**
 * The main list — a saved view nobody saved.
 *
 * Two doors onto one question, and which one is used is decided by whether anything has
 * been composed. Unfiltered, it is `api.tickets` exactly as it always was, so the entry
 * `queries/views.ts` shares stays shared and the four mutation hooks below go on writing
 * it optimistically. Composed, it is `organiseApi.ticketsMatching`, which spells the
 * scope the same way and the facets in the vocabulary the server validates.
 */
export const useTickets = () => {
  const scope = useUi((state) => state.scope);
  const showArchived = useUi((state) => state.showArchived);
  const filters = useUi((state) => state.filters);
  const asked = useAskedFilters();
  return useQuery({
    queryKey: keys.tickets(scope, showArchived, asked),
    queryFn: () =>
      asked === ""
        ? api.tickets(scope, showArchived).then(withOwnDrafts(scope, asked))
        : organiseApi.ticketsMatching(scopedFilters(filters, scope), {
            // The same scope the unfiltered door sends, so the two answer about the same
            // room: a team scope reaches its descendants, a project scope names no team.
            teamId: scope.kind === "team" ? scope.id : undefined,
            includeDescendants: scope.kind === "team" ? true : undefined,
            includeArchived: showArchived,
            limit: 200,
          }),
  });
};

/**
 * One query for the whole screen. Bounds, slack, criticality and arrows are computed
 * together over the same dependency closure on the server, so asking for them
 * separately would mean walking that closure more than once.
 *
 * `enabled` so the list view does not pay for a query nothing renders.
 */
export const useTimeline = (enabled: boolean) => {
  const scope = useUi((state) => state.scope);
  return useQuery({
    queryKey: keys.timeline(scope),
    queryFn: () => api.timeline(scope),
    enabled,
  });
};

/**
 * What a team or a project holds. The disposition modal exists to say what is in
 * there *now*, so a cached count is the one answer it must never be given.
 */
export const useContents = (kind: "team" | "project", id: string) =>
  useQuery({
    queryKey: keys.contents(kind, id),
    queryFn: () => (kind === "team" ? api.teamContents(id) : api.projectContents(id)),
    staleTime: 0,
    gcTime: 0,
  });

export const useSyncStatus = () =>
  useQuery({ queryKey: keys.sync, queryFn: api.syncStatus, refetchInterval: 10_000 });

export const useTeamMembers = (teamId: string) =>
  useQuery({ queryKey: keys.teamMembers(teamId), queryFn: () => api.teamMembers(teamId) });

export const useAddTeamMember = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ userId, role }: { userId: string; role: MemberRole }) =>
      api.addTeamMember(teamId, userId, role),
    onSettled: () => client.invalidateQueries({ queryKey: keys.teamMembers(teamId) }),
  });
};

export const useRemoveTeamMember = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (userId: string) => api.removeTeamMember(teamId, userId),
    // The timeline's `editable` is the server's answer to this same membership, so a
    // removal that did not invalidate it would leave handles on bars the viewer can no
    // longer move.
    onSettled: () => {
      client.invalidateQueries({ queryKey: keys.teamMembers(teamId) });
      client.invalidateQueries({ queryKey: ["timeline"] });
    },
  });
};

/**
 * The fields a patch may carry. `start` and `due` were missing, so a dragged bar's
 * new dates reached the server through an unchecked `Record<string, unknown>` — and
 * a cleared one was not applied optimistically at all.
 *
 * Neither date is nullable here on purpose: `TicketPatchRequest` reads an explicit
 * `null` as "leave unchanged", exactly like an absent key. `unset: ["due"]` is the
 * only thing that clears a bound, so offering `due: null` would type-check a call
 * that silently does nothing.
 */
export type PatchInput = {
  id: string;
  status?: TicketStatus;
  priority?: TicketPriority;
  /** Not nullable either, and for the same reason: `unset: ["estimate"]` is the only clear. */
  estimate?: EffortPoints;
  title?: string;
  description?: string;
  archived?: boolean;
  start?: KansoInstant;
  due?: KansoInstant;
  unset?: string[];
};

/**
 * Optimistic patch.
 *
 * The point of Kanso is that a status change feels instant, so the cache is rewritten
 * before the request leaves. Every field here is one the server stores as it is sent, so
 * the guess is not a guess about behaviour — only about whether the write is allowed.
 *
 * The guess goes through `lib/optimistic.ts`, which paints it into every cached entry
 * holding the row rather than into the one list this hook knows a key for, and which
 * stacks two edits of the same row instead of snapshotting one on top of the other.
 *
 * The list is no longer refetched when the request comes back, and that is the change
 * worth explaining. `/api/tickets` answers in `updatedAt DESC`, so a refetch moved the
 * row that was just edited to the top of the list — a jump, a scroll-position change and
 * a repaint, every time anyone pressed a status key. `applyEvents` deliberately patches
 * a changed row *in place* rather than reordering, so the socket's version of this same
 * edit does not move it; refetching here was the one thing that did. What the row now
 * says is the response itself, which is the whole saved row and needs no second ask.
 */
export function usePatchTicket() {
  const queryClient = useQueryClient();
  const cache = useEventCache();

  return useMutation({
    mutationFn: ({ id, ...body }: PatchInput) => api.patchTicket(id, body),

    // `unset` is destructured out of `body` here and nowhere else: the request needs
    // it, the cached ticket has no such field, and spreading it would leave a stray
    // array on the row.
    onMutate: async ({ id, unset, ...body }) => {
      await cancelTicketRefetches(queryClient);
      return { handle: ticketGuesses.open(cache, id, patchedTicket(body, unset ?? [])) };
    },

    // One call for both outcomes, which is the point of the ledger: the response row
    // becomes the new base, and its absence — a refusal — leaves the base as it was.
    // Either way what is repainted is the base with whatever else is still in flight
    // folded back over it, so a second edit of the same row survives this one failing.
    onSettled: (saved, _error, _input, context) => {
      if (context) ticketGuesses.close(cache, context.handle, saved);
      // A patch may have cascaded into tickets this mutation never named, and it
      // changes slack and criticality for others that did not move at all.
      queryClient.invalidateQueries({ queryKey: ["timeline"] });
    },
  });
}

/**
 * Drawing an arrow. The response carries the tickets the new constraint moved, but the
 * timeline is refetched rather than patched from it: the same edit also changes slack
 * and criticality for tickets that did not move at all.
 *
 * Not optimistic, and this is the clearest case for leaving one alone. A dependency runs
 * the scheduler: it moves the successor, then whatever waits on the successor, and it
 * recomputes slack and criticality across the whole closure. Guessing at that means
 * reimplementing the scheduler in the browser, and guessing at it *badly* means bars
 * sliding to the wrong dates and then sliding again when the answer lands. The arrow
 * itself would be the only honest part of the guess, and it is not the part anyone is
 * waiting to see.
 */
export function useLinkDependency() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      successorId,
      predecessorId,
    }: {
      successorId: string;
      predecessorId: string;
    }) => api.linkDependency(successorId, predecessorId),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["timeline"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
}

/** Erasing one. Nothing moves back: freeing slack does not pull work earlier. */
export function useUnlinkDependency() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      successorId,
      predecessorId,
    }: {
      successorId: string;
      predecessorId: string;
    }) => api.unlinkDependency(successorId, predecessorId),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: ["timeline"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
}

/**
 * Deliberately not optimistic, and the clearest case of it.
 *
 * A ticket's identifier comes from a per-team counter the server owns — `KAN-142` is not
 * something this client can invent, and a row drawn with a placeholder key that turns
 * into a different one a moment later is worse than a row that appears a moment late.
 * Its position is the server's too: the list is ordered on `updatedAt`, which does not
 * exist yet either.
 *
 * So the row is not guessed, it is *placed*, from the response — through the same writer
 * the socket uses, which refetches only the cached lists that have to gain it.
 */
export function useCreateTicket() {
  const queryClient = useQueryClient();
  const cache = useEventCache();
  return useMutation({
    mutationFn: api.createTicket,
    onSuccess: (created) => {
      ticketGuesses.arrived(cache, created);
      // The team row carries a ticket count, so it is stale too.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
    },
  });
}

/**
 * Optimistic, because a deletion the person confirmed is one they have already decided:
 * the row leaves every list holding it and the ticket page falls through to the 404 it
 * was written for. A refusal puts it back — through a refetch of the keys that lost it,
 * because where a row sorts back in is the server's order and not this client's guess.
 */
export function useDeleteTicket() {
  const queryClient = useQueryClient();
  const cache = useEventCache();
  return useMutation({
    mutationFn: api.deleteTicket,
    onMutate: async (id: string) => {
      await cancelTicketRefetches(queryClient);
      return { handle: ticketGuesses.open(cache, id, removedTicket) };
    },
    // `null` rather than "no answer": the endpoint returns nothing, so success has to say
    // in so many words that the row is gone, or settling would put it back.
    onSettled: (_data, error, _id, context) => {
      if (context) ticketGuesses.close(cache, context.handle, error ? undefined : null);
      queryClient.invalidateQueries({ queryKey: ["timeline"] });
    },
  });
}

/**
 * One hook for both entities: unarchiving asks nothing, so the only thing that
 * differs between a team and a project is which endpoint is called. Unarchiving a
 * team also unarchives its ancestors, which is why the ticket lists go stale too.
 */
export function useUnarchive() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (target: { kind: "team" | "project"; id: string }): Promise<Team | Project> =>
      target.kind === "team" ? api.unarchiveTeam(target.id) : api.unarchiveProject(target.id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
}

// What a realtime event does to these keys lives in `lib/realtime-events.ts`, beside the
// subscription that delivers it and away from React, where it is testable.
