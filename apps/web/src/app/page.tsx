"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { DispositionDialog } from "@/components/dialogs/disposition-dialog";
import { ProjectDialog } from "@/components/dialogs/project-dialog";
import { TeamDialog } from "@/components/dialogs/team-dialog";
import { LoginScreen } from "@/components/login";
import { Composer } from "@/components/composer";
import { CommandPalette, DetailPanel, HelpOverlay } from "@/components/overlays";
import { SettingsPanel } from "@/components/settings/panel";
import { Sidebar } from "@/components/sidebar";
import { TicketList } from "@/components/tickets";
import { availableActions, resolveShortcut } from "@/lib/actions";
import { ApiError, getDevUser, setDevUser, type Ticket } from "@/lib/api";
import {
  useAuthMode,
  useDeleteTicket,
  useMe,
  usePatchTicket,
  usePreferences,
  useProjects,
  useSetupState,
  useSyncStatus,
  useTeams,
  useTickets,
} from "@/lib/queries";
import { FILTER_INPUT_ID, useActionContext } from "@/lib/use-action-ctx";
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

  const { scope, selectedId, overlay, dialog, query, setScope, select, open, close, setQuery } =
    useUi();
  const [editingId, setEditingId] = useState<string | undefined>();
  const [actionError, setActionError] = useState<{ scope: Scope; message: string } | null>(null);

  const teams = useTeams();
  const tickets = useTickets();
  const projects = useProjects();
  const sync = useSyncStatus();

  const patch = usePatchTicket();
  const remove = useDeleteTicket();

  const visible = useMemo(() => {
    const rows = tickets.data ?? [];
    const needle = query.trim().toLowerCase();
    if (!needle) return rows;
    return rows.filter(
      (ticket) =>
        ticket.title.toLowerCase().includes(needle) ||
        ticket.identifier.toLowerCase().includes(needle),
    );
  }, [tickets.data, query]);

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

  const ctx = useActionContext({ tickets: visible, selected, move, startRename, reportError });

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      // A modified key, so it never reaches the registry, which only owns bare ones.
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        open("palette");
        return;
      }

      // Overlays and dialogs own their own keys; the list must not react behind them.
      if (overlay !== "none" || dialog.kind !== "none" || editingId || isTypingTarget(event.target)) {
        if (event.key === "Escape") {
          close();
          setEditingId(undefined);
          (event.target as HTMLElement | null)?.blur?.();
        }
        return;
      }
      if (event.metaKey || event.ctrlKey || event.altKey) return;

      const action = resolveShortcut(event.key);
      // One predicate answers both "may I show this" and "may I run it", so a key
      // whose action is unavailable stays inert rather than half-firing.
      if (!action || !action.when(ctx)) return;
      event.preventDefault();
      action.run(ctx);
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [ctx, overlay, dialog, editingId, open, close]);

  const commands = useMemo(
    () => [
      ...availableActions(ctx).map((action) => ({
        id: action.id,
        label: action.label,
        hint: action.shortcut?.split(" ")[0],
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
    ],
    [ctx, teams.data, setScope, close],
  );

  if (me.isLoading || authMode.isLoading || setup.isLoading) {
    return <div className="centered">Loading…</div>;
  }

  // Ahead of the sign-in screen: with no owner yet there is nobody to sign in as.
  if (needsSetup) {
    return <div className="centered">Opening setup…</div>;
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
    <div className="shell" data-sidebar={preferences.sidebarVisible ? "shown" : "hidden"}>
      {preferences.sidebarVisible && <Sidebar ctx={ctx} syncSummary={mirrorSummary} />}

      <div className="main">
        <div className="topbar">
          <h1>{currentTeam ? currentTeam.name : "All tickets"}</h1>
          <span style={{ color: "var(--text-faint)", fontSize: 11 }}>{visible.length}</span>
          <span className="spacer" />
          <input
            id={FILTER_INPUT_ID}
            className="filter-input"
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
          <button className="button" onClick={() => open("composer")}>
            New <kbd>c</kbd>
          </button>
        </div>

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

        {tickets.error ? (
          <div className="empty error">{(tickets.error as Error).message}</div>
        ) : (
          <TicketList
            tickets={visible}
            selectedId={selectedId}
            editingId={editingId}
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
            <span>
              <kbd>⌘K</kbd> commands
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
      {overlay === "palette" && <CommandPalette commands={commands} onClose={close} />}
      {overlay === "help" && <HelpOverlay onClose={close} />}
      {overlay === "settings" && <SettingsPanel onClose={close} />}
      {overlay === "detail" && selected && (
        <DetailPanel
          ticket={selected}
          projects={projects.data ?? []}
          onPatch={(body) => patch.mutate({ id: selected.id, ...body })}
          onDelete={() => {
            remove.mutate(selected.id);
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
