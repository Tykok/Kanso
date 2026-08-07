"use client";

import { useCallback, useMemo } from "react";
import { useUi } from "@/store/ui";
import type { ActionContext } from "./actions";
import type { Ticket } from "./api";
import { actionErrorMessage } from "./errors";
import { useMe, usePatchTicket, useProjects, useTeams, useUnarchive } from "./queries";

/** The id the filter input carries, so focusing it needs no React ref. */
export const FILTER_INPUT_ID = "ticket-filter";

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
  /** Where a failure with no dialog to land in goes. `null` clears it. */
  reportError: (message: string | null) => void;
}): ActionContext {
  const { scope, setScope, open, close, openDialog } = useUi();
  const me = useMe();
  const teams = useTeams();
  const projects = useProjects();
  const { mutate: patchTicket } = usePatchTicket();
  const { mutate: unarchiveEntity } = useUnarchive();

  // A ref would tie this hook to one component's tree, and a context holding one
  // cannot be read while rendering. The filter is a single element; its id is
  // what makes it reachable from a command.
  const focusFilter = useCallback(() => document.getElementById(FILTER_INPUT_ID)?.focus(), []);

  const role = me.data?.user.instanceRole;
  const canConfigure = role === "owner" || role === "admin";

  const { tickets, selected, move, startRename, reportError } = local;

  return useMemo(
    () => ({
      scope,
      teams: teams.data ?? [],
      projects: projects.data ?? [],
      tickets,
      selected,
      canConfigure,
      open,
      close,
      openDialog,
      setScope,
      move,
      focusFilter,
      startRename,
      // Left alone on purpose: a patch is optimistic, so a failure is already
      // visible as the row snapping back to what it was.
      patchTicket,
      // Unarchiving is run from a menu, with no dialog to report into and nothing
      // optimistic to snap back, so its 403s and 409s go to the top bar instead.
      unarchive: (target) =>
        unarchiveEntity(target, {
          onError: (error) => reportError(actionErrorMessage(error)),
          onSuccess: () => reportError(null),
        }),
    }),
    [
      scope,
      teams.data,
      projects.data,
      tickets,
      selected,
      canConfigure,
      open,
      close,
      openDialog,
      setScope,
      move,
      focusFilter,
      startRename,
      reportError,
      patchTicket,
      unarchiveEntity,
    ],
  );
}
