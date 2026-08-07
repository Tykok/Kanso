import { describe, expect, it, vi } from "vitest";
import {
  ACTIONS,
  actionById,
  availableActions,
  resolveShortcut,
  shortcutRows,
  type ActionContext,
} from "./actions";
import type { Project, Team, Ticket } from "./api";

const core: Team = {
  id: "team-core",
  name: "Core",
  key: "KAN",
  archived: false,
  ticketCount: 1,
  mirror: { state: "synced" },
};

const legacy: Team = { ...core, id: "team-legacy", name: "Legacy", key: "LEG", archived: true };

const refonte: Project = {
  id: "project-refonte",
  name: "Refonte",
  status: "active",
  teamId: "team-core",
  archived: false,
  mirror: { state: "synced" },
};

const audit: Project = { ...refonte, id: "project-audit", name: "Audit", archived: true };

const ticket: Ticket = {
  id: "ticket-1",
  identifier: "KAN-1",
  number: 1,
  teamId: "team-core",
  title: "Fix the OAuth login",
  status: "todo",
  priority: "medium",
  assigneeIds: [],
  docIds: [],
  archived: false,
  mirror: { state: "synced" },
  createdAt: "2026-08-07T09:00:00Z",
  updatedAt: "2026-08-07T09:00:00Z",
};

function context(overrides: Partial<ActionContext> = {}): ActionContext {
  return {
    scope: { kind: "all" },
    teams: [core, legacy],
    projects: [refonte, audit],
    tickets: [ticket],
    selected: undefined,
    canConfigure: true,
    open: vi.fn(),
    close: vi.fn(),
    openDialog: vi.fn(),
    setScope: vi.fn(),
    move: vi.fn(),
    focusFilter: vi.fn(),
    startRename: vi.fn(),
    patchTicket: vi.fn(),
    unarchive: vi.fn(),
    ...overrides,
  };
}

const ids = (ctx: ActionContext) => availableActions(ctx).map((action) => action.id);

/** Every id a menu, a button or a test is allowed to name. */
const REQUIRED_IDS = [
  "ticket.create",
  "ticket.open",
  "ticket.rename",
  "ticket.archive",
  "ticket.moveDown",
  "ticket.moveUp",
  "ticket.status.backlog",
  "ticket.status.todo",
  "ticket.status.in_progress",
  "ticket.status.in_review",
  "ticket.status.done",
  "ticket.status.canceled",
  "team.create",
  "team.createChild",
  "team.rename",
  "team.archive",
  "team.unarchive",
  "team.delete",
  "project.create",
  "project.createInTeam",
  "project.edit",
  "project.archive",
  "project.unarchive",
  "project.delete",
  "view.all",
  "view.filter",
  "app.palette",
  "app.settings",
  "app.help",
];

describe("the registry", () => {
  it("defines every action the menus reference by id", () => {
    for (const id of REQUIRED_IDS) {
      expect(actionById(id).id).toBe(id);
    }
  });

  it("throws on an unknown id rather than silently doing nothing", () => {
    expect(() => actionById("team.rename-v2")).toThrow(/Unknown action/);
  });
});

describe("resolveShortcut", () => {
  it("maps each key the inbox handles today to exactly one action", () => {
    expect(resolveShortcut("j")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("ArrowDown")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("k")?.id).toBe("ticket.moveUp");
    expect(resolveShortcut("ArrowUp")?.id).toBe("ticket.moveUp");
    expect(resolveShortcut("Enter")?.id).toBe("ticket.open");
    expect(resolveShortcut("c")?.id).toBe("ticket.create");
    expect(resolveShortcut("e")?.id).toBe("ticket.rename");
    expect(resolveShortcut("x")?.id).toBe("ticket.archive");
    expect(resolveShortcut("1")?.id).toBe("ticket.status.backlog");
    expect(resolveShortcut("2")?.id).toBe("ticket.status.todo");
    expect(resolveShortcut("3")?.id).toBe("ticket.status.in_progress");
    expect(resolveShortcut("4")?.id).toBe("ticket.status.in_review");
    expect(resolveShortcut("5")?.id).toBe("ticket.status.done");
    expect(resolveShortcut("6")?.id).toBe("ticket.status.canceled");
    expect(resolveShortcut("/")?.id).toBe("view.filter");
    expect(resolveShortcut(",")?.id).toBe("app.settings");
    expect(resolveShortcut("?")?.id).toBe("app.help");
  });

  it("lets no two actions claim the same key", () => {
    const claimed = new Map<string, string>();
    for (const action of ACTIONS) {
      for (const key of action.shortcut?.split(" ") ?? []) {
        expect(claimed.get(key)).toBeUndefined();
        claimed.set(key, action.id);
      }
    }
  });

  it("leaves an unbound key alone", () => {
    expect(resolveShortcut("z")).toBeUndefined();
    expect(resolveShortcut("Escape")).toBeUndefined();
  });

  it("keeps the palette off the bare keys, since it needs a modifier", () => {
    expect(actionById("app.palette").shortcut).toBeUndefined();
  });
});

describe("availableActions", () => {
  it("hides every team action from someone who cannot configure the instance", () => {
    const member = context({ canConfigure: false, scope: { kind: "team", id: core.id } });
    expect(availableActions(member).filter((action) => action.group === "team")).toEqual([]);
  });

  it("offers them to an admin on the same scope", () => {
    const admin = ids(context({ scope: { kind: "team", id: core.id } }));
    expect(admin).toContain("team.create");
    expect(admin).toContain("team.createChild");
    expect(admin).toContain("team.rename");
    expect(admin).toContain("team.delete");
  });

  it("offers archive on a live team and unarchive on an archived one, never both", () => {
    const live = ids(context({ scope: { kind: "team", id: core.id } }));
    expect(live).toContain("team.archive");
    expect(live).not.toContain("team.unarchive");

    const archived = ids(context({ scope: { kind: "team", id: legacy.id } }));
    expect(archived).toContain("team.unarchive");
    expect(archived).not.toContain("team.archive");
  });

  it("withholds ticket actions while nothing is selected", () => {
    const empty = ids(context());
    expect(empty).not.toContain("ticket.open");
    expect(empty).not.toContain("ticket.rename");
    expect(empty).not.toContain("ticket.archive");
    expect(empty).not.toContain("ticket.status.done");
    // Filing a new ticket needs no selection — that is the whole point of `c`.
    expect(empty).toContain("ticket.create");
  });

  it("offers them once a ticket is selected", () => {
    const selected = ids(context({ selected: ticket }));
    expect(selected).toContain("ticket.open");
    expect(selected).toContain("ticket.rename");
    expect(selected).toContain("ticket.archive");
    expect(selected).toContain("ticket.status.done");
  });

  it("withholds project actions outside a project scope", () => {
    const all = ids(context());
    expect(all).not.toContain("project.edit");
    expect(all).not.toContain("project.archive");
    expect(all).not.toContain("project.delete");
    // Creating one is always allowed: a project needs no team.
    expect(all).toContain("project.create");
  });

  it("offers them inside one, and unarchive on an archived project", () => {
    const live = ids(context({ scope: { kind: "project", id: refonte.id } }));
    expect(live).toContain("project.edit");
    expect(live).toContain("project.archive");
    expect(live).toContain("project.delete");
    expect(live).not.toContain("project.unarchive");

    const archived = ids(context({ scope: { kind: "project", id: audit.id } }));
    expect(archived).toContain("project.unarchive");
    expect(archived).not.toContain("project.archive");
  });

  it("drops the all-tickets view when it is already the scope", () => {
    expect(ids(context())).not.toContain("view.all");
    expect(ids(context({ scope: { kind: "team", id: core.id } }))).toContain("view.all");
  });
});

describe("running an action", () => {
  it("archives a ticket through the same patch the list uses", () => {
    const ctx = context({ selected: ticket });
    actionById("ticket.archive").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, archived: true });
  });

  it("unarchives it back", () => {
    const ctx = context({ selected: { ...ticket, archived: true } });
    actionById("ticket.archive").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, archived: false });
  });

  it("does nothing when a ticket action runs with no selection", () => {
    const ctx = context();
    actionById("ticket.rename").run(ctx);
    expect(ctx.startRename).not.toHaveBeenCalled();
  });

  it("opens the disposition dialog at the severity the action carries", () => {
    const ctx = context({ scope: { kind: "team", id: core.id } });
    actionById("team.delete").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({
      kind: "disposition",
      target: { kind: "team", id: core.id },
      severity: "delete",
    });
  });

  it("opens the team dialog with a parent when creating a sub-team", () => {
    const ctx = context({ scope: { kind: "team", id: core.id } });
    actionById("team.createChild").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "team", parentTeamId: core.id });
  });

  it("closes the palette after a status change, so the list is visible again", () => {
    const ctx = context({ selected: ticket });
    actionById("ticket.status.in_review").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, status: "in_review" });
    expect(ctx.close).toHaveBeenCalled();
  });
});

describe("shortcutRows", () => {
  it("is derived from the actions carrying a shortcut, not written by hand", () => {
    const bound = ACTIONS.filter((action) => action.shortcut !== undefined);
    const rows = shortcutRows();

    expect(rows).toHaveLength(bound.length);
    for (const action of bound) {
      expect(rows.some((row) => row.label === action.label)).toBe(true);
    }
  });

  it("prints the arrow keys as arrows rather than as DOM key names", () => {
    const rows = shortcutRows();
    expect(rows.find((row) => row.label === "Move down")?.keys).toBe("j / ↓");
    expect(rows.find((row) => row.label === "Move up")?.keys).toBe("k / ↑");
  });

  it("carries no row for an action the keyboard cannot reach", () => {
    expect(shortcutRows().some((row) => row.label === "New project")).toBe(false);
  });
});
