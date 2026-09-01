"use client";

import { useCallback, useMemo } from "react";
import { useUi } from "@/store/ui";
import type { ActionContext } from "./actions";
import { api, setDevUser, type Ticket } from "./api";
import { actionErrorMessage } from "./errors";
import {
  useDeleteTicket,
  useFavourites,
  useMe,
  usePatchTicket,
  useProjects,
  useTeams,
  useTimeline,
  useToggleFavourite,
  useUnarchive,
} from "./queries";

/** The id the filter input carries, so focusing it needs no React ref. */
export const FILTER_INPUT_ID = "ticket-filter";

/**
 * The id the chart's today rule carries. Same trick as the filter input: the element
 * that has to be reached lives in a component tree this hook knows nothing about, and
 * an id is what makes it reachable from an action without a ref or a second store.
 */
export const TODAY_MARKER_ID = "tl-today";

/**
 * Assembles the one object every action runs against: the loaded data, the
 * mutations, the store's setters, and the permission the server enforces anyway.
 *
 * What it cannot know is list-local — which rows survived the filter, which one the
 * cursor is on, how the cursor moves, where a failure is displayed — so the page
 * passes those in.
 */
export function useActionContext(local: {
  tickets: Ticket[];
  selected?: Ticket;
  move: (delta: number) => void;
  startRename: (id: string) => void;
  /** Opens the predecessor picker for a ticket. Page-local: the palette is its list. */
  startLink: (successorId: string) => void;
  /** Opens the same picker to erase one instead of to draw one. */
  startUnlink: (successorId: string) => void;
  /** Where a failure with no dialog to land in goes. `null` clears it. */
  reportError: (message: string | null) => void;
}): ActionContext {
  const { scope, setScope, view, zoom, setZoom, open, close, openDialog } = useUi();
  const me = useMe();
  const teams = useTeams();
  const projects = useProjects();
  // The chart's own query, asked for again rather than passed down: same key, so this is
  // the same cache entry the chart reads and no second request exists. `enabled` is the
  // view, so the list pays nothing for edges no action there can use.
  const timeline = useTimeline(view === "timeline");
  const { mutate: patchTicket } = usePatchTicket();
  const { mutate: unarchiveEntity } = useUnarchive();
  const { mutate: deleteTicket } = useDeleteTicket();

  // The whole list, not a membership test: the toggle has to know which way it is going,
  // and the sidebar has already loaded this exact cache entry to draw its own section —
  // so asking for it here costs a read of the cache and no second request.
  const favourites = useFavourites();
  const { mutate: flipFavourite } = useToggleFavourite();
  const pinned = favourites.data;

  // Signing out is a full page transition, not a cache update: everything on screen
  // belongs to the session that is ending, so a reload is the honest way to drop it.
  //
  // Dev mode's identity is not a session the server manages — it is a header the
  // client reads out of this one `localStorage` key and reattaches to every
  // request ("nothing is verified", `DevAuthenticationFilter`). The server's
  // `/api/auth/logout` has no authority over it and never did, so its outcome —
  // success, a rejection, or the network dropping the request entirely — has no
  // bearing on whether this browser should keep asserting that identity. Cleared
  // here, before `api.logout()` is even called, unconditionally and before any
  // request goes out: no request from this point on, including the logout call
  // itself and any background refetch racing it, can still carry the old header.
  // Placing it inside `.then()` or `.finally()` would leave exactly that window
  // open for the length of the request. Under `oidc` this is a no-op — the key
  // was never set — and the session cookie `api.logout()` clears is what actually
  // signs that mode out.
  const logout = useCallback(() => {
    setDevUser(null);
    api
      // Swallowed, not ignored: `api.logout()` rejects on a 4xx, and a rejection
      // `finally` does not handle propagates on to become an unhandled rejection in
      // the console — noise from the one path where the outcome provably does not
      // matter, since the reload below happens either way.
      .logout()
      .catch(() => {})
      // Not `useRouter().push()`: signing out is not a client-side navigation. Every
      // cache, store and subscription on screen belongs to the session that is ending,
      // and a full document load is the only thing that provably drops all of them.
      // eslint-disable-next-line @next/next/no-location-assign-relative-destination
      .finally(() => window.location.assign("/"));
  }, []);

  // A ref would tie this hook to one component's tree, and a context holding one
  // cannot be read while rendering. The filter is a single element; its id is
  // what makes it reachable from a command.
  const focusFilter = useCallback(() => document.getElementById(FILTER_INPUT_ID)?.focus(), []);

  // Reached the same way, and for the same reason: the chart owns its scroll position,
  // and the alternative — a "scroll to today" flag in the store that the view watches
  // and then has to clear — is a second copy of state the DOM already holds.
  //
  // `block: "nearest"` so recentring never scrolls the page vertically: the ask is
  // about the calendar, not about which row is on screen.
  const recentre = useCallback(
    () =>
      document
        .getElementById(TODAY_MARKER_ID)
        ?.scrollIntoView({ inline: "center", block: "nearest", behavior: "smooth" }),
    [],
  );

  const role = me.data?.user.instanceRole;
  const canConfigure = role === "owner" || role === "admin";

  const { tickets, selected, move, startRename, startLink, startUnlink, reportError } = local;

  return useMemo(
    () => ({
      scope,
      teams: teams.data ?? [],
      projects: projects.data ?? [],
      tickets,
      selected,
      canConfigure,
      view,
      zoom,
      open,
      close,
      openDialog,
      setScope,
      setZoom,
      move,
      focusFilter,
      startRename,
      recentre,
      dependencies: timeline.data?.dependencies ?? [],
      startLink,
      startUnlink,
      /**
       * A no-op until the list has loaded, deliberately. Without it the first press after
       * a cold start would always read as "not pinned yet" and un-pinning would be
       * impossible for as long as the request had left to run — a key that silently does
       * the wrong thing is worse than a key that waits a beat.
       */
      toggleFavourite: (target) => {
        if (!pinned) return;
        const already = pinned.some((row) => row.kind === target.kind && row.id === target.id);
        flipFavourite({ target, pinned: already });
      },
      // Left alone on purpose: a patch is optimistic, so a failure is already
      // visible as the row snapping back to what it was.
      patchTicket,
      deleteTicket,
      // Unarchiving is run from a menu, with no dialog to report into and nothing
      // optimistic to snap back, so its 403s and 409s go to the top bar instead.
      unarchive: (target) =>
        unarchiveEntity(target, {
          onError: (error) => reportError(actionErrorMessage(error)),
          onSuccess: () => reportError(null),
        }),
      logout,
    }),
    [
      scope,
      teams.data,
      projects.data,
      tickets,
      selected,
      canConfigure,
      view,
      zoom,
      open,
      close,
      openDialog,
      setScope,
      setZoom,
      move,
      focusFilter,
      startRename,
      recentre,
      timeline.data,
      startLink,
      startUnlink,
      reportError,
      pinned,
      flipFavourite,
      patchTicket,
      deleteTicket,
      unarchiveEntity,
      logout,
    ],
  );
}
