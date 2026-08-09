import type { Dialog, Overlay, Scope } from "@/store/ui";
import type { Project, Team, Ticket, TicketPriority, TicketStatus } from "./api";

export type ActionGroup = "ticket" | "team" | "project" | "view" | "app";

export type ActionContext = {
  scope: Scope;
  teams: Team[];
  projects: Project[];
  tickets: Ticket[];
  selected?: Ticket;
  canConfigure: boolean;

  open: (overlay: Overlay) => void;
  close: () => void;
  openDialog: (dialog: Dialog) => void;
  setScope: (scope: Scope) => void;
  move: (delta: number) => void;
  focusFilter: () => void;
  startRename: (id: string) => void;
  patchTicket: (input: { id: string } & Record<string, unknown>) => void;
  deleteTicket: (id: string) => void;
  unarchive: (target: { kind: "team" | "project"; id: string }) => void;
  logout: () => void;
};

export type Action = {
  id: string;
  label: string;
  /**
   * Space-separated `KeyboardEvent.key` values, so one action can own the two
   * spellings of the same intent (`j` and `ArrowDown`) without a second field
   * that could disagree with this one.
   */
  shortcut?: string;
  group: ActionGroup;
  when: (ctx: ActionContext) => boolean;
  run: (ctx: ActionContext) => void;
};

const hasSelection = (ctx: ActionContext) => ctx.selected !== undefined;

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

export const ACTIONS: readonly Action[] = [
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
  {
    id: "ticket.rename",
    label: "Rename ticket",
    shortcut: "e",
    group: "ticket",
    when: hasSelection,
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

  // `patchTicket` takes `Record<string, unknown>`, so `satisfies` is the only thing
  // standing between a typo in a wire value and a 400 at runtime.
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
    run: (ctx) => ctx.openDialog({ kind: "team" }),
  },
  {
    id: "team.createChild",
    label: "New sub-team",
    group: "team",
    when: (ctx) => ctx.canConfigure && ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "team", parentTeamId: id })),
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
    run: (ctx) => ctx.openDialog({ kind: "project" }),
  },
  {
    id: "project.createInTeam",
    label: "New project in this team",
    group: "project",
    when: (ctx) => ctx.scope.kind === "team",
    run: onTeam((ctx, id) => ctx.openDialog({ kind: "project", teamId: id })),
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
    when: (ctx) => scopedProject(ctx)?.archived === false,
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
    when: (ctx) => scopedProject(ctx)?.archived === true,
    run: onProject((ctx, id) => ctx.unarchive({ kind: "project", id })),
  },
  {
    id: "project.delete",
    label: "Delete project",
    group: "project",
    when: (ctx) => ctx.scope.kind === "project",
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

  {
    id: "app.palette",
    label: "Command palette",
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

const BY_ID = new Map<string, Action>();
const BY_KEY = new Map<string, Action>();

for (const action of ACTIONS) {
  if (BY_ID.has(action.id)) throw new Error(`Duplicate action id "${action.id}"`);
  BY_ID.set(action.id, action);

  for (const key of action.shortcut?.split(" ") ?? []) {
    const claimed = BY_KEY.get(key);
    if (claimed) {
      throw new Error(`Key "${key}" is claimed by both "${claimed.id}" and "${action.id}"`);
    }
    BY_KEY.set(key, action);
  }
}

/** The action a bare keypress means, before `when` is consulted. */
export function resolveShortcut(key: string): Action | undefined {
  return BY_KEY.get(key);
}

/** Everything currently permitted — what the palette lists and menus filter. */
export function availableActions(ctx: ActionContext): Action[] {
  return ACTIONS.filter((action) => action.when(ctx));
}

/** Throws on an unknown id: a menu referencing a dead action is a bug, not a no-op. */
export function actionById(id: string): Action {
  const action = BY_ID.get(id);
  if (!action) throw new Error(`Unknown action "${id}"`);
  return action;
}

const KEY_LABELS: Record<string, string> = {
  ArrowDown: "↓",
  ArrowUp: "↑",
};

/** Rows for the help overlay, generated from the shortcuts. */
export function shortcutRows(): { keys: string; label: string }[] {
  return ACTIONS.flatMap((action) =>
    action.shortcut === undefined
      ? []
      : [
          {
            keys: action.shortcut
              .split(" ")
              .map((key) => KEY_LABELS[key] ?? key)
              .join(" / "),
            label: action.label,
          },
        ],
  );
}
