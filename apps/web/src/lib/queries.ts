"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import { useUi, type Scope } from "@/store/ui";
import {
  api,
  DEFAULT_PREFERENCES,
  type Me,
  type Preferences,
  type Project,
  type Team,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
} from "./api";

export const keys = {
  authMode: ["authMode"] as const,
  me: ["me"] as const,
  setupState: ["setupState"] as const,
  users: ["users"] as const,
  people: ["people"] as const,
  invitations: ["invitations"] as const,
  sync: ["sync"] as const,

  // Everything below is keyed on what was asked for, so two different answers
  // never share one cache entry. `applyEvent` invalidates on the first segment,
  // which is what lets these keys grow without it having to know about them.
  teams: (includeArchived: boolean) => ["teams", includeArchived] as const,
  /** All projects, one query — the sidebar needs the whole set to draw its tree. */
  projects: (includeArchived: boolean) => ["projects", includeArchived] as const,
  tickets: (scope: Scope, includeArchived: boolean) =>
    ["tickets", scope.kind, scope.kind === "all" ? "" : scope.id, includeArchived] as const,
  contents: (kind: "team" | "project", id: string) => ["contents", kind, id] as const,
};

/**
 * The scope and the archived toggle live in the store, not in props, so every
 * caller of these hooks agrees on what is being shown without passing it down.
 */
const useTicketsKey = () =>
  keys.tickets(
    useUi((state) => state.scope),
    useUi((state) => state.showArchived),
  );

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

    onMutate: async (patch: Partial<Preferences>) => {
      await queryClient.cancelQueries({ queryKey: keys.me });
      const previous = queryClient.getQueryData<Me>(keys.me);
      if (previous) {
        queryClient.setQueryData<Me>(keys.me, {
          ...previous,
          preferences: { ...previous.preferences, ...patch },
        });
      }
      return { previous };
    },

    onError: (_error, _patch, context) => {
      if (context?.previous) queryClient.setQueryData(keys.me, context.previous);
    },

    // The response is the whole saved row, so take it rather than invalidate: a
    // refetch of /api/me on every keystroke-sized change would be pure noise.
    onSuccess: (saved) =>
      queryClient.setQueryData<Me>(keys.me, (current) =>
        current ? { ...current, preferences: saved } : current,
      ),
  });
}

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

export const useTickets = () => {
  const scope = useUi((state) => state.scope);
  const showArchived = useUi((state) => state.showArchived);
  return useQuery({
    queryKey: keys.tickets(scope, showArchived),
    queryFn: () => api.tickets(scope, showArchived),
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

type PatchInput = {
  id: string;
  status?: TicketStatus;
  priority?: TicketPriority;
  title?: string;
  description?: string;
  archived?: boolean;
  unset?: string[];
};

/**
 * Optimistic patch.
 *
 * The point of Kanso is that a status change feels instant, so the cache is
 * rewritten before the request leaves. On failure the snapshot is restored — the
 * row visibly snaps back, which is the honest signal that the change did not land.
 */
export function usePatchTicket() {
  const queryClient = useQueryClient();
  const key = useTicketsKey();

  return useMutation({
    mutationFn: ({ id, ...body }: PatchInput) => api.patchTicket(id, body),

    onMutate: async ({ id, ...body }) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<Ticket[]>(key);

      queryClient.setQueryData<Ticket[]>(key, (current) =>
        (current ?? []).map((ticket) =>
          ticket.id === id
            ? {
                ...ticket,
                ...body,
                // The mirror is asynchronous by design: the moment a row changes
                // locally, Notion is behind. Show that rather than imply it landed.
                mirror: { ...ticket.mirror, state: ticket.mirror.state === "disabled" ? "disabled" : "pending" },
              }
            : ticket,
        ),
      );
      return { previous };
    },

    onError: (_error, _input, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
    },

    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
  });
}

export function useCreateTicket() {
  const queryClient = useQueryClient();
  const key = useTicketsKey();
  return useMutation({
    mutationFn: api.createTicket,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: key });
      // The team row carries a ticket count, so it is stale too.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
    },
  });
}

export function useDeleteTicket() {
  const queryClient = useQueryClient();
  const key = useTicketsKey();
  return useMutation({
    mutationFn: api.deleteTicket,
    onMutate: async (id: string) => {
      await queryClient.cancelQueries({ queryKey: key });
      const previous = queryClient.getQueryData<Ticket[]>(key);
      queryClient.setQueryData<Ticket[]>(key, (current) =>
        (current ?? []).filter((ticket) => ticket.id !== id),
      );
      return { previous };
    },
    onError: (_error, _id, context) => {
      if (context?.previous) queryClient.setQueryData(key, context.previous);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: key }),
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

/**
 * Applies a realtime event to the cache. Same effect whoever caused it.
 *
 * Matched on the first key segment, so every variant of a list — archived shown
 * or not, whichever scope — is invalidated by one call.
 */
export function applyEvent(queryClient: QueryClient, entity: string) {
  if (entity === "tickets") {
    queryClient.invalidateQueries({ queryKey: ["tickets"] });
  } else if (entity === "projects") {
    queryClient.invalidateQueries({ queryKey: ["projects"] });
  } else if (entity === "teams") {
    queryClient.invalidateQueries({ queryKey: ["teams"] });
  }
}
