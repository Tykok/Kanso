"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { tokensApi } from "../api";

/**
 * Two keys that are not one inside the other, on purpose.
 *
 * `["tokens", "list"]` rather than `["tokens"]`, because React Query invalidates by prefix:
 * a bare `["tokens"]` would be a prefix of the scope key as well, so every creation and
 * every revocation would also refetch a vocabulary that changes when the instance is
 * redeployed and at no other time.
 */
export const tokenKeys = {
  list: ["tokens", "list"] as const,
  scopes: ["tokens", "scopes"] as const,
};

export const useApiTokens = () => useQuery({ queryKey: tokenKeys.list, queryFn: tokensApi.list });

/**
 * What a token may be granted. `staleTime: Infinity` because this is a compile-time list on
 * the server — it cannot change while a tab is open, so refetching it on focus would be a
 * request that can only ever return the same two rows.
 */
export const useApiTokenScopes = () =>
  useQuery({ queryKey: tokenKeys.scopes, queryFn: tokensApi.scopes, staleTime: Infinity });

/**
 * Creating one, and what this hook deliberately does **not** do: keep the secret.
 *
 * The plaintext is returned to the caller and nothing here stores it. It is held in the
 * section's own `useState` instead, and the difference is the whole show-once problem: a
 * secret read out of `mutation.data` lives exactly as long as the mutation object, and this
 * `onSuccess` invalidates the list underneath it — one refetch, one re-render, and a person
 * who had not finished copying is looking at a screen that cannot tell them what it just
 * said. Component state survives the re-render; `mutation.data` is not promised to.
 */
export const useCreateApiToken = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: tokensApi.create,
    onSuccess: () => client.invalidateQueries({ queryKey: tokenKeys.list }),
  });
};

/**
 * `onSettled` and not `onSuccess`, for the reason `useRevokeGrant` gives: a revocation that
 * failed still has to re-read the list, because the likeliest reason it failed is that the
 * row is already gone.
 */
export const useRevokeApiToken = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: tokensApi.revoke,
    onSettled: () => client.invalidateQueries({ queryKey: tokenKeys.list }),
  });
};
