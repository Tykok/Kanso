import { describe, expect, it, vi } from "vitest";
import {
  ACTIONS,
  actionById,
  availableActions,
  claim,
  clearClaims,
  hintOf,
  indexActions,
  predecessorsOf,
  type Action,
  type ActionContext,
  type ShortcutMode,
} from "./index";
import {
  DEFAULT_BINDINGS,
  DEFAULT_MERGE,
  hintFor,
  resolveShortcut,
  shortcutRows,
} from "../shortcuts";
import type { Project, Team, Ticket, TimelineDependency } from "../api";

/**
 * The registry's own keys, resolved. `mergeBindings` is what a running app dispatches
 * against; with no overrides its index is the defaults, which is what these assertions are
 * about — `lib/shortcuts.test.ts` is where a *remapped* keyboard is exercised.
 */
const resolve = (chord: string, mode: ShortcutMode) =>
  resolveShortcut(chord, mode, DEFAULT_MERGE.index);

const core: Team = {
  id: "team-core",
  name: "Core",
  key: "KAN",
  // Empty on purpose: nothing here reads a status word, and an empty catalogue is what
  // `lib/statuses.ts` answers Kanso's own six for.
  statuses: [],
  archived: false,
  ticketCount: 1,
  mirror: { state: "synced" },
  editable: true,
};

const legacy: Team = { ...core, id: "team-legacy", name: "Legacy", key: "LEG", archived: true };

/**
 * A team that named its own list, and a shorter one — the case `KAN-92` is about.
 *
 * Four words where Kanso ships six, one of them (`devis`) a key Kanso has never had, and
 * `livre` where `done` would be. A digit that wrote a literal would be refused by the
 * server on every one of these rows.
 */
const atelier: Team = {
  ...core,
  id: "team-atelier",
  name: "Atelier",
  key: "ATL",
  statuses: [
    { key: "boite", label: "Boîte", category: "backlog", position: 0 },
    { key: "devis", label: "Devis", category: "backlog", position: 1 },
    { key: "en_cours", label: "En cours", category: "started", position: 2 },
    { key: "livre", label: "Livré", category: "completed", position: 3 },
  ],
};

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
  customFields: {},
  // Required on `Ticket` for the reason `customFields` beside it is: the server always
  // sends the key, so a factory that omits it is not a ticket the API can produce.
  pullRequests: [],
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
    canWrite: true,
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
    toggleFavourite: vi.fn(),
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
  "ticket.status.1",
  "ticket.status.2",
  "ticket.status.3",
  "ticket.status.4",
  "ticket.status.5",
  "ticket.status.6",
  "ticket.status.pick",
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
  "app.back",
  "view.cycleDrawing",
  "ticket.openInPage",
  "ticket.priority.pick",
  "inbox.markAllRead",
  "triage.accept",
  "triage.defer",
  "triage.duplicate",
  "triage.reject",
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
  /**
   * Every key the list answered before the registry existed, and §6.4's two respellings.
   *
   * `j` and `k` are gone, on the maintainer's ruling, and `n` and `p` answer "next" and
   * "previous" in every drawing — with the arrows kept as the second spelling of both, so
   * nothing became unreachable. Nothing else in this list moved: `c`, `↵`, `e`, `x`,
   * `1`–`6`, `/`, `,` and `?` mean exactly what they meant, which is the whole point of a
   * rename that is supposed to protect muscle memory rather than spend it.
   */
  it("maps each key the list handles to exactly one action", () => {
    expect(resolve("n", "list")?.id).toBe("ticket.moveDown");
    expect(resolve("ArrowDown", "list")?.id).toBe("ticket.moveDown");
    expect(resolve("p", "list")?.id).toBe("ticket.moveUp");
    expect(resolve("ArrowUp", "list")?.id).toBe("ticket.moveUp");
    expect(resolve("Enter", "list")?.id).toBe("ticket.open");
    expect(resolve("c", "list")?.id).toBe("ticket.create");
    expect(resolve("e", "list")?.id).toBe("ticket.rename");
    expect(resolve("x", "list")?.id).toBe("ticket.archive");
    expect(resolve("1", "list")?.id).toBe("ticket.status.1");
    expect(resolve("2", "list")?.id).toBe("ticket.status.2");
    expect(resolve("3", "list")?.id).toBe("ticket.status.3");
    expect(resolve("4", "list")?.id).toBe("ticket.status.4");
    expect(resolve("5", "list")?.id).toBe("ticket.status.5");
    expect(resolve("6", "list")?.id).toBe("ticket.status.6");
    expect(resolve("/", "list")?.id).toBe("view.filter");
    expect(resolve(",", "list")?.id).toBe("app.settings");
    expect(resolve("?", "list")?.id).toBe("app.help");
  });

  /** The two §6.4 drops, asserted as drops: a reader pressing them gets nothing. */
  it("no longer answers j or k anywhere", () => {
    for (const mode of ["list", "board", "timeline"] as const) {
      expect(resolve("j", mode)).toBeUndefined();
      expect(resolve("k", mode)).toBeUndefined();
    }
  });

  /** `p` was `timeline.schedule` and is now "previous". The chart's `p` is gone with it. */
  it("gives p to the cursor, and leaves scheduling to the palette and the row menu", () => {
    expect(resolve("p", "timeline")?.id).toBe("ticket.moveUp");
    expect(actionById("timeline.schedule").defaultKeys).toBeUndefined();
  });

  it("one key means different things in the two views", () => {
    expect(resolve("h", "timeline")?.id).toBe("timeline.shiftEarlier");
    expect(resolve("h", "list")).toBeUndefined();
  });

  it("a shared key still resolves in both", () => {
    expect(resolve("n", "list")?.id).toBe("ticket.moveDown");
    expect(resolve("n", "timeline")?.id).toBe("ticket.moveDown");
  });

  /**
   * The shift pair, respelled. It used to be `"H"` and `"L"` — the `event.key` of the
   * press — which worked for letters and for nothing else, which is why `⇧↑↓` could not be
   * written at all. The chord carries the modifier now and the two halves of a bar edit
   * read as a pair.
   */
  it("spells the shift pair as a prefix rather than as a letter's case", () => {
    expect(resolve("Shift+h", "timeline")?.id).toBe("timeline.shrinkEnd");
    expect(resolve("l", "timeline")?.id).toBe("timeline.shiftLater");
    expect(resolve("Shift+l", "timeline")?.id).toBe("timeline.growEnd");
    // The retired spelling reaches nothing, and cannot: `chordOf` never produces it.
    expect(resolve("H", "timeline")).toBeUndefined();
    expect(resolve("L", "timeline")).toBeUndefined();
  });

  it("keeps a key the list owns out of the timeline's reach when the mode says so", () => {
    // Renaming edits a row of the list; there is no row to edit on the chart, and
    // `e` there would arm an editor nothing renders and swallow every later key.
    expect(resolve("e", "timeline")).toBeUndefined();
  });

  it("lets no two actions claim the same key inside one mode", () => {
    const claimed = new Map<string, string>();
    for (const action of ACTIONS) {
      for (const chord of action.defaultKeys ?? []) {
        const bucket = `${action.mode ?? "any"}:${chord}`;
        expect(claimed.get(bucket)).toBeUndefined();
        claimed.set(bucket, action.id);
      }
    }
  });

  it("lets no mode-specific key shadow one that works everywhere", () => {
    /**
     * Legal by the registry's rules — a mode bucket wins over `any` — and refused here
     * anyway, because in general it means one printed key doing two things.
     *
     * The exceptions are listed, not permitted by category. `n`, `p` and `↵` on the board
     * are the same intent as in the list — next item, previous item, open this — and a
     * board has no rows to walk, so the shared action would do nothing there.
     * `organise.select` and `triage.reject` are the two that made `savedView` and `triage`
     * modes at all: `x` is a checkbox on a saved view and "close without action" in the
     * queue, and neither screen has a selected ticket for the shared `x` to archive.
     * Anything else that wants to shadow a shared key has to be added to this set, which
     * is the point: the rule still refuses by default and the exception is a diff somebody
     * reads.
     */
    const MAY_SHADOW = new Set([
      "board.moveDown",
      "board.moveUp",
      "board.open",
      "organise.select",
      "triage.reject",
    ]);
    const shared = new Set(
      ACTIONS.filter((action) => action.mode === undefined).flatMap(
        (action) => action.defaultKeys ?? [],
      ),
    );
    for (const action of ACTIONS.filter((candidate) => candidate.mode !== undefined)) {
      if (MAY_SHADOW.has(action.id)) continue;
      for (const chord of action.defaultKeys ?? []) {
        expect({ id: action.id, shadows: shared.has(chord) }).toEqual({
          id: action.id,
          shadows: false,
        });
      }
    }
  });

  it("a duplicate key inside one mode is still a build-time error", () => {
    // The guard that made the registry trustworthy must survive the split.
    const clashing: Action[] = [
      { id: "a", label: "A", defaultKeys: ["z"], mode: "timeline", group: "view", when: () => true, run: () => {} },
      { id: "b", label: "B", defaultKeys: ["z"], mode: "timeline", group: "view", when: () => true, run: () => {} },
    ];
    expect(() => indexActions(clashing)).toThrow(/claimed by both/);
  });

  /**
   * The third throw, new with chords: a default that is not a chord is a key nobody can
   * ever press, and `"E"` in particular is the retired spelling this grammar exists to
   * remove. A developer's typo stops the build; a reader's stored preference never does —
   * `mergeBindings` refuses that one with a sentence instead.
   */
  it("refuses a default that is not a chord in canonical spelling", () => {
    const typo = (chord: string): Action[] => [
      { id: "a", label: "A", defaultKeys: [chord], group: "view", when: () => true, run: () => {} },
    ];
    expect(() => indexActions(typo("E"))).toThrow(/not a chord/);
    expect(() => indexActions(typo("Shift+E"))).toThrow(/not a chord/);
    expect(() => indexActions(typo("Cmd+k"))).toThrow(/not a chord/);
    expect(() => indexActions(typo("Shift+e"))).not.toThrow();
  });

  it("lets the two modes claim the same key, which is the point of the split", () => {
    const both: Action[] = [
      { id: "a", label: "A", defaultKeys: ["z"], mode: "timeline", group: "view", when: () => true, run: () => {} },
      { id: "b", label: "B", defaultKeys: ["z"], mode: "list", group: "view", when: () => true, run: () => {} },
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
    expect(resolve("z", "list")).toBeUndefined();
    expect(resolve("z", "timeline")).toBeUndefined();
  });

  /**
   * `Escape` and `⌘K` were the two keys the registry could not hold, for opposite reasons:
   * `Escape` was "not an action but the way out of whatever is on top of the list", and
   * `⌘K` needed a modifier a bare key could not carry. Both are ordinary bindings now,
   * which is what lets `?` list them from the same table as `c`.
   */
  it("holds the two keys that used to live outside it", () => {
    expect(resolve("Escape", "list")?.id).toBe("app.back");
    expect(resolve("Mod+k", "list")?.id).toBe("app.palette");
    // And `k` alone is still nobody's: it was `ticket.moveUp` and is now unbound, which is
    // the collision `hint: "Mod+K"` existed to dodge, gone rather than worked around.
    expect(resolve("k", "list")).toBeUndefined();
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

  it("answers ⇧d on the chart and nothing in the list", () => {
    // `"Shift+d"`, where this used to be `"D"` — the `event.key` of the same press. The
    // chord carries the modifier, so `d` and its inverse read as a pair rather than as two
    // letters that happen to differ in case.
    expect(resolve("Shift+d", "timeline")?.id).toBe("timeline.unlink");
    expect(resolve("Shift+d", "list")).toBeUndefined();
    expect(resolve("d", "timeline")?.id).toBe("timeline.link");
    // `d` in the queue is the third meaning of the letter, and the mode is what keeps the
    // three apart: nothing in the shared bucket answers it at all.
    expect(resolve("d", "triage")?.id).toBe("triage.duplicate");
    expect(resolve("d", "list")).toBeUndefined();
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
    expect(selected).toContain("ticket.status.5");
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
    actionById("ticket.status.4").run(ctx);
    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: ticket.id, status: "in_review" });
    expect(ctx.close).toHaveBeenCalled();
  });
});

describe("hintOf, the default a menu prints", () => {
  it("prints the palette's key as the one the reader actually has", () => {
    const palette = actionById("app.palette");
    expect(hintOf(palette, true)).toBe("⌘K");
    expect(hintOf(palette, false)).toBe("Ctrl+K");
  });

  it("prints the first spelling of a chord, for a human", () => {
    expect(hintOf(actionById("ticket.moveDown"), true)).toBe("n");
    expect(hintOf(actionById("app.help"), true)).toBe("?");
    expect(hintOf(actionById("timeline.shrinkEnd"), true)).toBe("⇧h");
    expect(hintOf(actionById("app.back"), true)).toBe("Esc");

    const arrowOnly: Action = {
      id: "test.arrow",
      label: "Arrow",
      defaultKeys: ["ArrowDown"],
      group: "view",
      when: () => true,
      run: () => {},
    };
    expect(hintOf(arrowOnly, true)).toBe("↓");
  });

  it("prints nothing for an action the keyboard cannot reach", () => {
    expect(hintOf(actionById("project.create"), true)).toBeUndefined();
    // The one action that lost a key in §6.4, and the palette entry it kept.
    expect(hintOf(actionById("timeline.schedule"), true)).toBeUndefined();
  });

  /**
   * `hintOf` reads the defaults and `hintFor` reads the reader's own, and the two agree
   * for anybody who has changed nothing — which is what makes the command palette's one
   * remaining call to `hintOf` honest until §6.5 repoints it.
   */
  it("agrees with the effective hint on an unremapped keyboard", () => {
    for (const action of ACTIONS) {
      expect(hintFor(action, DEFAULT_BINDINGS, true)).toBe(hintOf(action, true));
    }
  });
});

describe("shortcutRows", () => {
  const rowsOf = (isMac: boolean) => shortcutRows(DEFAULT_BINDINGS, isMac);

  it("is derived from the actions the keyboard can reach, not written by hand", () => {
    const bound = ACTIONS.filter((action) => (action.defaultKeys ?? []).length > 0);
    const rows = rowsOf(true);

    expect(rows).toHaveLength(bound.length);
    for (const action of bound) {
      expect(rows.some((row) => row.label === action.label)).toBe(true);
    }
  });

  it("carries the palette's row, which the overlay used to draw by hand", () => {
    expect(rowsOf(true).find((row) => row.label === "Command palette")).toEqual({
      mode: undefined,
      keys: "⌘K",
      label: "Command palette",
    });
    expect(rowsOf(false).find((row) => row.label === "Command palette")?.keys).toBe("Ctrl+K");
  });

  /**
   * The `Esc  Close` row `help-overlay.tsx` used to draw by hand under the generated list,
   * because "Escape is not an action but the way out of whatever is on top of the list".
   * It is an action now, so the sheet stops having a hand-written half.
   */
  it("carries Escape from the registry rather than from the markup", () => {
    expect(rowsOf(true).find((row) => row.keys === "Esc")?.label).toBe(
      "Close what is open, then leave",
    );
  });

  it("prints the arrow keys as arrows rather than as DOM key names", () => {
    const rows = rowsOf(true);
    expect(rows.find((row) => row.label === "Move down")?.keys).toBe("n / ↓");
    expect(rows.find((row) => row.label === "Move up")?.keys).toBe("p / ↑");
    expect(rows.find((row) => row.label === "Extend selection down")?.keys).toBe("⇧↓");
  });

  it("carries no row for an action the keyboard cannot reach", () => {
    expect(rowsOf(true).some((row) => row.label === "New project")).toBe(false);
  });

  it("names the mode of every row, so the help overlay can group them", () => {
    const rows = rowsOf(true);
    // Undefined, not "list": the row belongs to both views and the overlay says so.
    expect(rows.find((row) => row.label === "Move down")?.mode).toBeUndefined();
    expect(rows.find((row) => row.label === "Move bar earlier")?.mode).toBe("timeline");
    expect(rows.find((row) => row.label === "Rename ticket")?.mode).toBe("list");
    expect(rows.find((row) => row.label === "Select row")?.mode).toBe("savedView");
    expect(rows.find((row) => row.label === "Mark duplicate")?.mode).toBe("triage");
  });
});

describe("the digits, over a vocabulary the team owns", () => {
  const atTicket = (teamId: string | undefined) =>
    context({
      teams: [core, atelier],
      selected: { ...ticket, id: "row", teamId },
    });

  /**
   * The whole of `KAN-92`. The digit names a *position*, and the key written is whatever
   * that team put there — so one keyboard serves every vocabulary without the registry
   * knowing any of them.
   */
  it("writes the word the ticket's own team put in that position", () => {
    const ctx = atTicket("team-atelier");
    actionById("ticket.status.1").run(ctx);
    actionById("ticket.status.4").run(ctx);

    expect(ctx.patchTicket).toHaveBeenNthCalledWith(1, { id: "row", status: "boite" });
    expect(ctx.patchTicket).toHaveBeenNthCalledWith(2, { id: "row", status: "livre" });
  });

  it("is what it always was for a team that renamed nothing", () => {
    // Not a coincidence and not a compatibility shim: Kanso seeds its six at positions
    // 0–5 in exactly this order, so positional *is* the old behaviour spelled generally.
    const ctx = atTicket("team-core");
    actionById("ticket.status.1").run(ctx);
    actionById("ticket.status.5").run(ctx);
    actionById("ticket.status.6").run(ctx);

    expect(ctx.patchTicket).toHaveBeenNthCalledWith(1, { id: "row", status: "backlog" });
    expect(ctx.patchTicket).toHaveBeenNthCalledWith(2, { id: "row", status: "done" });
    expect(ctx.patchTicket).toHaveBeenNthCalledWith(3, { id: "row", status: "canceled" });
  });

  it("is not offered past the end of a shorter list", () => {
    // Inert rather than refused: `5` on a four-word team is a key that means nothing
    // here, and the server's 400 is not a sentence anybody needed to read.
    const ctx = atTicket("team-atelier");

    expect(ids(ctx)).toContain("ticket.status.4");
    expect(ids(ctx)).not.toContain("ticket.status.5");
    expect(ids(ctx)).not.toContain("ticket.status.6");
  });

  it("answers Kanso's six for a draft, which is what its composer offered", () => {
    const ctx = atTicket(undefined);
    actionById("ticket.status.2").run(ctx);

    expect(ctx.patchTicket).toHaveBeenCalledWith({ id: "row", status: "todo" });
  });

  it("keeps the digits on the same keys", () => {
    expect(resolve("1", "list")?.id).toBe("ticket.status.1");
    expect(resolve("6", "list")?.id).toBe("ticket.status.6");
  });

  /**
   * The seventh word, and every word by name. The digits can only say a position — the
   * shortcuts settings screen lists every action with no ticket in hand, so a label that
   * named a team's word would be a label that cannot be drawn there.
   */
  it("offers a picker for the word itself, claimed by the page that holds the rows", () => {
    expect(resolve("Shift+s", "list")?.id).toBe("ticket.status.pick");

    const ctx = atTicket("team-atelier");
    expect(ids(ctx)).not.toContain("ticket.status.pick");

    claim("ticket.status.pick", vi.fn());
    expect(ids(ctx)).toContain("ticket.status.pick");
    clearClaims();
  });
});
