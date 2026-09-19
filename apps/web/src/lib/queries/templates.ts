"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  templatesApi,
  type ResolvedTemplate,
  type TemplateBodyRequest,
  type TicketTemplate,
} from "@/lib/api";

export const templateKeys = {
  /** Keyed on the team because the answer is that team's catalogue plus Kanso's. */
  list: (teamId: string | undefined) => ["templates", "list", teamId ?? "instance"] as const,
};

export const useTemplates = (teamId: string | undefined) =>
  useQuery({
    queryKey: templateKeys.list(teamId),
    queryFn: () => templatesApi.list(teamId),
  });

/**
 * A mutation and not a query, which is the one shape decision in this file.
 *
 * Resolution is a *gesture* — somebody picked a template — not a subscription. A query keyed
 * on the pair would cache the answer for a team, and the team selector sits above the picker
 * and moves: the composer would then fill from a cached reading of a team nobody is pointing
 * at any more. It is also never wanted before the click, and a query would either fetch all
 * of them up front or need `enabled` gymnastics to avoid it.
 */
export const useResolveTemplate = () =>
  useMutation<ResolvedTemplate, Error, { id: string; teamId: string | undefined }>({
    mutationFn: ({ id, teamId }) => templatesApi.resolve(id, teamId),
  });

/**
 * Both levels are invalidated, not just the one written to.
 *
 * An instance template appears in every team's list, so a create at that level changes an
 * answer cached under every team key. Invalidating the whole `templates` prefix is one line
 * and cannot be wrong; enumerating the affected teams would need the team list here and would
 * go stale the next time somebody adds a team.
 */
const invalidateAll = (client: ReturnType<typeof useQueryClient>) =>
  client.invalidateQueries({ queryKey: ["templates"] });

export const useCreateTemplate = () => {
  const client = useQueryClient();
  return useMutation<TicketTemplate, Error, TemplateBodyRequest & { teamId?: string }>({
    mutationFn: (body) => templatesApi.create(body),
    onSuccess: () => invalidateAll(client),
  });
};

export const useUpdateTemplate = () => {
  const client = useQueryClient();
  return useMutation<TicketTemplate, Error, { id: string; body: TemplateBodyRequest }>({
    mutationFn: ({ id, body }) => templatesApi.update(id, body),
    onSuccess: () => invalidateAll(client),
  });
};

export const useDeleteTemplate = () => {
  const client = useQueryClient();
  return useMutation<void, Error, string>({
    mutationFn: (id) => templatesApi.remove(id),
    onSuccess: () => invalidateAll(client),
  });
};
