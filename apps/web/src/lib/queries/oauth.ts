"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { oauthApi } from "../api";

/**
 * The connected applications, and their one mutation.
 *
 * No `staleTime`: the list changes when somebody runs `claude mcp add` in a terminal,
 * which this tab has no way of hearing about — refetching on focus is the only signal
 * there is, and it is the exact moment a member switches back to check.
 */
export const oauthKeys = { grants: ["oauth", "grants"] as const };

export const useGrants = () =>
  useQuery({ queryKey: oauthKeys.grants, queryFn: oauthApi.grants });

/**
 * `onSettled` rather than `onSuccess`: a revocation that failed still has to re-read the
 * list, because the most likely reason it failed is that the grant is already gone.
 */
export const useRevokeGrant = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: oauthApi.revokeGrant,
    onSettled: () => client.invalidateQueries({ queryKey: oauthKeys.grants }),
  });
};
