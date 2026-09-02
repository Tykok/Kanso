"use client";

import { useMemo } from "react";
import { usePreferences } from "./queries";
import { mergeBindings, type MergedBindings } from "./shortcuts";

/**
 * The reader's keyboard, for the surfaces that draw it and the one that dispatches it.
 *
 * Four call sites, one truth: `use-shell-keys.ts` resolves a keypress against
 * `index`, the `?` overlay lists `keys`, every row menu prints from `keys`, and the ticket
 * list's status bar prints from `keys`. That last one is the reason this hook exists at
 * all — the strip printed ten keys as string literals, the only key-printing surface in
 * the app that did not read the registry, and after §6.4 it would have gone on
 * confidently advertising `j` and `k` to a reader who has neither.
 *
 * `usePreferences()` is a read of the `/api/me` cache entry every screen has already
 * fetched, so this costs no request; `preferences.shortcuts` is `{}` for anyone who has
 * never changed a key, which is the common case and makes the merge trivial. It is
 * **read** here and written nowhere: the settings section that writes it is §6.5's.
 *
 * Memoised on the overrides object, which react-query keeps stable between refetches that
 * change nothing — so the merge runs once per session for almost every reader, and the
 * `index` handed to the dispatcher's effect keeps its identity instead of re-attaching a
 * `keydown` listener on every render.
 */
export function useBindings(): MergedBindings {
  const { shortcuts } = usePreferences();
  return useMemo(() => mergeBindings(shortcuts), [shortcuts]);
}
