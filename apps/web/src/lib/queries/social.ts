import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { socialApi, type ActivityEntity, type LabelColour } from "../api/social";

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

export const useSetTicketLabels = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ ticketId, labelIds }: { ticketId: string; labelIds: string[] }) =>
      socialApi.setTicketLabels(ticketId, labelIds),
    onSuccess: (_labels, { ticketId }) => {
      client.invalidateQueries({ queryKey: socialKeys.ticketLabels(ticketId) });
      client.invalidateQueries({ queryKey: socialKeys.activity("ticket", ticketId) });
    },
  });
};
