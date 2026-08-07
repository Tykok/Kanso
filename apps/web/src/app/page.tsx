"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { LoginScreen } from "@/components/login";
import { CommandPalette, Composer, DetailPanel, HelpOverlay } from "@/components/overlays";
import { statusLabel } from "@/components/pills";
import { SettingsPanel } from "@/components/settings/panel";
import { Sidebar } from "@/components/sidebar";
import { TicketList } from "@/components/tickets";
import {
  ApiError,
  TICKET_PRIORITIES,
  TICKET_STATUSES,
  getDevUser,
  setDevUser,
  type Ticket,
  type TicketPriority,
  type TicketStatus,
} from "@/lib/api";
import {
  useAuthMode,
  useCreateTicket,
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
import { useUi } from "@/store/ui";

const isTypingTarget = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT");

export default function InboxPage() {
  const router = useRouter();
  const authMode = useAuthMode();
  const me = useMe();
  const setup = useSetupState();
  const preferences = usePreferences();

  const { teamId, selectedId, overlay, query, setTeam, select, open, close, setQuery } = useUi();
  const [editingId, setEditingId] = useState<string | undefined>();
  const filterRef = useRef<HTMLInputElement>(null);

  const teams = useTeams();
  const tickets = useTickets(teamId);
  const projects = useProjects(teamId);
  const sync = useSyncStatus();

  const patch = usePatchTicket(teamId);
  const create = useCreateTicket(teamId);
  const remove = useDeleteTicket(teamId);

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
  const currentTeam = teams.data?.find((team) => team.id === teamId);

  const move = useCallback(
    (delta: number) => {
      if (visible.length === 0) return;
      const index = visible.findIndex((ticket) => ticket.id === selectedId);
      const next = Math.min(Math.max((index < 0 ? 0 : index) + delta, 0), visible.length - 1);
      select(visible[next].id);
    },
    [visible, selectedId, select],
  );

  const setStatus = useCallback(
    (status: TicketStatus) => {
      if (selected) patch.mutate({ id: selected.id, status });
    },
    [selected, patch],
  );

  const setPriority = useCallback(
    (priority: TicketPriority) => {
      if (selected) patch.mutate({ id: selected.id, priority });
    },
    [selected, patch],
  );

  const createTicket = useCallback(
    (title: string) => {
      const target = teamId ?? teams.data?.[0]?.id;
      if (!target) return;
      create.mutate({ teamId: target, title }, { onSuccess: () => close() });
    },
    [teamId, teams.data, create, close],
  );

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        open("palette");
        return;
      }

      // Overlays own their own keys; the list must not react behind them.
      if (overlay !== "none" || editingId || isTypingTarget(event.target)) {
        if (event.key === "Escape") {
          close();
          setEditingId(undefined);
          (event.target as HTMLElement | null)?.blur?.();
        }
        return;
      }
      if (event.metaKey || event.ctrlKey || event.altKey) return;

      switch (event.key) {
        case "j":
        case "ArrowDown":
          event.preventDefault();
          move(1);
          break;
        case "k":
        case "ArrowUp":
          event.preventDefault();
          move(-1);
          break;
        case "Enter":
          if (selected) {
            event.preventDefault();
            open("detail");
          }
          break;
        case "c":
          event.preventDefault();
          open("composer");
          break;
        case "e":
          if (selected) {
            event.preventDefault();
            setEditingId(selected.id);
          }
          break;
        case "x":
          if (selected) {
            event.preventDefault();
            patch.mutate({ id: selected.id, archived: !selected.archived });
          }
          break;
        case "/":
          event.preventDefault();
          filterRef.current?.focus();
          break;
        case "?":
          event.preventDefault();
          open("help");
          break;
        case ",":
          event.preventDefault();
          open("settings");
          break;
        default:
          // 1..6 walk the status vocabulary in its natural order.
          if (/^[1-6]$/.test(event.key)) {
            event.preventDefault();
            setStatus(TICKET_STATUSES[Number(event.key) - 1]);
          }
      }
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [overlay, editingId, selected, move, open, close, patch, setStatus]);

  const commands = useMemo(
    () => [
      { id: "new", label: "New ticket", hint: "c", run: () => open("composer") },
      ...TICKET_STATUSES.map((status, index) => ({
        id: `status-${status}`,
        label: `Set status: ${statusLabel(status)}`,
        hint: String(index + 1),
        run: () => {
          setStatus(status);
          close();
        },
      })),
      ...TICKET_PRIORITIES.map((priority) => ({
        id: `priority-${priority}`,
        label: `Set priority: ${priority}`,
        run: () => {
          setPriority(priority);
          close();
        },
      })),
      {
        id: "all-teams",
        label: "View: all tickets",
        run: () => {
          setTeam(undefined);
          close();
        },
      },
      ...(teams.data ?? []).map((team) => ({
        id: `team-${team.id}`,
        label: `View team: ${team.name}`,
        run: () => {
          setTeam(team.id);
          close();
        },
      })),
      { id: "settings", label: "Settings", hint: ",", run: () => open("settings") },
      { id: "help", label: "Keyboard shortcuts", hint: "?", run: () => open("help") },
    ],
    [teams.data, open, close, setStatus, setPriority, setTeam],
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
      {preferences.sidebarVisible && (
        <Sidebar
          teams={teams.data ?? []}
          activeTeamId={teamId}
          onSelectTeam={setTeam}
          syncSummary={mirrorSummary}
        />
      )}

      <div className="main">
        <div className="topbar">
          <h1>{currentTeam ? currentTeam.name : "All tickets"}</h1>
          <span style={{ color: "var(--text-faint)", fontSize: 11 }}>{visible.length}</span>
          <span className="spacer" />
          <input
            ref={filterRef}
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

      {overlay === "composer" && (
        <Composer onCreate={createTicket} onClose={close} pending={create.isPending} />
      )}
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
