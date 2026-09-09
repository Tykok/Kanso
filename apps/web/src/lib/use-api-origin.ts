"use client";

import { useSyncExternalStore } from "react";
import { API_URL, apiOrigin } from "./api";

/** An origin does not change under a running page, so there is nothing to subscribe to. */
const subscribe = () => () => {};

/** What a prerender knows, which is the empty string unless a build inlined an API URL. */
const buildTime = () => API_URL;

/**
 * [apiOrigin] for the five places whose value is *drawn*, rather than read in a handler.
 *
 * A redirect URI, the GitHub callback URL and the `claude mcp add` command are rendered
 * into the HTML, and with nothing inlined the prerendered pass produces the empty string
 * where the browser produces its own origin — a hydration mismatch on five screens.
 *
 * `useSyncExternalStore` for the reason `useNarrow` gives beside the same choice, and for
 * one more: `react-hooks/set-state-in-effect` refuses the `useState` and an effect that
 * would read the origin a frame later. The server snapshot is what React hydrates with,
 * so the correction lands in the same commit as hydration, and a client-side navigation
 * never renders the empty pass at all.
 */
export function useApiOrigin(): string {
  return useSyncExternalStore(subscribe, apiOrigin, buildTime);
}
