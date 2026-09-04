"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { githubApi } from "../api";

/**
 * One key, because there is one answer: this member's link.
 *
 * `["github", "link"]` and not `["github"]`, the rule `tokenKeys` states — React Query
 * invalidates by prefix, so a bare `["github"]` would become a prefix of whatever the next
 * GitHub slice stores and every unlink would refetch it.
 */
export const githubKeys = {
  link: ["github", "link"] as const,
};

export const useGithubLink = () =>
  useQuery({ queryKey: githubKeys.link, queryFn: githubApi.link });

/**
 * Starts the consent flow, and navigates the window itself.
 *
 * `window.location.assign` in the hook rather than in the component, because it is the
 * *point* of the call and not a thing a caller might reasonably forget: the endpoint
 * answers a URL precisely because `fetch` cannot follow the redirect, so a caller that
 * awaited this and did nothing with the URL would have started a flow that goes nowhere.
 *
 * Nothing is invalidated here. The answer arrives as a fresh page load from GitHub's
 * redirect, so there is no cache on this side of it left to be stale.
 */
export const useStartGithubLink = () =>
  useMutation({
    mutationFn: async () => {
      const { url } = await githubApi.startLink();
      window.location.assign(url);
    },
  });

export const useUnlinkGithub = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: githubApi.unlink,
    onSettled: () => client.invalidateQueries({ queryKey: githubKeys.link }),
  });
};

/**
 * The App's client, for an owner.
 *
 * The response *is* the new link state — the controller answers with it rather than with
 * nothing — so it is written into the cache instead of invalidating and asking again. The
 * same shape `connections-section.tsx` uses for the setup state, and for the same reason:
 * the screen has the answer already.
 */
export const useSaveGithubApp = () => {
  const client = useQueryClient();
  return useMutation({
    mutationFn: githubApi.saveApp,
    onSuccess: (link) => client.setQueryData(githubKeys.link, link),
  });
};
