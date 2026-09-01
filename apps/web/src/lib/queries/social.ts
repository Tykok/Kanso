import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { Guesses, wornLabels } from "@/lib/optimistic";
import { socialApi, type ActivityEntity, type Label, type LabelColour } from "../api/social";

/**
 * Keys for the three shared reads.
 *
 * Declared here rather than added to `keys` in `queries/core.ts`, which six branches would
 * otherwise all edit. The first segment is what matters: `applyEvents` in
 * `lib/realtime-events.ts` matches on it, so these grow into the realtime channel the
 * day the server publishes an event for a comment — which it does not yet, deliberately.
 */
export const socialKeys = {
  activity: (entityType: ActivityEntity, entityId: string) =>
    ["activity", entityType, entityId] as const,
  comments: (ticketId: string) => ["comments", ticketId] as const,
  teamLabels: (teamId: string) => ["labels", "team", teamId] as const,
  ticketLabels: (ticketId: string) => ["labels", "ticket", ticketId] as const,
};

export const useActivity = (entityType: ActivityEntity, entityId: string | undefined) =>
  useQuery({
    queryKey: socialKeys.activity(entityType, entityId ?? ""),
    queryFn: () => socialApi.activity(entityType, entityId as string),
    enabled: entityId !== undefined,
  });

export const useComments = (ticketId: string | undefined) =>
  useQuery({
    queryKey: socialKeys.comments(ticketId ?? ""),
    queryFn: () => socialApi.comments(ticketId as string),
    enabled: ticketId !== undefined,
  });

/**
 * Writing a comment also writes an activity row on the server, so both caches are dropped.
 * Not optimistic: the server resolves the `@handles` into the mentions the comment will
 * carry, and a client guess at that list would be a second implementation of a rule the
 * server already owns — visibly wrong for exactly as long as the request takes.
 */
export const useCreateComment = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: socialApi.comment,
    onSuccess: (_comment, variables) => {
      if (variables.ticketId) {
        client.invalidateQueries({ queryKey: socialKeys.comments(variables.ticketId) });
        client.invalidateQueries({ queryKey: socialKeys.activity("ticket", variables.ticketId) });
      }
    },
  });
};

export const useTeamLabels = (teamId: string | undefined) =>
  useQuery({
    queryKey: socialKeys.teamLabels(teamId ?? ""),
    queryFn: () => socialApi.teamLabels(teamId as string),
    enabled: teamId !== undefined,
  });

export const useTicketLabels = (ticketId: string | undefined) =>
  useQuery({
    queryKey: socialKeys.ticketLabels(ticketId ?? ""),
    queryFn: () => socialApi.ticketLabels(ticketId as string),
    enabled: ticketId !== undefined,
  });

export const useCreateLabel = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { name: string; colour?: LabelColour }) =>
      socialApi.createLabel(teamId, body),
    onSuccess: () => client.invalidateQueries({ queryKey: socialKeys.teamLabels(teamId) }),
  });
};

/**
 * The pills in flight, keyed on the ticket.
 *
 * Its own ledger rather than the ticket one in `lib/optimistic.ts`: labels are a
 * separate cache entry with a separate shape, and giving them the same [Guesses] is what
 * makes two rapid presses of the picker settle in the right order here as well. Module
 * level for the reason the ticket ledger is — one per tab, beside the query client.
 */
const labelGuesses = new Guesses<Label[]>();

/**
 * Optimistic, where [useCreateComment] is not, and the difference is worth naming: this
 * endpoint replaces the whole set, so what the ticket will be wearing is exactly what
 * the picker was just told, and the response carries nothing the client did not already
 * have. A comment's does — the server resolves its `@handles` into mentions. The one
 * thing this could still get wrong is the order, and that is knowable too; see
 * [wornLabels].
 */
export const useSetTicketLabels = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ ticketId, labelIds }: { ticketId: string; labelIds: string[] }) =>
      socialApi.setTicketLabels(ticketId, labelIds),

    onMutate: async ({ ticketId, labelIds }) => {
      const key = socialKeys.ticketLabels(ticketId);
      await client.cancelQueries({ queryKey: key });

      // Every team's labels the session has loaded, plus what this ticket already wears.
      // The picker was drawing from one of these lists a moment ago, so the ids resolve
      // to real names and colours without a request; ids are unique across teams, so
      // pooling them cannot resolve one to the wrong label.
      const catalogue = [
        ...(client.getQueryData<Label[]>(key) ?? []),
        ...client
          .getQueriesData<Label[]>({ queryKey: ["labels", "team"] })
          .flatMap(([, rows]) => rows ?? []),
      ];

      const { handle, value } = labelGuesses.open(
        ticketId,
        () => client.getQueryData<Label[]>(key),
        () => wornLabels(labelIds, catalogue),
      );
      if (handle) client.setQueryData(key, value);
      return { handle };
    },

    onSettled: (saved, _error, { ticketId }, context) => {
      if (context) {
        const done = labelGuesses.close(context.handle, saved);
        if (done?.value) client.setQueryData(socialKeys.ticketLabels(ticketId), done.value);
      }
      // The feed is the server's to write — it records who attached what, and when.
      client.invalidateQueries({ queryKey: socialKeys.activity("ticket", ticketId) });
    },
  });
};
