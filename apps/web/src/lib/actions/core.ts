import type {
  Project,
  Team,
  Ticket,
  TicketPriority,
  TicketStatus,
} from "../api";
import { creationSeed } from "../creation-seed";
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

export const coreActions: readonly Action[] = [
  {
    id: "ticket.create",
    label: "New ticket",
    shortcut: "c",
    group: "ticket",
    when: () => true,
    run: (ctx) => ctx.open("composer"),
  },
  {
    id: "ticket.open",
    label: "Open ticket",
    shortcut: "Enter",
    group: "ticket",
    when: hasSelection,
    run: (ctx) => ctx.open("detail"),
  },
  // The one existing action the split had to claim: renaming edits a row of the list
  // in place, and the chart has no row to edit. Left shared, `e` on the timeline would
  // arm an editor nothing renders — and the page stops answering keys while one is
  // armed, so the keyboard would go dead until Escape.
  {
    id: "ticket.rename",
    label: "Rename ticket",
    shortcut: "e",
    mode: "list",
    group: "ticket",
    when: (ctx) => hasSelection(ctx) && ctx.view === "list",
    run: onSelected((ctx, ticket) => ctx.startRename(ticket.id)),
  },
  {
    id: "ticket.archive",
    label: "Archive / unarchive ticket",
    shortcut: "x",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => ctx.patchTicket({ id: ticket.id, archived: !ticket.archived })),
  },
  {
    id: "ticket.delete",
    label: "Delete ticket",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => ctx.deleteTicket(ticket.id)),
  },
  {
    id: "ticket.moveDown",
    label: "Move down",
    shortcut: "j ArrowDown",
    group: "ticket",
    when: (ctx) => ctx.tickets.length > 0,
    run: (ctx) => ctx.move(1),
  },
  {
    id: "ticket.moveUp",
    label: "Move up",
    shortcut: "k ArrowUp",
    group: "ticket",
    when: (ctx) => ctx.tickets.length > 0,
    run: (ctx) => ctx.move(-1),
  },

  // `PatchInput` now checks the field itself; `satisfies` is kept for the value, which
  // is a string the wire cares about and the field type alone would not spell out.
  {
    id: "ticket.status.backlog",
    label: "Set status: Backlog",
    shortcut: "1",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "backlog" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.todo",
    label: "Set status: Todo",
    shortcut: "2",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "todo" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.in_progress",
    label: "Set status: In progress",
    shortcut: "3",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "in_progress" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.in_review",
    label: "Set status: In review",
    shortcut: "4",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "in_review" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.done",
    label: "Set status: Done",
    shortcut: "5",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "done" satisfies TicketStatus });
      ctx.close();
    }),
  },
  {
    id: "ticket.status.canceled",
    label: "Set status: Canceled",
    shortcut: "6",
    group: "ticket",
    when: hasSelection,
    run: onSelected((ctx, ticket) => {
      ctx.patchTicket({ id: ticket.id, status: "canceled" satisfies TicketStatus });
      ctx.close();
    }),
  },

  {
    id: "ticket.priority.none",
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
    label: "Rename team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "team", id })),
  },
  {
    id: "team.archive",
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
    label: "Unarchive team",
    group: "team",
    when: (ctx) => ctx.canConfigure && scopedTeam(ctx)?.archived === true,
    run: onTeam((ctx, id) => ctx.unarchive({ kind: "team", id })),
  },
  {
    id: "team.delete",
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
    label: "Edit project",
    group: "project",
    when: (ctx) => ctx.scope.kind === "project",
    run: onProject((ctx, id) => ctx.openDialog({ kind: "project", id })),
  },
  {
    id: "project.archive",
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
    label: "Unarchive project",
    group: "project",
    when: (ctx) => ctx.canConfigure && scopedProject(ctx)?.archived === true,
    run: onProject((ctx, id) => ctx.unarchive({ kind: "project", id })),
  },
  {
    id: "project.delete",
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
    shortcut: "/",
    group: "view",
    when: () => true,
    run: (ctx) => {
      ctx.close();
      ctx.focusFilter();
    },
  },

  /*
   * The chart's own keys. All `mode: "timeline"`, so none of them resolves in the
   * list, and each `when` repeats the view because `availableActions` — what the
   * palette and the menus read — knows nothing about modes.
   */
  {
    id: "timeline.shiftEarlier",
    label: "Move bar earlier",
    shortcut: "h",
    mode: "timeline",
    group: "ticket",
    when: (ctx) =>
      canPlan(ctx) && onTimeline(ctx) && ctx.selected !== undefined && isScheduled(ctx.selected),
    run: shiftBy(-1),
  },
  {
    id: "timeline.shiftLater",
    label: "Move bar later",
    shortcut: "l",
    mode: "timeline",
    group: "ticket",
    when: (ctx) =>
      canPlan(ctx) && onTimeline(ctx) && ctx.selected !== undefined && isScheduled(ctx.selected),
    run: shiftBy(1),
  },
  {
    id: "timeline.shrinkEnd",
    label: "Pull the end in",
    shortcut: "H",
    mode: "timeline",
    group: "ticket",
    when: (ctx) => canPlan(ctx) && onTimeline(ctx) && ctx.selected?.due !== undefined,
    run: resizeBy(-1),
  },
  {
    id: "timeline.growEnd",
    label: "Push the end out",
    shortcut: "L",
    mode: "timeline",
    group: "ticket",
    when: (ctx) => canPlan(ctx) && onTimeline(ctx) && ctx.selected?.due !== undefined,
    run: resizeBy(1),
  },
  {
    id: "timeline.schedule",
    label: "Schedule this ticket",
    shortcut: "p",
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
    label: "Send back to the tray",
    shortcut: "u",
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
    shortcut: "[",
    mode: "timeline",
    group: "view",
    when: onTimeline,
    run: zoomBy(1),
  },
  {
    id: "timeline.zoomIn",
    label: "Zoom in",
    shortcut: "]",
    mode: "timeline",
    group: "view",
    when: onTimeline,
    run: zoomBy(-1),
  },
  {
    id: "timeline.today",
    label: "Recentre on today",
    shortcut: "t",
    mode: "timeline",
    group: "view",
    // No selection needed: finding today again is about the viewport, not a ticket.
    when: onTimeline,
    run: (ctx) => ctx.recentre(),
  },
  {
    id: "timeline.link",
    label: "Add a dependency",
    shortcut: "d",
    mode: "timeline",
    group: "ticket",
    when: (ctx) => canPlan(ctx) && onTimeline(ctx) && hasSelection(ctx) && ctx.tickets.length > 1,
    run: onSelected((ctx, ticket) => ctx.startLink(ticket.id)),
  },
  {
    id: "timeline.unlink",
    label: "Remove a dependency",
    // `D`, the `event.key` of Shift+d, so `d` and its inverse are one keystroke apart.
    shortcut: "D",
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
    // No `shortcut`: ⌘K is intercepted in `page.tsx` ahead of the registry, and `k` here
    // would collide with `ticket.moveUp`. `hint` is display only, which is what lets the
    // menus print a key the registry does not dispatch.
    hint: "Mod+K",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("palette"),
  },
  {
    id: "app.settings",
    label: "Settings",
    shortcut: ",",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("settings"),
  },
  {
    id: "app.help",
    label: "Keyboard shortcuts",
    shortcut: "?",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.open("help"),
  },
  {
    id: "app.logout",
    label: "Sign out",
    group: "app",
    when: () => true,
    run: (ctx) => ctx.logout(),
  },
];
