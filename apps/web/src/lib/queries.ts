"use client";

import {
  useMutation,
  useQuery,
  useQueryClient,
  type QueryClient,
} from "@tanstack/react-query";
import {
  api,
  DEFAULT_PREFERENCES,
  type Me,
  type Preferences,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
} from "./api";

export const keys = {
  authMode: ["authMode"] as const,
  me: ["me"] as const,
  setupState: ["setupState"] as const,
  teams: ["teams"] as const,
  users: ["users"] as const,
  people: ["people"] as const,
  invitations: ["invitations"] as const,
  projects: (teamId?: string) => ["projects", teamId ?? "all"] as const,
  tickets: (teamId?: string) => ["tickets", teamId ?? "all"] as const,
  sync: ["sync"] as const,
};

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

export const useTeams = () => useQuery({ queryKey: keys.teams, queryFn: api.teams });

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

export const useProjects = (teamId?: string) =>
  useQuery({ queryKey: keys.projects(teamId), queryFn: () => api.projects(teamId) });

export const useTickets = (teamId?: string) =>
  useQuery({ queryKey: keys.tickets(teamId), queryFn: () => api.tickets(teamId) });

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
export function usePatchTicket(teamId?: string) {
  const queryClient = useQueryClient();
  const key = keys.tickets(teamId);

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

export function useCreateTicket(teamId?: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: api.createTicket,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.tickets(teamId) });
      queryClient.invalidateQueries({ queryKey: keys.teams });
    },
  });
}

export function useDeleteTicket(teamId?: string) {
  const queryClient = useQueryClient();
  const key = keys.tickets(teamId);
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

/** Applies a realtime event to the cache. Same effect whoever caused it. */
export function applyEvent(queryClient: QueryClient, entity: string) {
  if (entity === "tickets") {
    queryClient.invalidateQueries({ queryKey: ["tickets"] });
  } else if (entity === "projects") {
    queryClient.invalidateQueries({ queryKey: ["projects"] });
  } else if (entity === "teams") {
    queryClient.invalidateQueries({ queryKey: keys.teams });
  }
}
