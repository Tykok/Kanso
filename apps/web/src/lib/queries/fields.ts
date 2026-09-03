"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import { fieldsApi, type CustomFieldBody, type CustomFieldValue } from "@/lib/api";

import { socialKeys } from "./social";

export const fieldKeys = {
  team: (teamId: string) => ["fields", "team", teamId] as const,
  ticket: (ticketId: string) => ["fields", "ticket", ticketId] as const,
};

export const useTeamFields = (teamId: string | undefined) =>
  useQuery({
    queryKey: fieldKeys.team(teamId ?? ""),
    queryFn: () => fieldsApi.teamFields(teamId as string),
    enabled: teamId !== undefined,
  });

export const useTicketFields = (ticketId: string | undefined) =>
  useQuery({
    queryKey: fieldKeys.ticket(ticketId ?? ""),
    queryFn: () => fieldsApi.ticketFields(ticketId as string),
    enabled: ticketId !== undefined,
  });

export const useDefineField = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: CustomFieldBody) => fieldsApi.defineField(teamId, body),
    onSuccess: () => client.invalidateQueries({ queryKey: fieldKeys.team(teamId) }),
  });
};

export const useRedefineField = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({ fieldId, body }: { fieldId: string; body: CustomFieldBody }) =>
      fieldsApi.redefineField(fieldId, body),
    onSuccess: () => client.invalidateQueries({ queryKey: fieldKeys.team(teamId) }),
  });
};

/**
 * Deleting a definition cascades to its values, so every ticket in the cache is now wrong
 * about what it holds — not just the ones this screen can see. Hence the broad
 * invalidation: a narrower one would leave a stale value rendering under a field that no
 * longer exists, which is precisely the orphan `V32` refused to keep on disk.
 */
export const useDeleteField = (teamId: string) => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (fieldId: string) => fieldsApi.deleteField(fieldId),
    onSuccess: () => {
      client.invalidateQueries({ queryKey: fieldKeys.team(teamId) });
      client.invalidateQueries({ queryKey: ["fields", "ticket"] });
      client.invalidateQueries({ queryKey: ["tickets"] });
    },
  });
};

/**
 * No optimistic guess, unlike `useSetTicketLabels`.
 *
 * A label write cannot be refused for the value's sake — an id either names a label of the
 * team or it does not, and the picker only offers the ones that do. A field value can:
 * `FieldValueCodec` refuses a string in a number field, a choice outside the options, and
 * the clearing of a required field. Drawing the new value first would show a severity that
 * the server is about to reject, and the panel would then have to un-draw it — a flicker
 * that says the write worked when it did not. So this one waits for the answer, which is
 * also the answer it renders: the server sends back the whole map.
 */
export const useSetTicketFields = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: ({
      ticketId,
      values,
    }: {
      ticketId: string;
      values: Record<string, CustomFieldValue | null>;
    }) => fieldsApi.setTicketFields(ticketId, values),

    onSuccess: (saved, { ticketId }) => {
      client.setQueryData(fieldKeys.ticket(ticketId), saved);
    },

    onSettled: (_saved, _error, { ticketId }) => {
      // The values ride on the ticket row too — `TicketResponse.customFields` — so a list
      // already drawn is stale until it refetches.
      client.invalidateQueries({ queryKey: ["tickets"] });
      client.invalidateQueries({ queryKey: socialKeys.activity("ticket", ticketId) });
    },
  });
};
