// Type-only, so nothing of the store reaches the runtime bundle: the tickets
// endpoint is shaped by the scope, and restating that union here would let the
// two drift.
import type { Scope } from "@/store/ui";

/**
 * The prefix every request is built on, and empty on purpose.
 *
 * Next inlines `NEXT_PUBLIC_*` at build time, so a default of `http://localhost:8080`
 * welds one deployment's API origin into every published image — the one thing a single
 * image distributed to strangers cannot carry. Empty makes `${API_URL}${path}` a relative
 * path, which the browser resolves against the page it came from, and the proxy in front
 * of both halves decides whether `/api/...` is the API's or the app's.
 *
 * `apps/web/.env.development` still sets it for `next dev`, where the two halves really
 * are on two ports. Next loads that file for `dev` and not for `build`, so nothing it
 * says can reach a published bundle.
 */
export const API_URL = process.env.NEXT_PUBLIC_API_URL ?? "";

/**
 * The API's absolute origin, for URLs that are shown, pasted, or parsed.
 *
 * A function and not a constant because of the second branch: `next build` prerenders
 * client components on the server, so a `window` read at module scope is a build failure
 * rather than a runtime one. Deferring it to call time is what lets the four module-level
 * constants that used to hold `${API_URL}/...` move inside a component body.
 *
 * The empty string it returns there is honest — a prerender has no origin to name — and
 * `useApiOrigin` is what stops that empty pass from reaching the DOM as a hydration
 * mismatch. Callers that only ever run in a browser (a click handler, a WebSocket dial)
 * use this directly.
 */
export const apiOrigin = (): string =>
  API_URL || (typeof window === "undefined" ? "" : window.location.origin);

/**
 * The six a team is *seeded* with — and no longer "the statuses" — `KAN-90`.
 *
 * Renamed from `DEFAULT_STATUSES`, and the rename is the point, exactly as
 * `DefaultStatus` is on the server. A team defines its own list now: it may add a
 * seventh word and remove one of these, so a screen that read this constant as the
 * vocabulary would draw a column nobody has and miss the one they do. `lib/statuses.ts`
 * resolves the real list from `Team.statuses`.
 *
 * What is still true of these six, and what this constant is for: they are what a *draft*
 * can hold — a ticket with no team has no catalogue to read, so its vocabulary is this —
 * and they are what the composer offers before a team has been chosen.
 */
export const DEFAULT_STATUSES = [
  "backlog",
  "todo",
  "in_progress",
  "in_review",
  "done",
  "canceled",
] as const;

/** One of the six, for the two places that genuinely mean only those. */
export type DefaultStatus = (typeof DEFAULT_STATUSES)[number];

/**
 * What a status *means*, as opposed to what it is called — `dev.kanso.domain.StatusCategory`.
 *
 * Here, with the wire types, since `KAN-28`: `lib/status.ts` held it and said "nothing
 * crosses the wire", which was true while the five were a reading this client did of a
 * closed status column. The server sends them now — on every `Team.statuses` row, and as
 * the bucket keys of a list whose scope spans teams — so the vocabulary belongs beside the
 * others it arrives with. `lib/status.ts` re-exports it for the screens that were already
 * asking it questions.
 */
export const STATUS_CATEGORIES = ["backlog", "unstarted", "started", "completed", "canceled"] as const;
export type StatusCategory = (typeof STATUS_CATEGORIES)[number];

export const TICKET_PRIORITIES = ["none", "low", "medium", "high", "urgent"] as const;

/**
 * `ProjectStatus`, server side (`domain/Model.kt`). Here rather than in the project
 * dialog, which used to hold it: the Notion import's columns step offers the same choice
 * when it maps a base of projects, and two copies of a closed vocabulary are two things
 * to keep in step with one enum.
 */
export const PROJECT_STATUSES = ["planned", "in_progress", "paused", "completed", "canceled"] as const;

/**
 * `ProjectHealth`, server side. Whether a project will land — a different question from
 * `PROJECT_STATUSES`, which says where its work is, and never derived from it: a project
 * can be `in_progress` and `off_track` at the same time, and that pair is the single most
 * useful thing this vocabulary can say.
 *
 * Three values and no fourth. A project nobody has assessed has `health: undefined`, which
 * is **not** `on_track` — "nobody has said" and "somebody said it is fine" are different
 * facts, and a client that draws the first as the second turns every project green on the
 * day the feature ships. Absence is drawn as absence everywhere below.
 */
export const PROJECT_HEALTHS = ["on_track", "at_risk", "off_track"] as const;

/**
 * The effort scale, and the whole of it: a truncated Fibonacci sequence the server
 * refuses anything outside of, in Kotlin and again by `tickets_estimate_chk`. Restated
 * here rather than fetched because it is a vocabulary, not data — the same reason
 * `DEFAULT_STATUSES` is a literal — and because a `<select>` has to be built from it
 * before any ticket has been loaded.
 *
 * Absent is not zero anywhere in this app: `estimate` is `undefined` for a ticket nobody
 * has sized, cleared by naming it in `unset`, and left out of every sum.
 */
export const EFFORT_POINTS = [1, 2, 3, 5, 8, 13] as const;
export type EffortPoints = (typeof EFFORT_POINTS)[number];

/**
 * A status key — a `string` since `KAN-90`, not a union of six.
 *
 * The union had to widen for the same reason the Kotlin enum did: a team's seventh word
 * is a value no closed type here can name, and a client that refused it would refuse a
 * ticket the server accepted. What a key *means* is `StatusCategory`, resolved through
 * `Team.statuses` — never read off the string.
 *
 * The trade is real and it is the one the server already made: the compiler no longer
 * catches a typo'd status literal. What catches it instead is `tickets_status_fk` and the
 * refusal `StatusCategories.require` writes, which names the team's own words — and the
 * screens that offer a status build their list from the team's catalogue rather than
 * typing one.
 */
export type TicketStatus = string;
export type TicketPriority = (typeof TICKET_PRIORITIES)[number];
export type ProjectStatus = (typeof PROJECT_STATUSES)[number];
export type ProjectHealth = (typeof PROJECT_HEALTHS)[number];
/**
 * `connected` is about the instance: false means Notion is not wired up, and the settings
 * screen omits the section rather than drawing a field nobody can fill. `member` is null
 * when Notion is connected but this account has not been matched yet — a different state,
 * and `reason` says which.
 */
export type MyNotionIdentity = {
  connected: boolean;
  notionPersonId?: string;
  member?: { id: string; name?: string; email?: string };
  reason?: string;
};

export type SyncState = "pending" | "synced" | "failed" | "disabled";

export type Mirror = {
  notionPageId?: string;
  state: SyncState;
  syncedAt?: string;
};

/**
 * A date from the API. `hasTime: false` means the value names a *day*, not a
 * moment: render it without timezone conversion, or a reader west of UTC sees the
 * previous day. `at` is always a full instant so the server can do arithmetic on it.
 */
export type KansoInstant = { at: string; hasTime: boolean };

/**
 * The `YYYY-MM-DD` an `<input type="date">` wants, taken by slicing the ISO string.
 * Never `new Date(instant.at)`: that converts, and a day must not be converted.
 */
export const dayValue = (instant: KansoInstant | null | undefined): string =>
  instant ? instant.at.slice(0, 10) : "";

/** The inverse: a date input's value as a floating instant. */
export const fromDayValue = (value: string): KansoInstant | null =>
  value ? { at: `${value}T00:00:00Z`, hasTime: false } : null;

/**
 * One custom field value, and the three JSON scalars are the whole vocabulary —
 * `ticket_field_values_value_chk` refuses an object, an array and a JSON null at the
 * database, so this union is not a simplification of what can arrive.
 *
 * There is no `null` in it: a field with no value has no key at all, because `V35` keeps no
 * row for one. `undefined` from a map lookup is the only spelling of "not set".
 *
 * Declared here rather than in `api/fields.ts` with the rest of the slice, because [Ticket]
 * carries it and that would make the two modules import each other. The slice imports it
 * back from here.
 */
export type CustomFieldValue = string | number | boolean;

export type Ticket = {
  id: string;
  /**
   * `KAN-142`, and absent for a ticket no team has claimed yet. The identifier is a team
   * key and that team's counter, so a ticket outside every team has no name to print — the
   * card draws a "no team" badge where this would have gone, and [id] is what addresses it
   * until somebody files it.
   *
   * Optional rather than `| null`, like every other absent field on this row: the server
   * omits nulls, so what arrives is `undefined` and a `=== null` test would silently miss
   * every draft. The helpers that read these three use `== null` for the same reason.
   */
  identifier?: string;
  number?: number;
  teamId?: string;
  title: string;
  description?: string;
  status: TicketStatus;
  priority: TicketPriority;
  /** Points. Absent means nobody has sized it — never 0, which would be a real estimate. */
  estimate?: EffortPoints;
  start?: KansoInstant;
  due?: KansoInstant;
  projectId?: string;
  assigneeIds: string[];
  docIds: string[];
  archived: boolean;
  /**
   * `V35`'s custom field values, keyed by field id — `{}` for a ticket whose team has
   * defined none, and for every draft.
   *
   * **Required, not optional**, and it is the one field on this row that is. The rest are
   * optional because the server omits nulls; this is a map, so an empty one is sent as `{}`
   * and the key is always there. That is deliberate rather than incidental: the whole reason
   * this shipped before anybody asked for a custom field is that `TicketResponse` is now read
   * by bearer-token scripts and by an MCP agent, and a key that appears later is a key that
   * breaks them. Typing it as required is this client agreeing to the same contract.
   *
   * Unlike labels, these ride on the ticket row rather than living in a cache entry of their
   * own — so a list already drawn carries them, and `useTicketFields` exists only for the
   * panel that edits one ticket.
   */
  customFields: Record<string, CustomFieldValue>;
  /**
   * `V36`'s linked pull requests, newest first within a repository — `[]` for every ticket
   * on an instance with no GitHub App, which is most of them.
   *
   * **Required, not optional**, for exactly the reason `customFields` is: the server always
   * sends the key, and a field that appears later is a field that breaks a script already
   * reading this shape. An array also gives "none" one spelling instead of two.
   */
  pullRequests: PullRequest[];
  /**
   * The branch to create for this ticket, `feat/kan-142-overlap-warning`, derived by the
   * server from the identifier and the title.
   *
   * Optional and not `| null`, like `identifier` above and for the same reason: a draft has
   * no identifier to name a branch after, the server omits nulls, and what arrives is
   * `undefined`. Read it with `== null`.
   */
  branchName?: string;
  mirror: Mirror;
  createdAt: string;
  updatedAt: string;
};

/** GitHub's three, closed on the server by `github_pull_requests_state_chk`. */
export const PR_STATES = ["open", "merged", "closed"] as const;
export type PrState = (typeof PR_STATES)[number];

/**
 * Two words where GitHub's review vocabulary has five: the server maps `commented`,
 * `dismissed` and `pending` to *absent*, because none of them blocks or unblocks a pull
 * request and the pill is the only thing that reads this.
 */
export const PR_REVIEW_STATES = ["approved", "changes_requested"] as const;
export type PrReviewState = (typeof PR_REVIEW_STATES)[number];

/**
 * One pull request on a ticket.
 *
 * Flat, unlike `TicketLink`, which nests a whole `Ticket` because the other end of a link
 * *is* one. Everything a row draws is here, so no row needs a request of its own.
 *
 * There is no `pill` field on purpose. The label is computed from `state`, `draft` and
 * `reviewState` by `prPill`, because a label on the wire would be a derived value stored in
 * a response — and one that could never be translated.
 */
export type PullRequest = {
  repo: string;
  number: number;
  title: string;
  url: string;
  state: PrState;
  draft: boolean;
  /** Absent until somebody reviews, which the pill reads as "In review". */
  reviewState?: PrReviewState;
  authorLogin?: string;
  /**
   * The Kanso member behind `authorLogin`, when that member has linked their GitHub
   * account in their own settings.
   *
   * **Optional and never `| null`.** The server's mapper omits nulls, so an author nobody
   * has linked arrives as an *absent key* — a `User | null` here would be a lie no
   * compiler catches, which is the trap that produced a "Last used Invalid Date" on a
   * screen in this repository. Read it with `== null`.
   *
   * `authorLogin` stays beside it and is what the row shows on its own: an unlinked
   * author reads `@tykok`, exactly as before this field existed. That is the documented
   * fallback, not a degradation.
   */
  author?: User;
  headRef: string;
  /** Whether this pull request may move the ticket, as opposed to merely naming it. */
  closes: boolean;
  /** True when a member drew this link by hand, so re-parsing will not remove it. */
  linkedByMember: boolean;
};

export type Team = {
  id: string;
  name: string;
  key: string;
  parentTeamId?: string;
  archived: boolean;
  /**
   * Tickets ever filed in this team, not tickets it has — the server sends
   * `ticket_counter`, the allocator that makes KAN-14 the fourteenth, so it climbs on a
   * create and never comes back down on a delete.
   *
   * Which is the right answer for its one reader: `emptyReason` asks whether anything has
   * ever been filed anywhere, and an instance whose work has all been deleted is not on a
   * first run. Read it as a high-water mark and not as a counter to draw beside a name.
   */
  ticketCount: number;
  mirror: Mirror;
  /** The server's answer to "may this actor create a ticket here", from `TicketAccess`. */
  editable: boolean;
  /**
   * This team's own words for its work, in its own order — `KAN-28`.
   *
   * **Required, not optional**, like `Ticket.customFields` and for the same reason: the
   * server sends it on every team payload from the version that introduced it, and typing
   * it as optional would push a `?.` into every screen that prints a status.
   *
   * Carried on the team rather than fetched per screen because every screen that draws a
   * status needs it — a list, a board, a chip in a filter — and `lib/statuses.ts` is where
   * the lookups into it live.
   */
  statuses: TeamStatus[];
};

/**
 * One row of a team's status catalogue — `team_statuses`, server side.
 *
 * `key` is what `Ticket.status` holds and what a saved view addresses; it never changes.
 * `label` is the word this team reads, `category` is what everything that reasons about
 * work reads instead of the word, and `position` is the order this team stacks its
 * buckets in.
 */
export type TeamStatus = {
  key: TicketStatus;
  label: string;
  category: StatusCategory;
  position: number;
};

/** Wire values from `dev.kanso.domain.MemberRole`; nothing here names "lead". */
export type MemberRole = "member" | "admin";

export type TeamMemberRow = { user: User; role: MemberRole };

export type Project = {
  id: string;
  name: string;
  status: string;
  start?: KansoInstant;
  end?: KansoInstant;
  leadUserId?: string;
  teamId?: string;
  /**
   * The newest update's health, derived server-side and absent when nobody has posted
   * one. Read-only: it is not on `ProjectBody`, because there is no column to write —
   * changing a project's health means posting an update, which is a different endpoint
   * and a different permission.
   */
  health?: ProjectHealth;
  archived: boolean;
  mirror: Mirror;
};

/** One thing somebody said about how a project is going, on the date they said it. */
export type ProjectUpdate = {
  id: string;
  projectId: string;
  health: ProjectHealth;
  body: string;
  /** Null once the account is gone. The assessment it left behind is not. */
  author: User | null;
  at: string;
};

export type ProjectBody = {
  name: string;
  status?: string;
  /** Null clears the bound; the PUT replaces the project wholesale either way. */
  start?: KansoInstant | null;
  end?: KansoInstant | null;
  leadUserId?: string;
  teamId?: string;
};

// --- timeline ----------------------------------------------------------------

/** A project bound, plus whether anyone posted it — a derived one is not editable. */
export type TimelineBound = KansoInstant & { derived: boolean };

export type TimelineProject = {
  id: string;
  name: string;
  start?: TimelineBound;
  end?: TimelineBound;
};

export type TimelineTicket = {
  id: string;
  identifier: string;
  title: string;
  projectId?: string;
  status: TicketStatus;
  start?: KansoInstant;
  due?: KansoInstant;
  /** Absent for a ticket with no dependencies: it has no slack to report. */
  slackMinutes?: number;
  critical: boolean;
  /**
   * The due date has gone by and nobody has finished or cancelled it. A fact about this
   * ticket alone, which is why a list row can print it without loading a timeline.
   */
  late: boolean;
  /**
   * The critical path says this will overrun: slack is negative.
   *
   * This is what `late` used to mean on this type, and the split is the point — the bar
   * announced negative slack as "overdue", so a screen reader said a ticket was late when
   * its due date was three weeks away. A bar in both states draws `late`.
   */
  slipping: boolean;
  /** Whose ticket this is, printed before the identifier on a context row. */
  teamKey: string;
  /** Drawn for reading: outside the scope, not selectable, never draggable. */
  context: boolean;
  /** The server's answer to "may this viewer move it". Never re-derived here. */
  editable: boolean;
};

export type TimelineDependency = {
  predecessorId: string;
  successorId: string;
  /** The cascade cannot repair this: the successor is done and starts too early. */
  violated: boolean;
  /** Broken now and repairable by moving the successor. Exclusive with `violated`. */
  overlap: boolean;
  /** The other end is outside this response, so the arrow is drawn as a stub. */
  outOfScope: boolean;
};

export type TimelineUnscheduled = { id: string; identifier: string; title: string };

/** How the column is stacked. Mirrors `TimelineSort` — three orders and no fourth. */
export const TIMELINE_SORTS = ["start", "priority", "due"] as const;
export type TimelineSort = (typeof TIMELINE_SORTS)[number];

/**
 * What the column asks for beyond the scope. Absent means the server's own defaults —
 * `start` and everything shown — which is what the screen opens on.
 */
export type TimelineOptions = { sort?: TimelineSort; hideCompleted?: boolean };

export type TimelineView = {
  projects: TimelineProject[];
  tickets: TimelineTicket[];
  dependencies: TimelineDependency[];
  /**
   * The *shared* widening hit `SCOPE_LIMIT`, so bars are missing and the chart has to say
   * so. The scope itself is paged rather than capped — see `hasMore`.
   */
  truncated: boolean;
  /** Another page of the column exists. Nothing is missing from the drawing; scroll. */
  hasMore: boolean;
};

/** What a dependency write returns: the tickets its cascade moved. */
/**
 * The closed vocabulary of `ticket_links.type`, which the server mirrors as
 * `TicketLinkType` and constrains as `ticket_links_type_chk`. Only `blocks` is a
 * schedule — the timeline and the critical path are computed from those alone.
 */
export const TICKET_LINK_TYPES = ["blocks", "relates", "duplicates"] as const;
export type TicketLinkType = (typeof TICKET_LINK_TYPES)[number];

/**
 * One edge, from the point of view of the ticket that was asked about. `ticket` is the
 * one at the *other* end.
 *
 * `outgoing` is what turns one row into two different sentences — "blocks" against
 * "blocked by" — and `symmetric` says when it means nothing worth printing, so the
 * client never has to keep its own list of which types have a direction.
 */
export type TicketLink = {
  ticket: Ticket;
  type: TicketLinkType;
  outgoing: boolean;
  symmetric: boolean;
};

/**
 * A parent's progress, derived server-side from its children's statuses.
 *
 * [total] already excludes cancelled children — they are in neither half of the fraction,
 * so "5 of 5" can mean "finished". The two point fields are null together whenever any
 * counted child is unestimated, because an unestimated child weighs zero in a sum and the
 * parent would otherwise read as complete while part of it had never been sized.
 */
export type SubTicketProgress = {
  total: number;
  done: number;
  /**
   * Optional as well as nullable, and that is the wire being described rather than
   * hedged: the server omits a null field instead of serialising it, so an unestimated
   * parent arrives with both of these absent. Anything reading them has to treat
   * `undefined` and `null` alike — spelling that out here is what makes a `=== null`
   * check look wrong at the call site.
   */
  donePoints?: number | null;
  totalPoints?: number | null;
};

export type CascadeResult = { movedTicketIds: string[] };

/** What happens to what a team or a project holds when the container goes away. */
export type DispositionChoice = "take" | "keep";

export type DispositionCounts = { subTeams: number; projects: number; tickets: number };

/**
 * Both readings of what a container holds, because the plan decides which one is
 * true. `subTeams: "keep"` lets the sub-teams leave first with their own contents
 * untouched, so the operation reaches this container alone — `direct`.
 * `subTeams: "take"` takes the whole subtree, and every project and ticket in it is
 * destroyed, archived or renumbered — `subtree`. The two coincide for a project,
 * which holds no teams, and for a team with no children.
 */
export type DispositionContents = { direct: DispositionCounts; subtree: DispositionCounts };

/**
 * `counts` is what the modal displayed. Deleting sends it so the server can refuse
 * on drift; archiving may omit it, because archiving comes back.
 */
export type DispositionPlan = {
  subTeams: DispositionChoice;
  projects: DispositionChoice;
  tickets: DispositionChoice;
  ticketsTargetTeamId?: string;
  counts?: DispositionCounts;
};

export type InstanceRole = "owner" | "admin" | "member" | "viewer";

export type User = {
  id: string;
  email: string;
  displayName: string;
  avatarUrl?: string;
  notionPersonId?: string;
  instanceRole: InstanceRole;
  /** The ways this account can sign in. The UI refuses to remove the last one. */
  hasPassword: boolean;
  linkedProvider?: string;
};

export type AuthMode = {
  mode: "oidc" | "dev";
  /** Local email + password sign-in, available once an owner account exists. */
  passwordLoginEnabled: boolean;
  providers: { id: string; label: string; authorizeUrl: string }[];
};

// --- preferences -------------------------------------------------------------

export const THEMES = ["system", "light", "dark"] as const;
export const ACCENTS = ["indigo", "blue", "green", "amber", "rose", "violet"] as const;
export const DENSITIES = ["comfortable", "compact"] as const;

export type Theme = (typeof THEMES)[number];
export type Accent = (typeof ACCENTS)[number];
export type Density = (typeof DENSITIES)[number];

export const OPEN_TICKET = ["panel", "page"] as const;
export type OpenTicket = (typeof OPEN_TICKET)[number];

/**
 * How much window the sidebar is allowed to take, in the order the segmented control
 * draws them: most present to least.
 *
 * Three states rather than the boolean this replaces, because "shown" and "hidden" were
 * never the two things a reader wanted to choose between — the third is a column that is
 * out of the way until you reach for it, revealed by a 12px hot zone down the left edge.
 * `hidden` drops the hot zone too, and is reachable only from Appearance: a control whose
 * third state you discover by pressing it twice is what this pass exists to remove.
 */
export const SIDEBAR_MODES = ["pinned", "hover", "hidden"] as const;
export type SidebarMode = (typeof SIDEBAR_MODES)[number];

export type Preferences = {
  theme: Theme;
  accent: Accent;
  density: Density;
  /**
   * What `↵` on a row does: open the panel, or navigate to the ticket's own page.
   *
   * Screen 02 of the design bundle says in as many words that this setting "lives in the
   * preferences", and until slice 0 it did not. `⤢` and `⇧↵` expand the current ticket
   * without changing it — the preference is the default, not the only way through.
   */
  openTicket: OpenTicket;
  sidebarMode: SidebarMode;
  showSyncBadges: boolean;
  showStatusBar: boolean;
  /**
   * Whether the top bar draws Filter, Group and Order as buttons.
   *
   * The same three intentions `Mod+f`, `Mod+g` and `Mod+o` reach, for the reader who
   * would rather click. Optional where the bell, the breadcrumb and the `×` are not:
   * those are how you get somewhere, these are a second spelling of something already
   * reachable, so hiding them takes nothing away.
   */
  showViewControls: boolean;
  /**
   * Remapped keys: action id to the chords that reach it — **overrides only**.
   *
   * Never a full copy of the defaults. Those are derived from the action registry by
   * `DEFAULT_BINDINGS`, so a stored copy would freeze one release's key set into the
   * account and a default improved later would never reach it. `{}` is the honest
   * representation of "I never changed anything", and it is the common case.
   *
   * The server validates shape and nothing else — it has no way to know whether an
   * action id exists, since the registry is a module in this bundle. `mergeBindings` is
   * where meaning is decided, and it ignores ids it does not recognise so that an action
   * deleted in a later version cannot make a stored preference unreadable.
   */
  shortcuts: Record<string, string[]>;
  defaultTeamId?: string;
  /** Set once the account has passed `/setup`'s account screen — the routing guard's own
   *  signal that there is nothing left here for this account to do. */
  onboardedAt?: string;
  /**
   * Points per working day, as this person estimates their own pace. Absent means they
   * never said — never `0`, which would be a claim that they deliver nothing.
   *
   * A seed, not a setting: once two of their team's cycles have closed, Kanso plans with
   * the measured number instead and keeps this one beside it as a reference. Whether it
   * is currently in force is `EffectiveVelocity.source`, not something to work out here.
   */
  declaredVelocity?: number;
};

export const DEFAULT_PREFERENCES: Preferences = {
  theme: "system",
  accent: "indigo",
  density: "comfortable",
  openTicket: "panel",
  sidebarMode: "pinned",
  showSyncBadges: true,
  showStatusBar: true,
  showViewControls: true,
  // Empty, and never a copy of `DEFAULT_BINDINGS`: the defaults come from the registry
  // at read time, so the account that has changed nothing follows them as they improve.
  // Read-only, like the rest of this object — a spread of `DEFAULT_PREFERENCES` shares
  // this `{}` by reference, so an override is a new object, never a write into this one.
  shortcuts: {},
};

// --- velocity ----------------------------------------------------------------

export const VELOCITY_SOURCES = ["declared", "measured", "none"] as const;
export type VelocitySource = (typeof VELOCITY_SOURCES)[number];

/**
 * One person's pace, with the arbitration already done.
 *
 * [source] is the whole reason this type exists rather than two loose numbers. The server
 * decides which of the declared and the measured value is in force; re-deriving that here
 * would be a second copy of the rule, free to disagree with the first the day it moves.
 *
 * Both numbers are always carried, whichever won: the screen shows the loser beside the
 * winner, and a lasting gap between them is information rather than an error.
 *
 * `perWorkingDay` is null exactly when `source` is `none` — null, never 0, because 0 is a
 * measurement ("delivers nothing") and null is the absence of one.
 */
export type EffectiveVelocity = {
  /** Absent — never `0` — when `source` is `none`. The API omits nulls rather than sending them. */
  perWorkingDay?: number;
  source: VelocitySource;
  declared?: number;
  measured?: number;
  /** How much history `measured` stands on. Zero means it could not be measured at all. */
  measuredCycles: number;
  /** Closed cycles still needed before the measurement takes over. Zero once it has. */
  cyclesUntilMeasured: number;
};

// --- my progress -------------------------------------------------------------

/**
 * One bar of the delivered-points chart: what this person shipped in one closed cycle.
 *
 * `points` is fractional because a ticket with two assignees gives half of itself to each,
 * so a share of an 8 is a 4 and a share of a 5 is not an integer at all.
 *
 * `countedTowardsVelocity` is what keeps the chart and the number above it from reading as
 * two contradictory claims. The chart is drawn over six closed cycles and the velocity is
 * measured over three of them, so some bars are history the number is not standing on —
 * and false on every bar when a declared velocity is in force, which is correct: it was
 * measured over nothing.
 */
export type DeliveredCycle = {
  cycleId: string;
  number: number;
  /** `YYYY-MM-DD`, both of them — a cycle is whole days, so no timezone. */
  startsOn: string;
  endsOn: string;
  points: number;
  workingDays: number;
  /** Finished tickets in this cycle that nobody sized. Absent from `points`, never zero. */
  unestimated: number;
  countedTowardsVelocity: boolean;
};

/**
 * One cut of an open plate. `unestimated` travels with `points` everywhere it is drawn,
 * because a sum that quietly leaves out half a plate reads as the whole of it.
 */
export type LoadSlice = { tickets: number; points: number; unestimated: number };

/** `projectId` and `projectName` are absent together: the tickets filed under no project. */
export type ProjectLoad = { projectId?: string; projectName?: string; load: LoadSlice };

export type OpenLoad = {
  load: LoadSlice;
  /** Keyed by the status wire value, and every open status is present, including zeros. */
  byStatus: Record<string, LoadSlice>;
  /** The bar's segments, in order, as the server grouped them — `StatusBucket`. */
  buckets: { key: string; label: string; category: StatusCategory }[];
  /** Heaviest first, and the unfiled pile last however big it is. */
  byProject: ProjectLoad[];
  /**
   * The plate's points divided by the pace in force — "you are carrying 6.5 days of work".
   *
   * Absent, never `0`, when there is no pace to divide by. It is *not* absent for an empty
   * plate: nought days is the honest reading of nothing, and it is the one zero on this
   * page that is a measurement rather than a gap.
   */
  workingDays?: number;
};

/**
 * Everything the personal-progress screen draws, with the arbitration already done.
 *
 * `person` is on the response even though `/api/me/progress` only answers for the caller:
 * the shape is the one the admin view of somebody else will serve, and a page that could
 * not name its own subject would need a second request to write its heading.
 *
 * `delivered` is **oldest first** — the order the chart draws, decided on the server so
 * that no caller can forget to reverse it and draw a rise as a fall.
 */
export type Progress = {
  /**
   * Spelled out rather than imported as `Person`, which lives in `organise.ts` — and
   * `organise.ts` imports from here, so naming it would be a module cycle. TypeScript is
   * structural, so `@/lib/api`'s `Person` is assignable to this and back.
   */
  person: { id: string; displayName: string; avatarUrl?: string };
  velocity: EffectiveVelocity;
  delivered: DeliveredCycle[];
  load: OpenLoad;
  readers: ProgressReaders;
  insights: Insights;
};

// --- cycle time and work in flight -------------------------------------------

/**
 * A median in **hours**, and how much of the sample it stands on.
 *
 * Hours, and the unit is the one thing a reader of this type has to hold on to: the pace on
 * the same screen is points per *working* day and this is elapsed wall-clock, weekends
 * included. The two are not divisible into one another and nothing on the screens multiplies
 * them. `lib/insights.ts` is the only place hours become words.
 *
 * `medianHours` is **absent, not `null`**, when nothing could be measured — the server omits
 * nulls, so a type claiming `| null` here would hand a component `undefined` and it would
 * print it. Every branch that draws this compares against `undefined`.
 *
 * `unmeasured` travels with the median the way `unestimated` travels with a points total: it
 * counts delivered tickets whose start was never recorded — dragged from `todo` straight to
 * `done`, or created finished — and a sample that dropped them silently would read as the
 * whole of the work.
 */
export type CycleTime = {
  medianHours?: number;
  /** Tickets the median actually stands on. */
  measured: number;
  unmeasured: number;
};

/** One point of the trend. Labelled by `number`, like the delivered bars beside it. */
export type CycleTimePoint = {
  cycleId: string;
  number: number;
  cycleTime: CycleTime;
};

/**
 * What is in flight now, and since when.
 *
 * `load` is the started slice of the same plate `OpenLoad` cuts — gathered on the server so
 * that "in flight" is `StatusCategory.STARTED` there rather than two status names added up
 * here, which would stop following the vocabulary the day a seventh status means it.
 *
 * Both ages are absent together when nothing in flight has a recorded start. They are kept
 * side by side because they mislead separately: a median alone hides the one ticket that is
 * stuck, and a maximum alone makes a healthy board with one straggler look like a fire.
 */
export type Wip = {
  load: LoadSlice;
  medianAgeHours?: number;
  oldestAgeHours?: number;
  unmeasured: number;
};

/**
 * KAN-23: cycle time, work in flight, and the trend across closed cycles.
 *
 * On `Progress` and on `TeamProgress` rather than behind a route of its own, so the figures
 * arrive with the bars they are measured over and under the permission rule that already
 * governs them.
 */
export type Insights = {
  /** Pooled over every ticket the closed cycles delivered, not the mean of `trend`. */
  cycleTime: CycleTime;
  /** Oldest first — the order the chart draws. */
  trend: CycleTimePoint[];
  wip: Wip;
};

/** A team named and nothing else — the heading of a page, or a clause in a sentence. */
export type TeamRef = { id: string; name: string; key: string };

/**
 * Who else can read a person's figures.
 *
 * On every version of the page, including the caller's own, and *especially* there: it is
 * the only place a person will look to find out that somebody else can read this. Both
 * lists are other people — the subject is in neither, and neither is a team whose only
 * titled administrator is the subject themselves.
 *
 * Two empty lists together are the common and honest answer on a small instance: nobody
 * but you.
 */
export type ProgressReaders = {
  instanceAdmins: { id: string; displayName: string; avatarUrl?: string }[];
  teams: TeamRef[];
};

// --- a team's progress -------------------------------------------------------

/**
 * A team's pace. No `source`, because nobody declares a team's velocity.
 *
 * Deliberately not an `EffectiveVelocity` with three fields left absent: that type's whole
 * subject is which of two numbers is in force, and a client handed it would caption a
 * team's number with a rule that was never applied to it.
 */
export type TeamPace = {
  /** Absent — never `0` — when no closed cycle could be measured. */
  perWorkingDay?: number;
  /** How many closed cycles the mean stands on. Zero means there was nothing to measure. */
  measuredCycles: number;
};

/**
 * A team's figures, and **no field that names a person.**
 *
 * That absence is the feature. Ranking people by points delivered is out of scope by the
 * ticket, and a response with no row to sort is how it stays out of scope rather than by a
 * decision the next person to touch this file has to rediscover. `byStatus` and `byProject`
 * cut the plate by things; the per-person cut of the same plate is the workload screen,
 * which has been open to every reader since screen 23 and is not repeated here.
 */
export type TeamProgress = {
  team: TeamRef;
  pace: TeamPace;
  /** Oldest first — the order the chart draws. */
  delivered: DeliveredCycle[];
  load: OpenLoad;
  /**
   * The trend KAN-23 asks for per team, and it reopens no ranking: a median over a team's
   * delivered tickets has no row to sort, because this response still has no per-person
   * field to put one on.
   */
  insights: Insights;
};

/**
 * How long a ticket should take, or which of three reasons Kanso will not say.
 *
 * A discriminated union on `basis`, so the four cases are four branches the compiler
 * counts. The three absences are deliberately not one nullable range: a screen that cannot
 * tell "nobody sized this" from "nobody is on this" points the reader at the wrong fix,
 * and an empty field reads as something that failed to load.
 *
 * There is no point estimate anywhere in this type, only the two ends. A field holding the
 * un-widened number would get printed, and a bare date off a three-cycle mean is exactly
 * what the range exists to prevent.
 */
export type TicketDuration =
  | {
      basis: "estimated";
      lowWorkingDays: number;
      highWorkingDays: number;
      points: number;
      assignees: number;
      /** Assignees with no known pace, and so the amount this range overstates by. */
      withoutVelocity: number;
    }
  // Carries nothing but the reason. The API omits nulls, so the four numbers are not
  // absent-and-null here — they are not on the wire at all, and the union is what makes
  // reaching for one a compile error rather than a `NaN` on the screen.
  | { basis: "no_estimate" | "no_assignee" | "no_velocity" };

/** Preferences travel with the session so the first paint needs one round trip, not two. */
export type Me = {
  user: User;
  teamIds: string[];
  preferences: Preferences;
  /**
   * Whether any ticket in the instance has been moved past where the composer leaves it
   * — screen 08's "Move it along", answered by the server so the sidebar's checklist
   * needs no ticket list of its own. See `components/inbox/onboarding-checklist.tsx`.
   *
   * About the instance and not about this reader, like [version] and unlike the three
   * fields above it.
   */
  workMovedAlong: boolean;
  /** The API's build version. Compared against WEB_VERSION: a skew is worth seeing. */
  version: string;
};

// --- first-run setup ---------------------------------------------------------

export type IntegrationState = {
  configured: boolean;
  /**
   * Set from the environment rather than the wizard. The UI shows it read-only:
   * letting both write the same setting is how they end up disagreeing.
   */
  managedByEnvironment: boolean;
};

export type SetupState = {
  /** No owner yet: this instance has never been set up. */
  needsOwner: boolean;
  setupCompletedAt?: string;
  notion: IntegrationState & {
    parentPageId?: string;
    bootstrapped: boolean;
    /**
     * Whether a public integration exists for consent to be asked through — which is a
     * different question from `configured`. The pair makes the Connect button possible;
     * a token makes the mirror work. An instance can have either without the other, and
     * the screen has to tell "nothing set up" from "set up, nobody has consented yet".
     */
    appConfigured: boolean;
    /**
     * The integration comes from `NOTION_CLIENT_ID` / `NOTION_CLIENT_SECRET` rather than
     * from this screen. The opposite of `managedByEnvironment` in what it hides: a pinned
     * token leaves nothing to connect, a pinned integration leaves nothing to type — the
     * button is precisely what remains.
     */
    appManagedByEnvironment: boolean;
    /** The workspace a completed consent named. Absent when the token was pasted. */
    workspaceName?: string;
  };
  google: IntegrationState & { clientId?: string };
};

/** One page the integration can write under — what the parent-page picker offers. */
export type NotionParentPage = {
  id: string;
  /** Absent when the page has no title. Notion allows it; `pageLabel` names it. */
  title?: string;
  url?: string;
};

/**
 * What the picker gets back.
 *
 * Three states in one shape, and the step says something different about each:
 * `available: false` with a `reason` is "no workspace to search yet"; available with no
 * pages is "the integration exists and nobody has shared a page with it", which is the
 * silent failure the pasted id used to hide; available with pages is the list.
 */
export type NotionParentPages = {
  available: boolean;
  reason?: string;
  pages: NotionParentPage[];
};

export type InvitationLink = { url: string; expiresAt: string };

export type PendingInvitation = {
  id: string;
  email?: string;
  role: InstanceRole;
  createdAt: string;
  expiresAt: string;
  expired: boolean;
};

/**
 * What the status bar reads, on every shell, every ten seconds, for every member.
 *
 * `jobs` is the whole queue grouped by status, so `jobs.failed` is the true count and not
 * the length of a list capped at fifty — which is what the badge used to count, and why an
 * instance three hundred pushes behind said "50 failed".
 *
 * There is no `databases` and no error string here on purpose: those name pages in a Notion
 * workspace this instance does not own, and they are `SyncDetail`'s. See
 * `SyncAdminController.status`.
 */
export type SyncStatus = {
  mirrorEnabled: boolean;
  bootstrapped: boolean;
  jobs: Record<string, number>;
};

/** The same reading with the identifiers in it, refused to anyone but a configurator. */
export type SyncDetail = SyncStatus & {
  databases: { kind: string; databaseId: string; dataSourceId: string }[];
  failed: { id: number; entity: string; entityId: string; attempts: number; error?: string }[];
  cursors: {
    dataSourceId: string;
    lastEditTime?: string;
    lastRunAt?: string;
    lastError?: string;
  }[];
};

/** One outbox row as `GET /api/admin/sync/queue` lists it — `SyncAdminController.queue`. */
export type QueuedJob = {
  id: number;
  entity: string;
  entityId: string;
  label?: string;
  operation: string;
  status: "pending" | "running" | "failed";
  attempts: number;
  nextAttemptAt?: string;
  error?: string;
};

export type SyncQueue = {
  counts: Record<string, number>;
  queued: QueuedJob[];
  failed: QueuedJob[];
};

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly detail: string,
    /**
     * The whole problem document. A 409 on a disposition carries the fresh
     * `counts` there, and the modal has to reopen on them.
     */
    readonly body?: unknown,
  ) {
    super(detail);
  }
}

/** Dev-mode identity, so one browser can act as several people while testing. */
const DEV_USER_KEY = "kanso.devUser";

export function getDevUser(): string | null {
  if (typeof window === "undefined") return null;
  return window.localStorage.getItem(DEV_USER_KEY);
}

export function setDevUser(email: string | null) {
  if (typeof window === "undefined") return;
  if (email) window.localStorage.setItem(DEV_USER_KEY, email);
  else window.localStorage.removeItem(DEV_USER_KEY);
}

/**
 * The one fetch every slice's client goes through.
 *
 * Exported rather than private because `api/core.ts` is no longer the only file that
 * talks to the API: each slice owns `api/<slice>.ts`. This is the single place that
 * attaches the session cookie, the dev-mode identity header and the `ApiError`
 * conversion, and re-implementing any of that per slice is how a screen ends up
 * silently unauthenticated. Three of the six branches copied it before this line
 * existed, which is the argument.
 */
export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const devUser = getDevUser();
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    // The session cookie is the only credential; it also authenticates the
    // WebSocket handshake, so there is no token to juggle here.
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(devUser ? { "X-Kanso-User": devUser } : {}),
      ...init.headers,
    },
  });

  if (response.status === 204) return undefined as T;

  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? response.statusText, problem);
  }
  return response.json() as Promise<T>;
}

/** Drops absent parameters rather than sending `undefined` as a literal string. */
export function query(params: Record<string, string | number | boolean | undefined>): string {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined) search.set(key, String(value));
  }
  const encoded = search.toString();
  return encoded ? `?${encoded}` : "";
}

export const api = {
  authMode: () => request<AuthMode>("/api/auth/mode"),
  me: () => request<Me>("/api/me"),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),

  login: (body: { email: string; password: string }) =>
    request<void>("/api/auth/login", { method: "POST", body: JSON.stringify(body) }),

  /** Public: the sign-in screen has to know whether this instance is set up yet. */
  setupState: () => request<SetupState>("/api/setup/state"),

  /** Claims the instance. Succeeds exactly once, whatever the client does. */
  createOwner: (body: { email: string; displayName: string; password: string }) =>
    request<Me>("/api/setup/owner", { method: "POST", body: JSON.stringify(body) }),

  /** The public integration's own credentials. Saving them connects nothing. */
  saveNotionApp: (body: { clientId: string; clientSecret?: string }) =>
    request<SetupState>("/api/setup/notion/app", { method: "POST", body: JSON.stringify(body) }),

  /**
   * Answers with the consent URL rather than redirecting to it: a redirect would be
   * followed by `fetch` and land here as an opaque CORS failure. The caller navigates
   * the window itself.
   */
  startNotionConnect: () =>
    request<{ url: string; redirectUri: string }>("/api/setup/notion/authorize", {
      method: "POST",
    }),

  saveNotion: (body: { token?: string; parentPageId: string }) =>
    request<SetupState>("/api/setup/notion", { method: "POST", body: JSON.stringify(body) }),

  /** Round trip to Notion before saving, so a bad token is caught in the wizard. */
  testNotion: (body: { token?: string; parentPageId?: string }) =>
    request<{ ok: boolean; detail: string }>("/api/setup/notion/test", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /**
   * The pages the parent page can be picked from, instead of typed.
   *
   * The token travels in a header because the picker has to work before anything is
   * saved — the same reason `testNotion` takes one — and a GET has no body to put it in.
   * A query parameter would print the secret into every access log there is.
   */
  notionPages: ({ token }: { token?: string } = {}) =>
    request<NotionParentPages>("/api/setup/notion/pages", {
      headers: token ? { "X-Notion-Token": token } : {},
    }),

  /**
   * Answers the mirror's own reading — the four ids it has just created — and not a setup
   * state, which is what `SyncAdminController.bootstrapNotion` says it returns and why.
   * Typed as one for long enough that both callers wrote it straight into the setup cache,
   * where `notion` is not a field: the screen that had just created the databases went
   * blank on its next render. A caller that draws `bootstrapped` refetches instead.
   */
  bootstrapNotion: () => request<SyncDetail>("/api/admin/notion/bootstrap", { method: "POST" }),

  saveGoogle: (body: { clientId: string; clientSecret: string }) =>
    request<SetupState>("/api/setup/google", { method: "POST", body: JSON.stringify(body) }),

  /**
   * Round trip to Google before saving, so a mistyped secret is caught here rather
   * than at the first attempt to sign in with it. Either field may be omitted to
   * check what is already stored.
   */
  testGoogle: (body: { clientId?: string; clientSecret?: string }) =>
    request<{ ok: boolean; detail: string }>("/api/setup/google/test", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  createInvitation: (body: { email?: string; role?: InstanceRole }) =>
    request<InvitationLink>("/api/setup/invitations", { method: "POST", body: JSON.stringify(body) }),

  acceptInvitation: (body: {
    token: string;
    email: string;
    displayName: string;
    password: string;
  }) => request<Me>("/api/auth/accept-invitation", { method: "POST", body: JSON.stringify(body) }),

  // --- account -------------------------------------------------------------

  renameMe: (displayName: string) =>
    request<User>("/api/me", { method: "PUT", body: JSON.stringify({ displayName }) }),

  changePassword: (body: { currentPassword: string; newPassword: string }) =>
    request<void>("/api/me/password", { method: "PUT", body: JSON.stringify(body) }),

  /**
   * Read-only, and it used to be a write. Which Notion person an account is belongs to the
   * workspace, not to the account: it is decided on the import's matching screen, and
   * setting it here let somebody claim a colleague's identity and have the mirror
   * attribute that colleague's work to them.
   */
  myNotionIdentity: () => request<MyNotionIdentity>("/api/me/notion-identity"),

  /** Refused by the server when it would leave no way to sign in. */
  unlinkProvider: (provider: string) =>
    request<User>(`/api/me/identities/${provider}`, { method: "DELETE" }),

  // --- people --------------------------------------------------------------

  people: () => request<User[]>("/api/people"),

  setRole: (userId: string, role: InstanceRole) =>
    request<User>(`/api/people/${userId}/role`, { method: "PUT", body: JSON.stringify({ role }) }),

  pendingInvitations: () => request<PendingInvitation[]>("/api/people/invitations"),

  revokeInvitation: (id: string) =>
    request<void>(`/api/people/invitations/${id}`, { method: "DELETE" }),

  preferences: () => request<Preferences>("/api/me/preferences"),

  /**
   * Your own, and only your own. A cycle is one team's calendar, so `teamId` is required
   * — somebody in two teams has two paces measured against two different fortnights and
   * picking one for them would show a number measured against the wrong one.
   */
  velocity: (teamId: string) => request<EffectiveVelocity>(`/api/me/velocity${query({ teamId })}`),

  /**
   * Your own progress page, in one request.
   *
   * One call rather than the velocity plus a workload read plus a cycle list: the three
   * are one question — how fast do you go, and how much are you carrying at that speed —
   * and answered separately they would be three caches free to disagree about which cycles
   * are closed. `teamId` is required for the reason `velocity` requires it.
   */
  progress: (teamId: string) => request<Progress>(`/api/me/progress${query({ teamId })}`),

  /**
   * Somebody else's progress page, same shape, admin-only on the server.
   *
   * The rule is `ProgressAccess`, not this function: a 403 here is the product working. The
   * client's job is to render the refusal as a sentence rather than to guess in advance who
   * may ask — a page that hid itself and an endpoint that answered anyway would be the
   * failure the ticket is about.
   */
  personProgress: (userId: string, teamId: string) =>
    request<Progress>(`/api/people/${userId}/progress${query({ teamId })}`),

  /**
   * A team's figures, in aggregates.
   *
   * It doubles as the capability probe for this screen, which is why nothing else has to be
   * added to `/api/me`: the rule that governs it is *exactly* the rule that governs reading
   * another person, minus the "you always read yourself" branch. So a caller who gets an
   * answer here may read anybody in this team, and one who gets a 403 may read only
   * themselves.
   */
  teamProgress: (teamId: string) => request<TeamProgress>(`/api/teams/${teamId}/progress`),

  /**
   * Beside the ticket rather than on it: this costs a walk of the team's closed cycles and
   * a preferences read per assignee, which a list of two hundred rows should not pay to
   * render something only the detail view draws.
   */
  ticketDuration: (id: string) => request<TicketDuration>(`/api/tickets/${id}/duration`),

  /**
   * `onboarded: true` stamps the moment the account screen was passed. It is a command,
   * not a field — `onboardedAt` is a server timestamp — kept here because
   * `PreferencesPatch` accepts it, though nothing in this app sends it any more: every
   * account-creation path stamps it from the backend directly (`claimOwner`,
   * `UserProvisioning`, `InvitationService.accept`) rather than through this call.
   */
  savePreferences: (body: Partial<Preferences> & { onboarded?: boolean }) =>
    request<Preferences>("/api/me/preferences", { method: "PUT", body: JSON.stringify(body) }),

  // --- teams ---------------------------------------------------------------

  teams: (includeArchived = false) => request<Team[]>(`/api/teams${query({ includeArchived })}`),

  createTeam: (body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>("/api/teams", { method: "POST", body: JSON.stringify(body) }),

  /** Reparenting is this call too: the parent is just another field. */
  updateTeam: (id: string, body: { name: string; key?: string; parentTeamId?: string }) =>
    request<Team>(`/api/teams/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  teamContents: (id: string) => request<DispositionContents>(`/api/teams/${id}/contents`),

  archiveTeam: (id: string, plan: DispositionPlan) =>
    request<Team>(`/api/teams/${id}/archive`, { method: "PUT", body: JSON.stringify(plan) }),

  unarchiveTeam: (id: string) => request<Team>(`/api/teams/${id}/unarchive`, { method: "POST" }),

  deleteTeam: (id: string, plan: DispositionPlan) =>
    request<void>(`/api/teams/${id}`, { method: "DELETE", body: JSON.stringify(plan) }),

  teamMembers: (teamId: string) => request<TeamMemberRow[]>(`/api/teams/${teamId}/members`),

  addTeamMember: (teamId: string, userId: string, role: MemberRole) =>
    request<TeamMemberRow[]>(`/api/teams/${teamId}/members`, {
      method: "POST",
      body: JSON.stringify({ userId, role }),
    }),

  removeTeamMember: (teamId: string, userId: string) =>
    request<void>(`/api/teams/${teamId}/members/${userId}`, { method: "DELETE" }),

  // --- projects ------------------------------------------------------------

  /**
   * Called once with no team: the sidebar draws every project to build its tree,
   * and a query per team would be one request per row to render it.
   */
  projects: (opts: { teamId?: string; includeArchived?: boolean } = {}) =>
    request<Project[]>(
      `/api/projects${query({
        teamId: opts.teamId,
        includeDescendants: opts.teamId === undefined ? undefined : true,
        includeArchived: opts.includeArchived,
      })}`,
    ),

  createProject: (body: ProjectBody) =>
    request<Project>("/api/projects", { method: "POST", body: JSON.stringify(body) }),

  /** Omitting `teamId` is how a project becomes transverse. */
  updateProject: (id: string, body: ProjectBody) =>
    request<Project>(`/api/projects/${id}`, { method: "PUT", body: JSON.stringify(body) }),

  projectContents: (id: string) => request<DispositionContents>(`/api/projects/${id}/contents`),

  archiveProject: (id: string, plan: DispositionPlan) =>
    request<Project>(`/api/projects/${id}/archive`, { method: "PUT", body: JSON.stringify(plan) }),

  unarchiveProject: (id: string) =>
    request<Project>(`/api/projects/${id}/unarchive`, { method: "POST" }),

  deleteProject: (id: string, plan: DispositionPlan) =>
    request<void>(`/api/projects/${id}`, { method: "DELETE", body: JSON.stringify(plan) }),

  /** Newest first — the reader wants what is true now, and the rest as context under it. */
  projectUpdates: (id: string) => request<ProjectUpdate[]>(`/api/projects/${id}/updates`),

  /**
   * No date on the way in: the server dates an update when it is written. A caller-supplied
   * one would let somebody backfill a history nobody lived through, which is the only thing
   * that would make this record unreadable as evidence.
   */
  postProjectUpdate: (id: string, body: { health: ProjectHealth; body: string }) =>
    request<ProjectUpdate>(`/api/projects/${id}/updates`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  // --- tickets -------------------------------------------------------------

  /**
   * A team scope includes its descendants, so a parent shows the work of its
   * sub-teams. A project scope needs no team: a project may span several, or none.
   */
  tickets: (scope: Scope, includeArchived = false) =>
    request<Ticket[]>(
      `/api/tickets${query({
        limit: 200,
        teamId: scope.kind === "team" ? scope.id : undefined,
        includeDescendants: scope.kind === "team" ? true : undefined,
        projectId: scope.kind === "project" ? scope.id : undefined,
        includeArchived,
      })}`,
    ),

  /**
   * One row. What a realtime event is worth fetching: the event names an id, and
   * refetching the list it happens to be in to learn what changed about it is the
   * round trip `lib/realtime-events.ts` exists to avoid.
   */
  ticket: (id: string) => request<Ticket>(`/api/tickets/${id}`),

  /** The drafts: tickets no team has claimed, which are in no other list this API serves. */
  drafts: () => request<Ticket[]>("/api/tickets/drafts"),

  createTicket: (body: {
    /** Absent files a draft — see `Ticket.identifier` for what that costs it. */
    teamId?: string;
    title: string;
    /**
     * Absent rather than empty when the composer is folded, which is every creation that
     * does not start from a template. `TicketService.create` has accepted a description
     * since `V1`; this type simply never offered one, because no screen had a field for it.
     */
    description?: string;
    status?: TicketStatus;
    priority?: TicketPriority;
    estimate?: EffortPoints;
    projectId?: string;
    assigneeIds?: string[];
  }) => request<Ticket>("/api/tickets", { method: "POST", body: JSON.stringify(body) }),

  patchTicket: (
    id: string,
    body: Partial<{
      title: string;
      description: string;
      status: TicketStatus;
      priority: TicketPriority;
      estimate: EffortPoints;
      start: KansoInstant;
      due: KansoInstant;
      projectId: string;
      /** Attaching a draft to a team. There is no way back: the server refuses `unset`. */
      teamId: string;
      archived: boolean;
      unset: string[];
    }>,
  ) => request<Ticket>(`/api/tickets/${id}`, { method: "PATCH", body: JSON.stringify(body) }),

  // --- a team's own words ---------------------------------------------------
  //
  // `KAN-28`. No create and no delete, and their absence is the ticket's boundary rather
  // than an oversight: adding or removing a status is `KAN-90`, because it makes
  // `Ticket.status` unrepresentable as an enum on the server.

  renameStatus: (teamId: string, key: string, label: string) =>
    request<TeamStatus>(`/api/teams/${teamId}/statuses/${key}`, {
      method: "PATCH",
      body: JSON.stringify({ label }),
    }),

  /**
   * A word and what it means — `KAN-90`. No key: the server derives it from the label.
   *
   * `category` is required by the request shape because `TeamStatusService.add` will not
   * let it change afterwards. Defaulting it here would answer, silently, the one question
   * this control exists to ask.
   */
  addStatus: (teamId: string, label: string, category: StatusCategory) =>
    request<TeamStatus>(`/api/teams/${teamId}/statuses`, {
      method: "POST",
      body: JSON.stringify({ label, category }),
    }),

  /**
   * `into` names where the status's tickets go, and is omitted when it holds none.
   *
   * A query parameter rather than a body, because a DELETE with a body is dropped by
   * enough proxies not to rely on — `TeamStatusController` says the same thing from the
   * other side.
   */
  removeStatus: (teamId: string, key: string, into?: string) =>
    request<void>(
      `/api/teams/${teamId}/statuses/${key}${into ? `?into=${encodeURIComponent(into)}` : ""}`,
      { method: "DELETE" },
    ),

  /** The whole order, because the server refuses a partial one — see `TeamStatusService`. */
  reorderStatuses: (teamId: string, keys: readonly string[]) =>
    request<TeamStatus[]>(`/api/teams/${teamId}/statuses/order`, {
      method: "PUT",
      body: JSON.stringify({ keys }),
    }),

  deleteTicket: (id: string) => request<void>(`/api/tickets/${id}`, { method: "DELETE" }),

  // --- timeline ------------------------------------------------------------

  /**
   * One GET for the whole screen, and `page` bounds the **column** rather than the chart.
   *
   * Bounds, slack and the arrows are still computed together and still arrive together —
   * a Gantt showing two pages of a shape would show two plans. What pages is the list
   * beside it, which holds every ticket in scope including finished work.
   */
  timeline: (scope: Scope, options?: TimelineOptions) =>
    request<TimelineView>(
      `/api/timeline${query({
        teamId: scope.kind === "team" ? scope.id : undefined,
        projectId: scope.kind === "project" ? scope.id : undefined,
        sort: options?.sort,
        hideCompleted: options?.hideCompleted ? "true" : undefined,
      })}`,
    ),

  /** `{id}` is the successor; the body names what it now waits on. */
  linkDependency: (successorId: string, predecessorId: string) =>
    request<CascadeResult>(`/api/tickets/${successorId}/dependencies`, {
      method: "POST",
      body: JSON.stringify({ predecessorId }),
    }),

  unlinkDependency: (successorId: string, predecessorId: string) =>
    request<void>(`/api/tickets/${successorId}/dependencies/${predecessorId}`, {
      method: "DELETE",
    }),

  /**
   * Every edge touching one ticket, of all three kinds. The per-ticket endpoint the
   * ticket page used to lack — its "Depends on" chips were scraped out of the timeline
   * response, which meant a ticket outside the chart's current scope drew none.
   */
  ticketLinks: (ticketId: string) => request<TicketLink[]>(`/api/tickets/${ticketId}/links`),

  /** The sub-tickets of one ticket. At most one level: a child never has children. */
  ticketChildren: (ticketId: string) => request<Ticket[]>(`/api/tickets/${ticketId}/children`),

  /**
   * How much of a parent is finished, derived on read from its children — never a stored
   * column, so it cannot disagree with them.
   *
   * `null` for a ticket with no children, which is most of them: "0 of 0 done" is a
   * number about nothing, so the client draws nothing rather than an empty bar.
   */
  ticketProgress: (ticketId: string) =>
    request<SubTicketProgress | null>(`/api/tickets/${ticketId}/progress`),

  setTicketParent: (ticketId: string, parentId: string | null) =>
    request<void>(`/api/tickets/${ticketId}/parent`, {
      method: "PUT",
      body: JSON.stringify({ parentId }),
    }),

  /** `{id}` is the `from` end — for `duplicates`, the ticket being retired. */
  linkTicket: (fromId: string, otherId: string, type: TicketLinkType) =>
    request<CascadeResult>(`/api/tickets/${fromId}/links`, {
      method: "POST",
      body: JSON.stringify({ otherId, type }),
    }),

  unlinkTicket: (fromId: string, otherId: string, type: TicketLinkType) =>
    request<void>(`/api/tickets/${fromId}/links/${type}/${otherId}`, { method: "DELETE" }),

  users: () => request<User[]>("/api/users"),
  syncStatus: () => request<SyncStatus>("/api/admin/sync"),
  syncDetail: () => request<SyncDetail>("/api/admin/sync/detail"),
  syncQueue: () => request<SyncQueue>("/api/admin/sync/queue"),
};
