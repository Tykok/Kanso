"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { BoardView } from "@/components/board/view";
import { BrandSplash } from "@/components/brand-logo";
import { EmptyState } from "@/components/inbox/empty-state";
import { ImportDialog } from "@/components/inbox/import-dialog";
import { DispositionDialog } from "@/components/dialogs/disposition-dialog";
import { ProjectDialog } from "@/components/dialogs/project-dialog";
import { SaveViewDialog } from "@/components/dialogs/save-view-dialog";
import { TeamDialog } from "@/components/dialogs/team-dialog";
import { LoginScreen } from "@/components/login";
import { MobileNavDrawer } from "@/components/mobile-nav";
import { NewMenu } from "@/components/new-menu";
import { Composer } from "@/components/composer";
import { CommandPalette, DetailPanel, HelpOverlay } from "@/components/overlays";
import { SettingsPanel } from "@/components/settings/panel";
import { Sidebar } from "@/components/sidebar";
import { TicketList } from "@/components/tickets";
import { TimelineView } from "@/components/timeline/view";
import { availableActions, hintOf, predecessorsOf, resolveShortcut } from "@/lib/actions";
import { ApiError, getDevUser, setDevUser, ticketHref, type Ticket } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { isMac } from "@/lib/platform";
import {
  useAuthMode,
  useLinkDependency,
  useMe,
  usePatchTicket,
  usePreferences,
  useProjects,
  useSetupState,
  useSyncStatus,
  useTeams,
  useTickets,
  useUnlinkDependency,
} from "@/lib/queries";
import { ZOOMS } from "@/lib/timeline-geometry";
import { FILTER_INPUT_ID, useActionContext } from "@/lib/use-action-ctx";
import { cn } from "@/lib/utils";
import { useUi, type Scope } from "@/store/ui";

const isTypingTarget = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT");

export default function InboxPage() {
  const router = useRouter();
  const authMode = useAuthMode();
  const me = useMe();
  const setup = useSetupState();
  const preferences = usePreferences();

  const {
    scope,
    selectedId,
    view,
    zoom,
    overlay,
    dialog,
    query,
    setScope,
    select,
    setView,
    setZoom,
    open,
    close,
    setQuery,
  } = useUi();
  const [editingId, setEditingId] = useState<string | undefined>();
  const [actionError, setActionError] = useState<{ scope: Scope; message: string } | null>(null);
  /**
   * The ticket whose arrows the palette is asking about, and which question it is
   * asking. Page-local rather than in the store: it lives exactly as long as the overlay
   * it re-labels, and the palette is rendered here.
   */
  const [picker, setPicker] = useState<{ kind: "link" | "unlink"; ticketId: string }>();

  const teams = useTeams();
  const tickets = useTickets();
  const projects = useProjects();
  const sync = useSyncStatus();

  const patch = usePatchTicket();
  const link = useLinkDependency();
  const unlink = useUnlinkDependency();

  const filtered = useMemo(() => {
    const rows = tickets.data ?? [];
    const needle = query.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (ticket) =>
        ticket.title.toLowerCase().includes(needle) ||
        ticket.identifier.toLowerCase().includes(needle),
    );
  }, [tickets.data, query]);

  /**
   * The rows on screen — which is not the same list in the two views.
   *
   * The chart draws the *timeline* query, which the filter box does not touch, while the
   * list draws this one filtered. Keeping the cursor inside the filtered list either way
   * meant that with a filter typed, clicking a bar the filter excluded selected it and
   * the effect below bounced the cursor straight back to `filtered[0]` — and, less
   * visibly, that `h`, `l`, `H` and `L` did nothing at all on such a bar, since every
   * action reads `ctx.selected` and that is looked up in this list.
   *
   * So the cursor lives in whatever the view actually draws. Filtering the chart to
   * match the list instead was the other way to make one list feed both, and it is a
   * different feature: a Gantt with half its bars hidden draws arrows to tickets that
   * are not there, and the filter's job here is to find a row, not to hide a plan.
   *
   * Archived tickets are dropped in the timeline branch because the timeline endpoint
   * never returns them, so the cursor could otherwise land on a ticket that appears
   * nowhere on the chart — the very bug being fixed.
   */
  const visible = useMemo(
    () =>
      view === "timeline"
        ? (tickets.data ?? []).filter((ticket) => !ticket.archived)
        : filtered,
    [view, tickets.data, filtered],
  );

  // The cursor follows the list: when a filter or a realtime update removes the
  // selected row, land on something sensible rather than losing the selection.
  useEffect(() => {
    if (visible.length === 0) {
      if (selectedId) select(undefined);
      return;
    }
    if (!selectedId || !visible.some((ticket) => ticket.id === selectedId)) {
      select(visible[0].id);
    }
  }, [visible, selectedId, select]);

  /**
   * An instance without an owner has nothing to show, and someone who has never been
   * through the preferences step is sent to pick them once. Both answers come from
   * the setup endpoint: on a backend that predates the wizard it 404s, and pushing
   * anyone towards a route that does not exist there is worse than a working list.
   */
  const needsSetup =
    setup.data !== undefined &&
    (setup.data.needsOwner || (me.data !== undefined && !me.data.preferences.onboardedAt));

  useEffect(() => {
    if (needsSetup) router.replace("/setup");
  }, [needsSetup, router]);

  const selected: Ticket | undefined = visible.find((ticket) => ticket.id === selectedId);
  const currentTeam =
    scope.kind === "team" ? teams.data?.find((team) => team.id === scope.id) : undefined;

  /**
   * What the list is showing, said in the heading.
   *
   * A project scope used to read "All tickets" here, which is the one label that is false
   * for it: the rows *are* filtered, and the header was the only thing on screen claiming
   * otherwise. Screen 05's "See all" lands exactly here, so the sentence it lands on has to
   * name the project it came from.
   */
  const currentProject =
    scope.kind === "project" ? projects.data?.find((row) => row.id === scope.id) : undefined;
  const heading = currentTeam?.name ?? currentProject?.name ?? "All tickets";

  const move = useCallback(
    (delta: number) => {
      if (visible.length === 0) return;
      const index = visible.findIndex((ticket) => ticket.id === selectedId);
      const next = Math.min(Math.max((index < 0 ? 0 : index) + delta, 0), visible.length - 1);
      select(visible[next].id);
    },
    [visible, selectedId, select],
  );

  const startRename = useCallback((id: string) => setEditingId(id), []);

  /**
   * `d` and `D` on the chart. The predecessor is picked from the palette the app already
   * has rather than from a link mode of its own: nothing else in this interface is modal,
   * and one keyboard gesture is not worth teaching a second way to be in a state.
   */
  const startLink = useCallback(
    (successorId: string) => {
      setPicker({ kind: "link", ticketId: successorId });
      open("palette");
    },
    [open],
  );

  const startUnlink = useCallback(
    (successorId: string) => {
      setPicker({ kind: "unlink", ticketId: successorId });
      open("palette");
    },
    [open],
  );

  // The palette is one overlay with three lists, so leaving it has to put the ordinary
  // one back — otherwise ⌘K afterwards would still be asking about a dependency.
  const closeOverlay = useCallback(() => {
    setPicker(undefined);
    close();
  }, [close]);

  /**
   * A failure belongs to the view it happened in, so the scope it was reported
   * against is stored with it and a scope change simply stops it applying. Clearing
   * it from an effect instead would leave one render showing a sentence about a team
   * nobody is looking at any more.
   */
  const reportError = useCallback(
    (message: string | null) => setActionError(message === null ? null : { scope, message }),
    [scope],
  );
  const shownError = actionError?.scope === scope ? actionError.message : null;

  const ctx = useActionContext({
    tickets: visible,
    selected,
    move,
    startRename,
    startLink,
    startUnlink,
    reportError,
  });

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const dismiss = () => {
        // `closeOverlay`, not `close`: Escape from anywhere in the palette but its
        // input reaches this handler, and it must not leave the predecessor pending.
        closeOverlay();
        setEditingId(undefined);
        (event.target as HTMLElement | null)?.blur?.();
      };

      // Overlays and dialogs own their own keys; the list must not react behind them.
      // This comes first, ⌘K included: the composer's title input stops propagation
      // but its four `<select>`s do not, so ⌘K from one of them used to throw away a
      // typed title by opening the palette over it.
      if (overlay !== "none" || dialog.kind !== "none" || editingId) {
        if (event.key === "Escape") dismiss();
        return;
      }

      // A modified key, so it never reaches the registry, which only owns bare ones.
      // Still answered while typing in the filter — that field is part of the list,
      // not an overlay over it.
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        open("palette");
        return;
      }

      if (isTypingTarget(event.target)) {
        if (event.key === "Escape") dismiss();
        return;
      }
      if (event.metaKey || event.ctrlKey || event.altKey) return;

      /**
       * `⇧↵` opens the selected ticket in its own page.
       *
       * Handled here rather than in the registry because it cannot be in the registry:
       * `event.key` for Shift+Enter is `"Enter"`, the same string the panel's own `↵`
       * dispatches on, and the registry holds one entry per key with no modifier state.
       * Shift+h works there only because `event.key` for it is `"H"` — a different
       * string, not a flag. This is the one gesture in the interface where the modifier
       * is the whole difference, so it is read where the modifier still exists.
       */
      if (event.key === "Enter" && event.shiftKey && selected) {
        event.preventDefault();
        router.push(ticketHref(selected.identifier));
        return;
      }

      // Shift is otherwise deliberately not in the guard above: `event.key` for Shift+h
      // is "H", which the registry holds as its own entry, so the two halves of a bar
      // edit are two keys rather than one key and a modifier flag.
      const action = resolveShortcut(event.key, view);
      // One predicate answers both "may I show this" and "may I run it", so a key
      // whose action is unavailable stays inert rather than half-firing.
      if (!action || !action.when(ctx)) return;
      event.preventDefault();
      action.run(ctx);
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [ctx, overlay, dialog, editingId, open, closeOverlay, view, selected, router]);

  const commands = useMemo(() => {
    // Asked for a predecessor, the palette lists tickets instead of commands: same
    // overlay, same filtering, same keys, so `d` costs nobody a new mental model.
    if (picker?.kind === "link") {
      return visible
        .filter((candidate) => candidate.id !== picker.ticketId)
        .map((candidate) => ({
          id: `timeline.link.${candidate.id}`,
          label: `Wait for ${candidate.identifier}: ${candidate.title}`,
          run: () => {
            link.mutate(
              { successorId: picker.ticketId, predecessorId: candidate.id },
              {
                // A cycle is a 409 naming the chain. It belongs on the screen the
                // arrow was drawn on, in the same strip every other refusal uses.
                onError: (error) => reportError(actionErrorMessage(error)),
                onSuccess: () => reportError(null),
              },
            );
            closeOverlay();
          },
        }));
    }

    // The inverse list, in the same overlay. "Stop waiting for" against "Wait for", so
    // the two are legible as opposites rather than as two unrelated pickers.
    if (picker?.kind === "unlink") {
      const successorId = picker.ticketId;
      return predecessorsOf(ctx, successorId).map((predecessor) => ({
        id: `timeline.unlink.${predecessor.id}`,
        label: `Stop waiting for ${predecessor.identifier}: ${predecessor.title}`,
        run: () => {
          unlink.mutate(
            { successorId, predecessorId: predecessor.id },
            {
              // Nothing here is optimistic — freeing slack pulls nothing earlier — so a
              // refusal has no row snapping back to serve as its signal, and goes to the
              // strip every other failed action reports into.
              onError: (error) => reportError(actionErrorMessage(error)),
              onSuccess: () => reportError(null),
            },
          );
          closeOverlay();
        },
      }));
    }

    return [
      ...availableActions(ctx).map((action) => ({
        id: action.id,
        label: action.label,
        hint: hintOf(action, isMac()),
        run: () => action.run(ctx),
      })),
      // Teams are rows from the server, so no static registry can enumerate them.
      ...(teams.data ?? []).map((team) => ({
        id: `view.team.${team.id}`,
        label: `View team: ${team.name}`,
        run: () => {
          setScope({ kind: "team", id: team.id });
          close();
        },
      })),
    ];
  }, [ctx, teams.data, setScope, close, picker, visible, link, unlink, reportError, closeOverlay]);

  if (me.isLoading || authMode.isLoading || setup.isLoading) {
    return <BrandSplash label="Loading…" />;
  }

  // Ahead of the sign-in screen: with no owner yet there is nobody to sign in as.
  if (needsSetup) {
    return <BrandSplash label="Opening setup…" />;
  }

  if (me.error instanceof ApiError && me.error.status === 401) {
    return <LoginScreen mode={authMode.data} />;
  }

  const mirrorSummary = !sync.data
    ? ""
    : sync.data.mirrorEnabled
      ? `Notion: ${sync.data.bootstrapped ? "connected" : "not bootstrapped"}${
          sync.data.jobs.pending ? ` · ${sync.data.jobs.pending} queued` : ""
        }${sync.data.failed.length ? ` · ${sync.data.failed.length} failed` : ""}`
      : "Notion mirror off";

  return (
    <div
      className={cn(
        "grid h-screen max-[720px]:grid-cols-[1fr]",
        preferences.sidebarVisible ? "grid-cols-[248px_1fr]" : "grid-cols-[1fr]",
      )}
    >
      {/* `contents` so the wrapper is invisible to the grid — `<Sidebar>` still lands
          in the 248px column — `max-[720px]:hidden` so only *this* copy disappears
          under 720px; the drawer's own copy (`MobileNavDrawer`) is what replaces it
          there. `<Sidebar>` no longer hides itself: it is reused inside the drawer
          too, where it must not. */}
      {preferences.sidebarVisible && (
        <div className="contents max-[720px]:hidden">
          <Sidebar ctx={ctx} syncSummary={mirrorSummary} />
        </div>
      )}

      <div className="flex min-h-0 min-w-0 flex-col">
        <div className="flex items-center gap-3 bg-card px-5 py-3">
          <MobileNavDrawer ctx={ctx} syncSummary={mirrorSummary} />
          <h1 className="m-0 text-13 font-medium">{heading}</h1>
          <span className="font-mono text-11 text-faint">{visible.length}</span>
          <span className="flex-1" />

          {view === "timeline" && (
            <div className="segmented" role="group" aria-label="Zoom">
              {ZOOMS.map((level) => (
                <button
                  key={level}
                  type="button"
                  aria-pressed={zoom === level}
                  onClick={() => setZoom(level)}
                >
                  {level[0].toUpperCase() + level.slice(1)}
                </button>
              ))}
            </div>
          )}

          <div className="segmented" role="group" aria-label="View">
            <button type="button" aria-pressed={view === "list"} onClick={() => setView("list")}>
              List
            </button>
            <button type="button" aria-pressed={view === "board"} onClick={() => setView("board")}>
              Board
            </button>
            <button
              type="button"
              aria-pressed={view === "timeline"}
              onClick={() => setView("timeline")}
            >
              Timeline
            </button>
          </div>

          <input
            id={FILTER_INPUT_ID}
            className="w-[180px] rounded-md border border-transparent bg-accent px-2 py-1.5 text-12"
            placeholder="Filter…  /"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Escape") {
                setQuery("");
                event.currentTarget.blur();
              }
            }}
          />
          <NewMenu ctx={ctx} />
        </div>

        {/*
         * Read-only is a fact about the scope, not the chart's own state, so it is said
         * once here rather than by every bar refusing the pointer one at a time — a
         * feature indistinguishable from a bug is a bug.
         */}
        {view === "timeline" && scope.kind === "all" && (
          <div className="topbar-note" role="status">
            Read-only — open a team or a project to plan.
          </div>
        )}

        {shownError && (
          <div className="topbar-error error" role="alert">
            <span>{shownError}</span>
            <button
              type="button"
              aria-label="Dismiss this message"
              title="Dismiss"
              onClick={() => setActionError(null)}
            >
              ×
            </button>
          </div>
        )}

        {view === "timeline" ? (
          <TimelineView reportError={reportError} />
        ) : view === "board" ? (
          <BoardView reportError={reportError} />
        ) : tickets.error ? (
          <div className="empty error">{(tickets.error as Error).message}</div>
        ) : (
          <TicketList
            // `filtered`, not `visible`: the two are the same object in this branch, and
            // naming the filtered one here is what says the list is the thing the filter
            // box was written for.
            tickets={filtered}
            /**
             * Screen 15's empty states and screen 08's three gestures, both of which need
             * to tell "the filter found nothing" from "there is nothing" — so they need
             * the count before the filter and the count across the instance, neither of
             * which the list itself has. `ticketsAnywhere` is summed from the teams query
             * rather than fetched: `Team.ticketCount` is already on every row, and a
             * second request to learn whether this is a new install would be one more
             * thing to keep in step.
             */
            empty={
              <EmptyState
                total={(tickets.data ?? []).length}
                filter={query}
                ticketsAnywhere={(teams.data ?? []).reduce(
                  (sum, team) => sum + team.ticketCount,
                  0,
                )}
                onClearFilter={() => setQuery("")}
                onSeeAll={() => {
                  setQuery("");
                  setScope({ kind: "all" });
                }}
                onCreate={() => open("composer")}
                notionConnected={sync.data?.bootstrapped ?? false}
                onConnectNotion={() => open("settings")}
              />
            }
            selectedId={selectedId}
            editingId={editingId}
            ctx={ctx}
            onSelect={select}
            onOpen={(id) => {
              select(id);
              open("detail");
            }}
            onRename={(id, title) => {
              patch.mutate({ id, title });
              setEditingId(undefined);
            }}
            onCancelEdit={() => setEditingId(undefined)}
          />
        )}

        {preferences.showStatusBar && (
          <div className="statusbar">
            <span>
              <kbd>j</kbd> <kbd>k</kbd> move
            </span>
            <span>
              <kbd>1</kbd>–<kbd>6</kbd> status
            </span>
            <span>
              <kbd>c</kbd> new
            </span>
            {/* The chart's keys are not guessable and are worth one line while it is
                on screen; `?` lists them all, grouped by the view they belong to. */}
            {view === "timeline" && (
              <>
                <span>
                  <kbd>h</kbd> <kbd>l</kbd> move
                </span>
                <span>
                  <kbd>[</kbd> <kbd>]</kbd> zoom
                </span>
                <span>
                  <kbd>t</kbd> today
                </span>
                <span>
                  <kbd>d</kbd> depends on
                </span>
              </>
            )}
            {/* The only key in this strip whose spelling depends on the reader —
                every other one is bare — which is why `Action.hint` exists. */}
            <span>
              <kbd>{isMac() ? "⌘K" : "Ctrl+K"}</kbd> commands
            </span>
            <span>
              <kbd>,</kbd> settings
            </span>
            <span>
              <kbd>?</kbd> help
            </span>
            <span style={{ flex: 1 }} />
            {me.data && <DevUserSwitcher email={me.data.user.email} />}
          </div>
        )}
      </div>

      {overlay === "composer" && <Composer scope={scope} onClose={close} />}
      {overlay === "palette" && <CommandPalette commands={commands} onClose={closeOverlay} />}
      {overlay === "help" && <HelpOverlay onClose={close} />}
      {overlay === "settings" && <SettingsPanel onClose={close} />}
      {overlay === "detail" && selected && (
        <DetailPanel
          ticket={selected}
          projects={projects.data ?? []}
          onPatch={(body) => patch.mutate({ id: selected.id, ...body })}
          onDelete={() => {
            ctx.deleteTicket(selected.id);
            close();
          }}
          onClose={close}
        />
      )}
      {dialog.kind === "team" && (
        <TeamDialog id={dialog.id} parentTeamId={dialog.parentTeamId} onClose={close} />
      )}
      {dialog.kind === "project" && (
        <ProjectDialog id={dialog.id} teamId={dialog.teamId} onClose={close} />
      )}
      {dialog.kind === "importMap" && <ImportDialog onClose={close} />}
      {dialog.kind === "saveView" && <SaveViewDialog onClose={close} />}
      {dialog.kind === "disposition" && (
        <DispositionDialog target={dialog.target} severity={dialog.severity} onClose={close} />
      )}
    </div>
  );
}

/**
 * Dev-mode affordance only: act as somebody else without an OAuth round trip, so
 * two-user behaviour (realtime, assignment) can be exercised from one browser.
 */
function DevUserSwitcher({ email }: { email: string }) {
  const authMode = useAuthMode();
  const [value, setValue] = useState(getDevUser() ?? "");

  if (authMode.data?.mode !== "dev") return <span>{email}</span>;

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault();
        setDevUser(value.trim() || null);
        window.location.reload();
      }}
      style={{ display: "flex", gap: 6, alignItems: "center" }}
    >
      <span title="Dev auth: identity comes from a header, nothing is verified">dev as</span>
      <input
        style={{ width: 180, padding: "2px 6px", fontSize: 11 }}
        placeholder={email}
        value={value}
        onChange={(event) => setValue(event.target.value)}
      />
    </form>
  );
}
