import { describe, expect, it, vi } from "vitest";
import {
  ACTIONS,
  actionById,
  availableActions,
  hintOf,
  indexActions,
  predecessorsOf,
  resolveShortcut,
  shortcutRows,
  type Action,
  type ActionContext,
} from "./index";
import type { Project, Team, Ticket, TimelineDependency } from "../api";

const core: Team = {
  id: "team-core",
  name: "Core",
  key: "KAN",
  archived: false,
  ticketCount: 1,
  mirror: { state: "synced" },
  editable: true,
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

/** A ticket already on the chart, so the bar actions have something to move. */
const scheduled: Ticket = {
  ...ticket,
  id: "ticket-2",
  identifier: "KAN-2",
  start: { at: "2026-08-04T00:00:00Z", hasTime: false },
  due: { at: "2026-08-06T00:00:00Z", hasTime: false },
};

/** A second bar, so a predecessor can be one the chart draws rather than a tray chip. */
const earlier: Ticket = {
  ...ticket,
  id: "ticket-3",
  identifier: "KAN-3",
  title: "Migrate the schema",
  start: { at: "2026-08-01T00:00:00Z", hasTime: false },
  due: { at: "2026-08-02T00:00:00Z", hasTime: false },
};

const dependency = (predecessorId: string, successorId: string): TimelineDependency => ({
  predecessorId,
  successorId,
  violated: false,
  overlap: false,
  outOfScope: false,
});

function context(overrides: Partial<ActionContext> = {}): ActionContext {
  return {
    scope: { kind: "all" },
    teams: [core, legacy],
    projects: [refonte, audit],
    tickets: [ticket],
    selected: undefined,
    canConfigure: true,
    view: "list",
    zoom: "day",
    open: vi.fn(),
    close: vi.fn(),
    openDialog: vi.fn(),
    setScope: vi.fn(),
    setZoom: vi.fn(),
    move: vi.fn(),
    focusFilter: vi.fn(),
    startRename: vi.fn(),
    patchTicket: vi.fn(),
    unarchive: vi.fn(),
    deleteTicket: vi.fn(),
    recentre: vi.fn(),
    startLink: vi.fn(),
    dependencies: [],
    startUnlink: vi.fn(),
    logout: vi.fn(),
    ...overrides,
  };
}

/**
 * The same context, already on the timeline with a scheduled ticket under the cursor.
 *
 * Scoped to a team by default, not `context()`'s own "all": the ordinary chart under
 * test here is one you plan on, and `canPlan` now makes scope `all` the exceptional
 * case — which is why the tests about it override this back to `all` themselves.
 */
const timeline = (overrides: Partial<ActionContext> = {}) =>
  context({
    view: "timeline",
    scope: { kind: "team", id: core.id },
    tickets: [ticket, scheduled],
    selected: scheduled,
    ...overrides,
  });

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
  "team.rename",
  "team.archive",
  "team.unarchive",
  "team.delete",
  "project.create",
  "project.edit",
  "project.archive",
  "project.unarchive",
  "project.delete",
  "view.all",
  "view.filter",
  "app.palette",
  "app.settings",
  "app.help",
  "ticket.delete",
  "app.logout",
  "timeline.shiftEarlier",
  "timeline.shiftLater",
  "timeline.shrinkEnd",
  "timeline.growEnd",
  "timeline.schedule",
  "timeline.unschedule",
  "timeline.zoomOut",
  "timeline.zoomIn",
  "timeline.today",
  "timeline.link",
  "timeline.unlink",
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

  it("offers a delete for the selected ticket, and none without a selection", () => {
    expect(ids(context({ selected: ticket }))).toContain("ticket.delete");
    expect(ids(context({ selected: undefined }))).not.toContain("ticket.delete");
  });

  it("offers sign-out to everyone, member or admin", () => {
    expect(ids(context({ canConfigure: false }))).toContain("app.logout");
    expect(ids(context({ canConfigure: true }))).toContain("app.logout");
  });

  it("runs delete against the selected ticket, and sign-out against the session", () => {
    const deleteTicket = vi.fn();
    const logout = vi.fn();
    actionById("ticket.delete").run(context({ selected: ticket, deleteTicket }));
    actionById("app.logout").run(context({ logout }));
    expect(deleteTicket).toHaveBeenCalledWith(ticket.id);
    expect(logout).toHaveBeenCalledTimes(1);
  });
});

describe("resolveShortcut", () => {
  it("maps each key the inbox handles today to exactly one action", () => {
    expect(resolveShortcut("j", "list")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("ArrowDown", "list")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("k", "list")?.id).toBe("ticket.moveUp");
    expect(resolveShortcut("ArrowUp", "list")?.id).toBe("ticket.moveUp");
    expect(resolveShortcut("Enter", "list")?.id).toBe("ticket.open");
    expect(resolveShortcut("c", "list")?.id).toBe("ticket.create");
    expect(resolveShortcut("e", "list")?.id).toBe("ticket.rename");
    expect(resolveShortcut("x", "list")?.id).toBe("ticket.archive");
    expect(resolveShortcut("1", "list")?.id).toBe("ticket.status.backlog");
    expect(resolveShortcut("2", "list")?.id).toBe("ticket.status.todo");
    expect(resolveShortcut("3", "list")?.id).toBe("ticket.status.in_progress");
    expect(resolveShortcut("4", "list")?.id).toBe("ticket.status.in_review");
    expect(resolveShortcut("5", "list")?.id).toBe("ticket.status.done");
    expect(resolveShortcut("6", "list")?.id).toBe("ticket.status.canceled");
    expect(resolveShortcut("/", "list")?.id).toBe("view.filter");
    expect(resolveShortcut(",", "list")?.id).toBe("app.settings");
    expect(resolveShortcut("?", "list")?.id).toBe("app.help");
  });

  it("one key means different things in the two views", () => {
    expect(resolveShortcut("h", "timeline")?.id).toBe("timeline.shiftEarlier");
    expect(resolveShortcut("h", "list")).toBeUndefined();
  });

  it("a shared key still resolves in both", () => {
    expect(resolveShortcut("j", "list")?.id).toBe("ticket.moveDown");
    expect(resolveShortcut("j", "timeline")?.id).toBe("ticket.moveDown");
  });

  it("the shift and resize pair are distinct keys, not a modifier", () => {
    // `event.key` for Shift+h is "H", so the registry needs no modifier plumbing.
    expect(resolveShortcut("H", "timeline")?.id).toBe("timeline.shrinkEnd");
    expect(resolveShortcut("l", "timeline")?.id).toBe("timeline.shiftLater");
    expect(resolveShortcut("L", "timeline")?.id).toBe("timeline.growEnd");
  });

  it("keeps a key the list owns out of the timeline's reach when the mode says so", () => {
    // Renaming edits a row of the list; there is no row to edit on the chart, and
    // `e` there would arm an editor nothing renders and swallow every later key.
    expect(resolveShortcut("e", "timeline")).toBeUndefined();
  });

  it("lets no two actions claim the same key inside one mode", () => {
    const claimed = new Map<string, string>();
    for (const action of ACTIONS) {
      for (const key of action.shortcut?.split(" ") ?? []) {
        const bucket = `${action.mode ?? "any"}:${key}`;
        expect(claimed.get(bucket)).toBeUndefined();
        claimed.set(bucket, action.id);
      }
    }
  });

  it("lets no mode-specific key shadow one that works everywhere", () => {
    // Legal by the registry's rules — a mode bucket wins over `any` — but it would
    // mean one printed key doing two things, so nothing does it today.
    const shared = new Set(
      ACTIONS.filter((action) => action.mode === undefined).flatMap(
        (action) => action.shortcut?.split(" ") ?? [],
      ),
    );
    for (const action of ACTIONS.filter((candidate) => candidate.mode !== undefined)) {
      for (const key of action.shortcut?.split(" ") ?? []) {
        expect({ id: action.id, shadows: shared.has(key) }).toEqual({
          id: action.id,
          shadows: false,
        });
      }
    }
  });

  it("a duplicate key inside one mode is still a build-time error", () => {
    // The guard that made the registry trustworthy must survive the split.
    const clashing: Action[] = [
      { id: "a", label: "A", shortcut: "z", mode: "timeline", group: "view", when: () => true, run: () => {} },
      { id: "b", label: "B", shortcut: "z", mode: "timeline", group: "view", when: () => true, run: () => {} },
    ];
    expect(() => indexActions(clashing)).toThrow(/claimed by both/);
  });

  it("lets the two modes claim the same key, which is the point of the split", () => {
    const both: Action[] = [
      { id: "a", label: "A", shortcut: "z", mode: "timeline", group: "view", when: () => true, run: () => {} },
      { id: "b", label: "B", shortcut: "z", mode: "list", group: "view", when: () => true, run: () => {} },
    ];
    expect(() => indexActions(both)).not.toThrow();
  });

  it("still refuses two actions sharing an id, whatever their modes", () => {
    const twins: Action[] = [
      { id: "a", label: "A", mode: "timeline", group: "view", when: () => true, run: () => {} },
      { id: "a", label: "A again", mode: "list", group: "view", when: () => true, run: () => {} },
    ];
    expect(() => indexActions(twins)).toThrow(/Duplicate action id/);
  });

  it("leaves an unbound key alone", () => {
    expect(resolveShortcut("z", "list")).toBeUndefined();
    expect(resolveShortcut("Escape", "list")).toBeUndefined();
    expect(resolveShortcut("z", "timeline")).toBeUndefined();
  });

  it("keeps the palette off the bare keys, since it needs a modifier", () => {
    expect(actionById("app.palette").shortcut).toBeUndefined();
  });
});

describe("the timeline actions", () => {
  it("are offered on the chart and withheld from the list", () => {
    const onChart = ids(timeline());
    expect(onChart).toContain("timeline.shiftEarlier");
    expect(onChart).toContain("timeline.growEnd");
    expect(onChart).toContain("timeline.today");

    const inList = ids(context({ selected: scheduled }));
    expect(inList).not.toContain("timeline.shiftEarlier");
    expect(inList).not.toContain("timeline.today");
  });

  it("moves both bounds by one day, so the bar slides rather than stretches", () => {
    const ctx = timeline();
    actionById("timeline.shiftLater").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({
      id: scheduled.id,
      start: { at: "2026-08-05T00:00:00Z", hasTime: false },
      due: { at: "2026-08-07T00:00:00Z", hasTime: false },
    });
  });

  it("sends only the bound a milestone has, rather than inventing the other", () => {
    const milestone = { ...scheduled, start: undefined };
    const ctx = timeline({ selected: milestone, tickets: [milestone] });
    actionById("timeline.shiftEarlier").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({
      id: milestone.id,
      due: { at: "2026-08-05T00:00:00Z", hasTime: false },
    });
  });

  it("keeps the hour on a bound that names one", () => {
    const timed = {
      ...scheduled,
      start: undefined,
      due: { at: "2026-08-06T17:30:00Z", hasTime: true },
    };
    const ctx = timeline({ selected: timed, tickets: [timed] });
    actionById("timeline.growEnd").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({
      id: timed.id,
      due: { at: "2026-08-07T17:30:00Z", hasTime: true },
    });
  });

  it("moves only the end when resizing", () => {
    const ctx = timeline();
    actionById("timeline.growEnd").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({
      id: scheduled.id,
      due: { at: "2026-08-07T00:00:00Z", hasTime: false },
    });
  });

  it("refuses to pull the end back past the start, because the API answers that with a 400", () => {
    const oneDay = { ...scheduled, due: scheduled.start };
    const ctx = timeline({ selected: oneDay, tickets: [oneDay] });
    actionById("timeline.shrinkEnd").run(ctx);
    expect(ctx.patchTicket).not.toHaveBeenCalled();
  });

  it("offers resizing only to a bar that has an end to move", () => {
    const started = { ...scheduled, due: undefined };
    expect(ids(timeline({ selected: started, tickets: [started] }))).not.toContain(
      "timeline.growEnd",
    );
  });

  it("schedules an unplanned ticket as a one-day milestone, and only then", () => {
    const ctx = timeline({ selected: ticket, tickets: [ticket] });
    expect(ids(ctx)).toContain("timeline.schedule");
    expect(ids(timeline())).not.toContain("timeline.schedule");

    actionById("timeline.schedule").run(ctx);
    const [[patch]] = (ctx.patchTicket as ReturnType<typeof vi.fn>).mock.calls;
    expect(patch.id).toBe(ticket.id);
    expect(patch.start).toEqual(patch.due);
    expect(patch.start.hasTime).toBe(false);
    expect(patch.start.at).toMatch(/^\d{4}-\d{2}-\d{2}T00:00:00Z$/);
  });

  it("sends a scheduled ticket back to the tray by clearing both bounds", () => {
    const ctx = timeline();
    actionById("timeline.unschedule").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({
      id: scheduled.id,
      unset: ["start", "due"],
    });
    expect(ids(timeline({ selected: ticket, tickets: [ticket] }))).not.toContain(
      "timeline.unschedule",
    );
  });

  it("steps the zoom out and in, and stops at either end rather than wrapping", () => {
    const out = timeline({ zoom: "week" });
    actionById("timeline.zoomOut").run(out);
    expect(out.setZoom).toHaveBeenCalledWith("month");

    const inwards = timeline({ zoom: "week" });
    actionById("timeline.zoomIn").run(inwards);
    expect(inwards.setZoom).toHaveBeenCalledWith("day");

    const widest = timeline({ zoom: "month" });
    actionById("timeline.zoomOut").run(widest);
    expect(widest.setZoom).toHaveBeenCalledWith("month");
  });

  it("asks the palette for a predecessor rather than opening a mode of its own", () => {
    const ctx = timeline();
    actionById("timeline.link").run(ctx);
    expect(ctx.startLink).toHaveBeenCalledWith(scheduled.id);
    // No second ticket to depend on means nothing to pick from.
    expect(ids(timeline({ tickets: [scheduled] }))).not.toContain("timeline.link");
  });

  it("recentres on today with no selection at all", () => {
    const ctx = timeline({ selected: undefined });
    expect(ids(ctx)).toContain("timeline.today");
    actionById("timeline.today").run(ctx);
    expect(ctx.recentre).toHaveBeenCalledTimes(1);
  });
});

describe("predecessorsOf", () => {
  it("names a predecessor drawn as a bar and one waiting in the tray", () => {
    // `ticket` has no dates, so it is a tray chip; `earlier` is a bar. Both are rows of
    // the tickets query, which is the only thing the rule asks.
    const ctx = timeline({
      tickets: [ticket, scheduled, earlier],
      dependencies: [dependency(earlier.id, scheduled.id), dependency(ticket.id, scheduled.id)],
    });
    expect(predecessorsOf(ctx, scheduled.id).map((row) => row.id)).toEqual([earlier.id, ticket.id]);
  });

  it("drops an edge whose other end is outside this scope", () => {
    // The server's own word for "absent from this response". Being out of scope, it is
    // absent from the tickets query too, so there is no name to print.
    const ctx = timeline({
      dependencies: [
        {
          predecessorId: "ticket-elsewhere",
          successorId: scheduled.id,
          violated: false,
          overlap: false,
          outOfScope: true,
        },
      ],
    });
    expect(predecessorsOf(ctx, scheduled.id)).toEqual([]);
  });

  it("ignores the edges of another successor", () => {
    const ctx = timeline({ dependencies: [dependency(scheduled.id, ticket.id)] });
    expect(predecessorsOf(ctx, scheduled.id)).toEqual([]);
  });
});

describe("timeline.unlink", () => {
  it("is offered only when the selected ticket waits on something nameable", () => {
    expect(ids(timeline())).not.toContain("timeline.unlink");

    const waiting = timeline({ dependencies: [dependency(ticket.id, scheduled.id)] });
    expect(ids(waiting)).toContain("timeline.unlink");

    const unnameable = timeline({
      dependencies: [
        {
          predecessorId: "ticket-elsewhere",
          successorId: scheduled.id,
          violated: false,
          overlap: false,
          outOfScope: true,
        },
      ],
    });
    expect(ids(unnameable)).not.toContain("timeline.unlink");
  });

  it("is withheld from the list, where there is no arrow to erase", () => {
    const inList = context({
      selected: scheduled,
      tickets: [ticket, scheduled],
      dependencies: [dependency(ticket.id, scheduled.id)],
    });
    expect(ids(inList)).not.toContain("timeline.unlink");
  });

  it("is withheld while the timeline query has not answered", () => {
    // The first action in the registry whose availability depends on a fetch: with no
    // edges loaded, nothing knows whether there is anything to erase.
    expect(ids(timeline({ dependencies: [] }))).not.toContain("timeline.unlink");
  });

  it("asks the palette which arrow to erase, rather than erasing one on its own", () => {
    const ctx = timeline({ dependencies: [dependency(ticket.id, scheduled.id)] });
    actionById("timeline.unlink").run(ctx);
    expect(ctx.startUnlink).toHaveBeenCalledWith(scheduled.id);
    expect(ctx.patchTicket).not.toHaveBeenCalled();
  });

  it("answers Shift+D on the chart and nothing in the list", () => {
    // `event.key` for Shift+d is "D" — the same convention `H` and `L` follow, so the
    // registry still carries no modifier state.
    expect(resolveShortcut("D", "timeline")?.id).toBe("timeline.unlink");
    expect(resolveShortcut("D", "list")).toBeUndefined();
    expect(resolveShortcut("d", "timeline")?.id).toBe("timeline.link");
  });
});

const WRITING_CHART_ACTIONS = [
  "timeline.shiftEarlier",
  "timeline.shiftLater",
  "timeline.shrinkEnd",
  "timeline.growEnd",
  "timeline.schedule",
  "timeline.unschedule",
  "timeline.link",
  "timeline.unlink",
];

const VIEWPORT_CHART_ACTIONS = ["timeline.zoomOut", "timeline.zoomIn", "timeline.today"];

describe("the global timeline is read-only", () => {
  it("refuses every writing chart action in scope all", () => {
    // A scheduled, linked ticket selected: every one of the eight would be live on its
    // own terms, so a `false` here can only be `canPlan` speaking.
    const ctx = timeline({
      scope: { kind: "all" },
      dependencies: [dependency(ticket.id, scheduled.id)],
    });
    for (const id of WRITING_CHART_ACTIONS) {
      expect(actionById(id).when(ctx), `${id} must be inert on the global chart`).toBe(false);
    }
  });

  it("keeps the viewport actions live, because navigating is not planning", () => {
    const ctx = timeline({ scope: { kind: "all" } });
    for (const id of VIEWPORT_CHART_ACTIONS) {
      expect(actionById(id).when(ctx), `${id} moves the viewport, not the plan`).toBe(true);
    }
  });

  it("allows the writing actions again inside a team", () => {
    const ctx = timeline({ scope: { kind: "team", id: core.id } });
    expect(actionById("timeline.shiftLater").when(ctx)).toBe(true);
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

  it("withholds a project's disposition from someone who cannot configure the instance", () => {
    // Archiving or deleting a project reaches every ticket it holds, the same blast
    // radius as a team's — a member gets neither, same as `team.archive`/`team.delete`.
    const member = ids(
      context({ canConfigure: false, scope: { kind: "project", id: refonte.id } }),
    );
    expect(member).not.toContain("project.archive");
    expect(member).not.toContain("project.delete");

    // Unarchiving is the same disposition seen from the other side — see
    // `project.unarchive`'s `when`, which now matches `team.unarchive`'s.
    const memberOnArchived = ids(
      context({ canConfigure: false, scope: { kind: "project", id: audit.id } }),
    );
    expect(memberOnArchived).not.toContain("project.unarchive");
  });

  it("withholds the cursor moves when the list is empty, and offers them when it is not", () => {
    const empty = ids(context({ tickets: [] }));
    expect(empty).not.toContain("ticket.moveDown");
    expect(empty).not.toContain("ticket.moveUp");

    const populated = ids(context());
    expect(populated).toContain("ticket.moveDown");
    expect(populated).toContain("ticket.moveUp");
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

  it("opens the project dialog carrying the scoped team's id", () => {
    const ctx = context({ scope: { kind: "team", id: core.id } });
    actionById("project.create").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "project", teamId: core.id });
  });

  it("opens the project dialog carrying a project scope's own team, one level up", () => {
    const ctx = context({ scope: { kind: "project", id: refonte.id } });
    actionById("project.create").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "project", teamId: core.id });
  });

  it("opens the team dialog with the scoped team as parent", () => {
    const ctx = context({ scope: { kind: "team", id: core.id } });
    actionById("team.create").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "team", parentTeamId: core.id });
  });

  it("opens the team dialog with a project scope's own team as parent, one level up", () => {
    const ctx = context({ scope: { kind: "project", id: refonte.id } });
    actionById("team.create").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "team", parentTeamId: core.id });
  });

  it("leaves both dialogs unseeded from the all-tickets scope", () => {
    const ctx = context({ scope: { kind: "all" } });
    actionById("project.create").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "project", teamId: undefined });
    actionById("team.create").run(ctx);
    expect(ctx.openDialog).toHaveBeenCalledWith({ kind: "team", parentTeamId: undefined });
  });

  it("closes the palette after a status change, so the list is visible again", () => {
    const ctx = context({ selected: ticket });
    actionById("ticket.status.in_review").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, status: "in_review" });
    expect(ctx.close).toHaveBeenCalled();
  });
});

describe("hintOf", () => {
  it("prints the palette's key as the one the reader actually has", () => {
    const palette = actionById("app.palette");
    expect(hintOf(palette, true)).toBe("⌘K");
    expect(hintOf(palette, false)).toBe("Ctrl+K");
  });

  it("keeps that hint out of the dispatch table", () => {
    // The whole point of a second field: ⌘K is intercepted ahead of the registry, and
    // `k` there would collide with `ticket.moveUp`.
    expect(actionById("app.palette").shortcut).toBeUndefined();
    expect(resolveShortcut("K", "list")).toBeUndefined();
    expect(resolveShortcut("k", "list")?.id).toBe("ticket.moveUp");
  });

  it("falls back to the first spelling of a shortcut, printed for a human", () => {
    expect(hintOf(actionById("ticket.moveDown"), true)).toBe("j");
    expect(hintOf(actionById("app.help"), true)).toBe("?");

    const arrowOnly: Action = {
      id: "test.arrow",
      label: "Arrow",
      shortcut: "ArrowDown",
      group: "view",
      when: () => true,
      run: () => {},
    };
    expect(hintOf(arrowOnly, true)).toBe("↓");
  });

  it("prints nothing for an action the keyboard cannot reach", () => {
    expect(hintOf(actionById("project.create"), true)).toBeUndefined();
  });
});

describe("shortcutRows", () => {
  it("is derived from the actions the keyboard can reach, not written by hand", () => {
    const bound = ACTIONS.filter(
      (action) => action.shortcut !== undefined || action.hint !== undefined,
    );
    const rows = shortcutRows(true);

    expect(rows).toHaveLength(bound.length);
    for (const action of bound) {
      expect(rows.some((row) => row.label === action.label)).toBe(true);
    }
  });

  it("carries the palette's row, which the overlay used to draw by hand", () => {
    expect(shortcutRows(true).find((row) => row.label === "Command palette")).toEqual({
      mode: undefined,
      keys: "⌘K",
      label: "Command palette",
    });
    expect(shortcutRows(false).find((row) => row.label === "Command palette")?.keys).toBe("Ctrl+K");
  });

  it("prints the arrow keys as arrows rather than as DOM key names", () => {
    const rows = shortcutRows(true);
    expect(rows.find((row) => row.label === "Move down")?.keys).toBe("j / ↓");
    expect(rows.find((row) => row.label === "Move up")?.keys).toBe("k / ↑");
  });

  it("carries no row for an action the keyboard cannot reach", () => {
    expect(shortcutRows(true).some((row) => row.label === "New project")).toBe(false);
  });

  it("names the mode of every row, so the help overlay can group them", () => {
    const rows = shortcutRows(true);
    // Undefined, not "list": the row belongs to both views and the overlay says so.
    expect(rows.find((row) => row.label === "Move down")?.mode).toBeUndefined();
    expect(rows.find((row) => row.label === "Move bar earlier")?.mode).toBe("timeline");
    expect(rows.find((row) => row.label === "Rename ticket")?.mode).toBe("list");
  });
});
