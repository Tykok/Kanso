import type { Project, Team, Ticket, TicketPriority, TicketStatus } from "../api";
import { claimed, runClaim } from "./claims";
import { creationSeed } from "../creation-seed";
import { optionsFor } from "../statuses";
import type { PatchInput } from "../queries";
import { dayKey, laterBy, today, ZOOMS } from "../timeline-geometry";
import type { Action, ActionContext } from "./types";

const hasSelection = (ctx: ActionContext) => ctx.selected !== undefined;

const onTimeline = (ctx: ActionContext) => ctx.view === "timeline";

/**
 * Whether this scope is one you plan in.
 *
 * The global chart is read-only: it crosses every team, and a drag there is as often a
 * slip as an intention. Planning happens where a scope has been chosen. This is the
 * keyboard half of that rule — a view that refuses the mouse and accepts `h` is not
 * read-only, it is a trap with a discoverability problem.
 */
export const canPlan = (ctx: ActionContext) => ctx.scope.kind !== "all";

/**
 * `when` has already answered this, but the compiler cannot know that a predicate
 * run earlier constrains a field read later. Re-checking here is what keeps
 * `selected` narrowed without a non-null assertion.
 */
const onSelected =
  (run: (ctx: ActionContext, ticket: Ticket) => void) => (ctx: ActionContext) => {
    if (ctx.selected) run(ctx, ctx.selected);
  };

/** Same guard as [onSelected], for the two scopes that name an entity. */
const onTeam = (run: (ctx: ActionContext, id: string) => void) => (ctx: ActionContext) => {
  if (ctx.scope.kind === "team") run(ctx, ctx.scope.id);
};

const onProject = (run: (ctx: ActionContext, id: string) => void) => (ctx: ActionContext) => {
  if (ctx.scope.kind === "project") run(ctx, ctx.scope.id);
};

/**
 * Archive and unarchive are the same action seen from two sides, so both ask the
 * loaded row rather than assume. An entity absent from the cache — archived while
 * "Show archived" is off — offers neither: its state is not known here.
 */
const scopedTeam = (ctx: ActionContext): Team | undefined => {
  if (ctx.scope.kind !== "team") return undefined;
  const { id } = ctx.scope;
  return ctx.teams.find((team) => team.id === id);
};

const scopedProject = (ctx: ActionContext): Project | undefined => {
  if (ctx.scope.kind !== "project") return undefined;
  const { id } = ctx.scope;
  return ctx.projects.find((project) => project.id === id);
};

/**
 * Slides the whole bar. A ticket carrying one bound sends only that bound: giving a
 * milestone a start it never had would undo the shape the API was deliberately given.
 */
const shiftBy = (days: number) =>
  onSelected((ctx, ticket) => {
    const patch: PatchInput = { id: ticket.id };
    if (ticket.start) patch.start = laterBy(ticket.start, days);
    if (ticket.due) patch.due = laterBy(ticket.due, days);
    ctx.patchTicket(patch);
  });

/**
 * Moves the end alone. Clamped at the start rather than allowed to cross it — an
 * inverted bar is a 400 and a dialog nobody asked for, and the keyboard repeats.
 */
const resizeBy = (days: number) =>
  onSelected((ctx, ticket) => {
    if (!ticket.due) return;
    const due = laterBy(ticket.due, days);
    if (ticket.start && dayKey(due) < dayKey(ticket.start)) return;
    ctx.patchTicket({ id: ticket.id, due });
  });

const isScheduled = (ticket: Ticket) => ticket.start !== undefined || ticket.due !== undefined;

/**
 * The predecessors of [successorId] this screen can name.
 *
 * An edge is listed exactly when its other end resolves in `ctx.tickets`, and that one
 * rule is also the scope rule. A predecessor sitting in the unscheduled tray is a row of
 * the tickets query — it has no dates, not no row — so it is named and offered. One the
 * timeline response marked `outOfScope` is outside the current scope, so it is absent
 * from that query too, resolves to nothing, and drops out. The palette lists names, and
 * two edges nothing can name would be two identical rows with different consequences.
 *
 * The equivalence has a bound: `api.tickets` caps at `limit: 200`, while the timeline's
 * own scope cap (`SCOPE_LIMIT` in `TimelineService.kt`) is 2000, so past 200 tickets in
 * scope an edge can be `outOfScope: false` — drawn as a full arrow — and still resolve
 * to nothing here, leaving `D` inert with no listing to explain why.
 *
 * Read by `when` and by the picker in `page.tsx`, which is what keeps an inert key from
 * opening an empty list and a listed row from failing to resolve.
 */
export function predecessorsOf(ctx: ActionContext, successorId: string): Ticket[] {
  return ctx.dependencies
    .filter((edge) => edge.successorId === successorId)
    .flatMap((edge) => ctx.tickets.find((row) => row.id === edge.predecessorId) ?? []);
}

/** Steps along [ZOOMS] and stops at the ends: a zoom that wraps is a lost place. */
const zoomBy = (delta: number) => (ctx: ActionContext) => {
  const index = ZOOMS.indexOf(ctx.zoom) + delta;
  ctx.setZoom(ZOOMS[Math.min(Math.max(index, 0), ZOOMS.length - 1)]);
};

/**
 * The five priorities, in the order they are offered.
 *
 * Named here rather than in the two surfaces that list them — the priority pill's menu and
 * the palette `⇧p` opens — because the order is a fact about the vocabulary and a second
 * copy would be a second answer to "which comes first". `STATUS_ACTIONS` stays in
 * `pills.tsx`: `1`–`6` already pin that order in the registry itself.
 */
export const PRIORITY_ACTIONS: readonly string[] = [
  "ticket.priority.none",
  "ticket.priority.low",
  "ticket.priority.medium",
  "ticket.priority.high",
  "ticket.priority.urgent",
];

/**
 * The [position]-th status of the selected ticket's own team, or `undefined` when it has
 * none there — one-based, because the keys a reader presses are.
 *
 * Its own team's and not the scope's, the same reading every other status question makes:
 * `optionsFor` answers the team's catalogue in `team_statuses.position` order, and Kanso's
 * six for a draft with no team to ask — which is what that draft's composer offered it.
 */
function statusAt(ctx: ActionContext, position: number): TicketStatus | undefined {
  if (!ctx.selected) return undefined;
  return optionsFor(ctx.teams, ctx.selected.teamId)[position - 1]?.key;
}

export const coreActions: readonly Action[] = [
  {
    id: "ticket.create",
    writes: true,
    label: "New ticket",
    defaultKeys: ["c"],
    group: "ticket",
    when: () => true,
    run: (ctx) => ctx.open("composer"),
  },
  {
    id: "ticket.open",
    label: "Open ticket",
    defaultKeys: ["Enter"],
    group: "ticket",
    when: hasSelection,
    run: (ctx) => ctx.open("detail"),
  },
  /**
   * `⇧↵`, which could not be an action until a chord could hold a modifier.
   *
   * `event.key` for Shift+Enter is `"Enter"` — the same string `ticket.open` dispatches on
   * — so the old registry had no way to tell the two apart and `app/page.tsx` read the
   * modifier in its own handler. That handler is gone; this is where it went.
   *
   * Claimed rather than run from the context, because opening a ticket's own page needs
   * the router and `ActionContext` deliberately has none — see `./claims.ts`. The page
   * that draws the rows is the page that can navigate to one.
   */
  {
    id: "ticket.openInPage",
    label: "Open ticket in its own page",
    defaultKeys: ["Shift+Enter"],
    group: "ticket",
    when: (ctx) => hasSelection(ctx) && claimed("ticket.openInPage"),
    run: () => runClaim("ticket.openInPage"),
  },
  // The one existing action the split had to claim: renaming edits a row of the list
  // in place, and the chart has no row to edit. Left shared, `e` on the timeline would
  // arm an editor nothing renders — and the page stops answering keys while one is
  // armed, so the keyboard would go dead until Escape.
  {
    id: "ticket.rename",
    writes: true,
    label: "Rename ticket",
    defaultKeys: ["e"],
    mode: "list",
    group: "ticket",
    when: (ctx) => hasSelection(ctx) && ctx.view === "list",
    run: onSelected((ctx, ticket) => ctx.startRename(ticket.id)),
  },
  {
    id: "ticket.archive",
    writes: true,
    label: "Archive / unarchive ticket",
    defaultKeys: ["x"],
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => ctx.patchTicket({ id: ticket.id, archived: !ticket.archived })),
  },
  {
    id: "ticket.delete",
    writes: true,
    label: "Delete ticket",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => ctx.deleteTicket(ticket.id)),
  },
  {
    id: "ticket.moveDown",
    label: "Move down",
    defaultKeys: ["n", "ArrowDown"],
    group: "ticket",
    when: (ctx) => ctx.tickets.length > 0,
    run: (ctx) => ctx.move(1),
  },
  {
    id: "ticket.moveUp",
    label: "Move up",
    defaultKeys: ["p", "ArrowUp"],
    group: "ticket",
    when: (ctx) => ctx.tickets.length > 0,
    run: (ctx) => ctx.move(-1),
  },

  /**
   * The digits name a *position*, not a word — `KAN-92`.
   *
   * They were six actions each carrying a literal: `1` wrote `"backlog"`, `6` wrote
   * `"canceled"`. `KAN-90` made a status the team's to invent, and left these alone: a
   * team that named its first column `Boîte` pressed `1` and got the server's refusal,
   * and a team with a seventh word had no key for it at all.
   *
   * A position is the one thing a static registry can say about a vocabulary it cannot
   * see. It is also not a compromise: Kanso seeds its six at positions 0–5 in exactly the
   * order these keys had, so for every team that has renamed nothing this is the old
   * behaviour spelled generally, key for key.
   *
   * The label can only say the position too, and that is a constraint rather than a
   * choice: the shortcuts settings screen lists every action in the registry with no
   * ticket and no team in hand, so a label naming a team's word would be a label that
   * screen cannot draw. `ticket.status.pick` below is where the words themselves live.
   */
  {
    id: "ticket.status.1",
    writes: true,
    label: "Set status: the team's 1st",
    defaultKeys: ["1"],
    group: "ticket",
    when: (ctx) => statusAt(ctx, 1) !== undefined,
    run: onSelected((ctx, ticket) => {
      const status = statusAt(ctx, 1);
      if (!status) return;
      ctx.patchTicket({ id: ticket.id, status });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.2",
    writes: true,
    label: "Set status: the team's 2nd",
    defaultKeys: ["2"],
    group: "ticket",
    when: (ctx) => statusAt(ctx, 2) !== undefined,
    run: onSelected((ctx, ticket) => {
      const status = statusAt(ctx, 2);
      if (!status) return;
      ctx.patchTicket({ id: ticket.id, status });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.3",
    writes: true,
    label: "Set status: the team's 3rd",
    defaultKeys: ["3"],
    group: "ticket",
    when: (ctx) => statusAt(ctx, 3) !== undefined,
    run: onSelected((ctx, ticket) => {
      const status = statusAt(ctx, 3);
      if (!status) return;
      ctx.patchTicket({ id: ticket.id, status });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.4",
    writes: true,
    label: "Set status: the team's 4th",
    defaultKeys: ["4"],
    group: "ticket",
    when: (ctx) => statusAt(ctx, 4) !== undefined,
    run: onSelected((ctx, ticket) => {
      const status = statusAt(ctx, 4);
      if (!status) return;
      ctx.patchTicket({ id: ticket.id, status });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.5",
    writes: true,
    label: "Set status: the team's 5th",
    defaultKeys: ["5"],
    group: "ticket",
    when: (ctx) => statusAt(ctx, 5) !== undefined,
    run: onSelected((ctx, ticket) => {
      const status = statusAt(ctx, 5);
      if (!status) return;
      ctx.patchTicket({ id: ticket.id, status });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.6",
    writes: true,
    label: "Set status: the team's 6th",
    defaultKeys: ["6"],
    group: "ticket",
    when: (ctx) => statusAt(ctx, 6) !== undefined,
    run: onSelected((ctx, ticket) => {
      const status = statusAt(ctx, 6);
      if (!status) return;
      ctx.patchTicket({ id: ticket.id, status });
      ctx.close();
    }),
  },

  /**
   * One key for the word itself — the seventh status, and every status by name.
   *
   * The same trick as `⇧p` under it, for a reason that is stronger here: priorities are
   * five values the registry knows, and a status is a word only the ticket's team can
   * name. The rows are built by the page from `statuses.optionsFor`, so nothing in here
   * has to know a vocabulary, and a team that adds an eighth word gets an eighth row
   * without a line changing.
   *
   * Claimed, for the reason the priority picker gives: the rows are the page's to
   * assemble, and the shell has no business listing statuses for a ticket it does not
   * hold.
   */
  {
    id: "ticket.status.pick",
    writes: true,
    label: "Set status…",
    defaultKeys: ["Shift+s"],
    group: "ticket",
    when: (ctx) => hasSelection(ctx) && claimed("ticket.status.pick"),
    run: () => runClaim("ticket.status.pick"),
  },

  /**
   * One key for five priorities, through the palette.
   *
   * The five below have no keys and are not going to get any: `1`–`6` are already the
   * status vocabulary, and a second run of digits over the same rows would be the kind of
   * thing a reader has to look up every time. So `⇧p` asks *which*, in the list the app
   * already has — the same trick `startLink` uses for a predecessor, and for the same
   * reason its comment gives: nothing in this interface is modal, and one gesture is not
   * worth teaching a second way of being in a state.
   *
   * Claimed, because the palette's rows are published by the page (`PageShell.commands`)
   * and the shell has no business assembling a list about a ticket it does not hold.
   */
  {
    id: "ticket.priority.pick",
    writes: true,
    label: "Set priority…",
    defaultKeys: ["Shift+p"],
    group: "ticket",
    when: (ctx) => hasSelection(ctx) && claimed("ticket.priority.pick"),
    run: () => runClaim("ticket.priority.pick"),
  },

  {
    id: "ticket.priority.none",
    writes: true,
    label: "Set priority: None",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "none" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.low",
    writes: true,
    label: "Set priority: Low",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "low" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.medium",
    writes: true,
    label: "Set priority: Medium",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "medium" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.high",
    writes: true,
    label: "Set priority: High",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "high" satisfies TicketPriority });
      ctx.close();
    }),
  },
  {
    id: "ticket.priority.urgent",
    writes: true,
    label: "Set priority: Urgent",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, priority: "urgent" satisfies TicketPriority });
      ctx.close();
    }),
  },

  {
    id: "team.create",
    writes: true,
    label: "New team",
    group: "team",
    when: (ctx) => ctx.canConfigure,
    run: (ctx) =>
      ctx.openDialog({
        kind: "team",
        parentTeamId: creationSeed(ctx.scope, ctx.teams, ctx.projects).team.parentTeamId,
      }),
  },
  {
    id: "team.rename",
    writes: true,
    label: "Rename team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "team", id })),
  },
  {
    id: "team.archive",
    writes: true,
    label: "Archive team",
    group: "team",
    when: (ctx) => ctx.canConfigure && scopedTeam(ctx)?.archived === false,
    run: onTeam((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "team", id },
        severity: "archive",
      }),
    ),
  },
  {
    id: "team.unarchive",
    writes: true,
    label: "Unarchive team",
    group: "team",
    when: (ctx) => ctx.canConfigure && scopedTeam(ctx)?.archived === true,
    run: onTeam((ctx, id) => ctx.unarchive({ kind: "team", id })),
  },
  {
    id: "team.delete",
    writes: true,
    label: "Delete team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "team", id },
        severity: "delete",
      }),
    ),
  },

  {
    id: "project.create",
    writes: true,
    label: "New project",
    group: "project",
    when: () => true,
    run: (ctx) =>
      ctx.openDialog({
        kind: "project",
        teamId: creationSeed(ctx.scope, ctx.teams, ctx.projects).project.teamId,
      }),
  },
  {
    id: "project.edit",
    writes: true,
    label: "Edit project",
    group: "project",
    when: (ctx) => ctx.scope.kind === "project",
    run: onProject((ctx, id) => ctx.openDialog({ kind: "project", id })),
  },
  {
    id: "project.archive",
    writes: true,
    label: "Archive project",
    group: "project",
    when: (ctx) => ctx.canConfigure && scopedProject(ctx)?.archived === false,
    run: onProject((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "project", id },
        severity: "archive",
      }),
    ),
  },
  {
    id: "project.unarchive",
    writes: true,
    label: "Unarchive project",
    group: "project",
    when: (ctx) => ctx.canConfigure && scopedProject(ctx)?.archived === true,
    run: onProject((ctx, id) => ctx.unarchive({ kind: "project", id })),
  },
  {
    id: "project.delete",
    writes: true,
    label: "Delete project",
    group: "project",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "project",
    run: onProject((ctx, id) =>
      ctx.openDialog({
        kind: "disposition",
        target: { kind: "project", id },
        severity: "delete",
      }),
    ),
  },

  {
    id: "view.all",
    label: "View: all tickets",
    group: "view",
    when: (ctx) => ctx.scope.kind !== "all",
    run: (ctx) => {
      ctx.setScope({ kind: "all" });
      ctx.close();
    },
  },
  {
    id: "view.filter",
    label: "Filter tickets",
    defaultKeys: ["/"],
    group: "view",
    when: () => true,
    run: (ctx) => {
      ctx.close();
      ctx.focusFilter();
    },
  },
  /**
   * List → board → timeline → list, on one chord.
   *
   * The three drawings had no key at all: the only way between them was the segmented
   * control in the top bar. One key that cycles rather than three that each land
   * somewhere, because there are exactly three and a reader pressing it twice has learned
   * the whole control — where `Mod+1/2/3` would spend three chords on a choice nobody
   * makes from memory.
   *
   * **`Mod+v` deliberately shadows paste**, and this is the note asking the next reader
   * not to "fix" it. Over a list of rows paste did nothing at all; inside every input,
   * textarea and document the typing guard stands the dispatcher down, so pasting still
   * works everywhere pasting means something. It is the only chord in the set that
   * overlays a system reflex, it is argued in §11 of the design, and bindings are data —
   * so it is one line here and one click in Settings if it proves wrong in use.
   *
   * Claimed: only the route that draws all three may cycle them, and `ctx.view` is a store
   * value every route shares — cycling it from `/trash` would set a value nothing draws.
   */
  {
    id: "view.cycleDrawing",
    label: "Next drawing: list, board, timeline",
    defaultKeys: ["Mod+v"],
    group: "view",
    when: () => claimed("view.cycleDrawing"),
    run: () => runClaim("view.cycleDrawing"),
  },

  /*
   * The chart's own keys. All `mode: "timeline"`, so none of them resolves in the
   * list, and each `when` repeats the view because `availableActions` — what the
   * palette and the menus read — knows nothing about modes.
   */
  {
    id: "timeline.shiftEarlier",
    writes: true,
    label: "Move bar earlier",
    defaultKeys: ["h"],
    mode: "timeline",
    group: "ticket",
    when: (ctx) =>
      canPlan(ctx) && onTimeline(ctx) && ctx.selected !== undefined && isScheduled(ctx.selected),
    run: shiftBy(-1),
  },
  {
    id: "timeline.shiftLater",
    writes: true,
    label: "Move bar later",
    defaultKeys: ["l"],
    mode: "timeline",
    group: "ticket",
    when: (ctx) =>
      canPlan(ctx) && onTimeline(ctx) && ctx.selected !== undefined && isScheduled(ctx.selected),
    run: shiftBy(1),
  },
  {
    id: "timeline.shrinkEnd",
    writes: true,
    label: "Pull the end in",
    defaultKeys: ["Shift+h"],
    mode: "timeline",
    group: "ticket",
    when: (ctx) => canPlan(ctx) && onTimeline(ctx) && ctx.selected?.due !== undefined,
    run: resizeBy(-1),
  },
  {
    id: "timeline.growEnd",
    writes: true,
    label: "Push the end out",
    defaultKeys: ["Shift+l"],
    mode: "timeline",
    group: "ticket",
    when: (ctx) => canPlan(ctx) && onTimeline(ctx) && ctx.selected?.due !== undefined,
    run: resizeBy(1),
  },
  {
    id: "timeline.schedule",
    writes: true,
    label: "Schedule this ticket",
    /*
     * No key, on the maintainer's ruling (§6.4). It is the one action in the registry that
     * gets quieter in this pass, and it had to lose something: `p` is now "previous" in
     * every drawing, and scheduling a bar is a once-per-ticket gesture where moving the
     * cursor is a once-per-row one. Both its other doors — the palette and the row menu —
     * are untouched, which is the difference between quieter and gone.
     */
    mode: "timeline",
    group: "ticket",
    when: (ctx) =>
      canPlan(ctx) && onTimeline(ctx) && ctx.selected !== undefined && !isScheduled(ctx.selected),
    // A one-day milestone on today, the same default the tray drop takes: any other
    // length would be a guess presented as a plan.
    run: onSelected((ctx, ticket) => {
      const day = today();
      ctx.patchTicket({ id: ticket.id, start: day, due: day });
    }),
  },
  {
    id: "timeline.unschedule",
    writes: true,
    label: "Send back to the tray",
    defaultKeys: ["u"],
    mode: "timeline",
    group: "ticket",
    when: (ctx) =>
      canPlan(ctx) && onTimeline(ctx) && ctx.selected !== undefined && isScheduled(ctx.selected),
    // `unset`, not two nulls: an explicit null reads as "leave unchanged" on the wire.
    run: onSelected((ctx, ticket) => ctx.patchTicket({ id: ticket.id, unset: ["start", "due"] })),
  },
  {
    id: "timeline.zoomOut",
    label: "Zoom out",
    defaultKeys: ["["],
    mode: "timeline",
    group: "view",
    when: onTimeline,
    run: zoomBy(1),
  },
  {
    id: "timeline.zoomIn",
    label: "Zoom in",
    defaultKeys: ["]"],
    mode: "timeline",
    group: "view",
    when: onTimeline,
    run: zoomBy(-1),
  },
  {
    id: "timeline.today",
    label: "Recentre on today",
    defaultKeys: ["t"],
    mode: "timeline",
    group: "view",
    // No selection needed: finding today again is about the viewport, not a ticket.
    when: onTimeline,
    run: (ctx) => ctx.recentre(),
  },
  {
    id: "timeline.link",
    writes: true,
    label: "Add a dependency",
    defaultKeys: ["d"],
    mode: "timeline",
    group: "ticket",
    when: (ctx) => canPlan(ctx) && onTimeline(ctx) && hasSelection(ctx) && ctx.tickets.length > 1,
    run: onSelected((ctx, ticket) => ctx.startLink(ticket.id)),
  },
  {
    id: "timeline.unlink",
    writes: true,
    label: "Remove a dependency",
    // One keystroke apart from `d`, and now spelled as one: `"Shift+d"` where this used to
    // be `"D"` — the `event.key` of the same press. See `./chords.ts` on why the case of a
    // letter is no longer where a modifier hides.
    defaultKeys: ["Shift+d"],
    mode: "timeline",
    group: "ticket",
    // It always opens the picker, even with a single predecessor: one key doing two
    // things depending on the shape of the graph would make the fast path the
    // destructive one.
    when: (ctx) =>
      canPlan(ctx) &&
      onTimeline(ctx) &&
      ctx.selected !== undefined &&
      predecessorsOf(ctx, ctx.selected.id).length > 0,
    run: onSelected((ctx, ticket) => ctx.startUnlink(ticket.id)),
  },

  {
    id: "app.palette",
    label: "Command palette",
    /*
     * A binding like any other now. It was `hint: "Mod+K"` — printed, never dispatched —
     * and two files intercepted `⌘K` ahead of the registry, because a bare
     * `KeyboardEvent.key` could not hold a modifier and `k` alone was `ticket.moveUp`.
     * With chords there is one entry, one dispatcher and nothing to keep in step.
     */
    defaultKeys: ["Mod+k"],
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("palette"),
  },
  {
    id: "app.settings",
    label: "Settings",
    defaultKeys: [","],
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("settings"),
  },
  {
    id: "app.help",
    label: "Keyboard shortcuts",
    defaultKeys: ["?"],
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("help"),
  },
  /**
   * Close what is open, then leave. One gesture with two meanings, in the order a reader
   * expects — and one implementation for the two ways in, because `topbar.tsx` draws a `×`
   * that runs the same thing.
   *
   * The one action the shell runs itself rather than through `run`. Leaving a page needs
   * the router, the overlay state and the page's own claim on `Escape`
   * (`PageShell.onEscape`, which is how a saved view drops a selection *between* "close"
   * and "leave") — three things `ActionContext` does not carry and should not, since
   * thirty-odd other actions would then be able to reach a router that means nothing to
   * them. `use-shell-keys.ts` reads this action's binding and calls its own `leave`.
   *
   * It is in the registry all the same, and that is the point: `?` lists `Escape` from the
   * same table as every other key instead of drawing it by hand, and §6.5 can rebind it.
   * `run` is empty because nobody dispatches through it — the comment is the contract.
   */
  {
    id: "app.back",
    label: "Close what is open, then leave",
    defaultKeys: ["Escape"],
    group: "app",
    when: () => true,
    run: () => {},
  },
  {
    id: "app.logout",
    label: "Sign out",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.logout(),
  },
];
