import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { api, type ProjectHealth } from "../api";
import { socialKeys } from "./social";

/**
 * The health updates on one project.
 *
 * Its own file with its own key, rather than another entry in `keys` in `queries/core.ts`
 * — the same call `socialKeys` makes and for the same reason: seven branches are open on
 * this repository and that object is the one every one of them would otherwise edit. The
 * first segment is what matters, since `applyEvents` in `lib/realtime-events.ts` matches
 * on it; nothing publishes an event for an update yet, deliberately, so this only grows
 * into that channel the day the server does.
 */
export const projectUpdateKeys = {
  updates: (projectId: string) => ["project-updates", projectId] as const,
};

export const useProjectUpdates = (projectId: string | undefined) =>
  useQuery({
    queryKey: projectUpdateKeys.updates(projectId ?? ""),
    queryFn: () => api.projectUpdates(projectId as string),
    enabled: projectId !== undefined,
  });

/**
 * Posting one, and the three caches it moves.
 *
 * Not optimistic, for the reason `useCreateComment` is not: the server dates the row and
 * resolves the author, so a guess here would be a second implementation of facts the
 * server owns — visibly wrong for exactly as long as the request takes, over a control
 * somebody presses once a week.
 *
 * `projects` is invalidated as well as the history, because the current health lives on
 * the project — derived from the newest of these rows, never stored — so every list
 * drawing a project is now showing a stale one. And the activity feed, because the server
 * writes a `health_posted` row in the same transaction.
 */
export const usePostProjectUpdate = (projectId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { health: ProjectHealth; body: string }) =>
      api.postProjectUpdate(projectId, body),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: projectUpdateKeys.updates(projectId) });
      // Every `projects` entry, archived or not: the key carries that flag and this
      // project is in whichever one the reader is looking at.
      client.invalidateQueries({ queryKey: ["projects"] });
      client.invalidateQueries({ queryKey: socialKeys.activity("project", projectId) });
    },
  });
};
