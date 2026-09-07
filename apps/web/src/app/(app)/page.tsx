"use client";

import { useRouter } from "next/navigation";
import { Fragment, useCallback, useEffect, useMemo, useState, type ReactNode } from "react";
import { BoardView } from "@/components/board/view";
import { EmptyState } from "@/components/inbox/empty-state";
import { NewMenu } from "@/components/new-menu";
import { nameGroups } from "@/components/organise/grouping";
import { bucketLabel } from "@/lib/statuses";
import { ListFilters } from "@/components/organise/list-filters";
import { TopbarSlot, usePageShell, useReportError } from "@/components/shell/topbar-slot";
import { usePageActions } from "@/components/shell/use-shell-keys";
import { TicketList } from "@/components/tickets";
import { TimelineView } from "@/components/timeline/view";
import { actionById, predecessorsOf, PRIORITY_ACTIONS } from "@/lib/actions";
import {
  getDevUser,
  setDevUser,
  ticketAddress,
  ticketHref,
  type Ticket,
} from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { isMac } from "@/lib/platform";
import { useNarrow } from "@/lib/use-narrow";
import {
  useAuthMode,
  useGroupedTickets,
  useLinkDependency,
  useMe,
  usePatchTicket,
  usePreferences,
  useProjects,
  useSyncStatus,
  useTeams,
  useTickets,
  useUnlinkDependency,
} from "@/lib/queries";
import { ZOOMS } from "@/lib/timeline-geometry";
import { hintFor, type Bindings } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import { useActionContext } from "@/lib/use-action-ctx";
import { useUi } from "@/store/ui";

/**
 * The filter box's predicate, written once.
 *
 * This file applies it twice — over the buckets the list draws, and over the flat rows the
 * chart and the board draw — and two copies of it in one file would be the drift
 * `board/view.tsx` warns about at its own call site, at much closer range.
 */
const matches = (needle: string) => (ticket: Ticket) =>
  ticket.title.toLowerCase().includes(needle) ||
  (ticket.identifier?.toLowerCase().includes(needle) ?? false);

/**
 * The ticket list, the board and the chart — three drawings of one scoped query.
 *
 * It used to build the application's chrome inline as well: the 248px grid, the sidebar,
 * the mobile drawer, the auth gates, every overlay and five dialogs. All of that is
 * `app/(app)/layout.tsx`'s now, which is what makes this file the list again rather than
 * the list plus a shell two other shells were copied from.
 *
 * It no longer owns the keyboard either. It kept its own `keydown` through slice 1
 * because that handler also drove the inline rename, the dependency picker and `⇧↵`,
 * "none of which the registry can express while a shortcut is one bare
 * `KeyboardEvent.key`". Chords express two of the three and `usePageActions` supplies the
 * bodies the registry cannot hold, so `use-shell-keys.ts` is now the app's only
 * dispatcher and `ownsKeyboard` is gone. What is left here is what a *page* has to
 * decide: which ticket is being renamed, which arrow the palette is asking about, and
 * where `⇧↵` navigates.
 */
export default function ListPage() {
  const router = useRouter();
  const me = useMe();
  const preferences = usePreferences();
  // The reader's own keyboard, for the status bar. The dispatcher reads the same merge
  // from the same hook, so the strip cannot advertise a key the shell does not answer.
  const { keys } = useBindings();

  const {
    scope,
    selectedId,
    view: storedView,
    zoom,
    // `overlay` alone, where this used to read `dialog` beside it: the handler that stood
    // itself down for either of them is the shell's now, and all this page still asks is
    // whether the palette is the thing on screen — which is what tells it whether the
    // picker it opened is still the question being asked.
    overlay,
    query,
    setScope,
    select,
    setView,
    setZoom,
    open,
    close,
    setQuery,
  } = useUi();

  /**
   * Which of the three drawings is on screen, which is not always the one the reader
   * picked.
   *
   * A phone gets the list, whatever the store holds. The board scrolls sideways by
   * construction — one column per status, none of them narrower than a card — and the
   * timeline is an axis of dates that has no useful zoom at 390px; both are named in
   * `Kanso - Mobile.dc.html` as screens that "renvoient vers le bureau". The stored
   * choice is left exactly as it was rather than reset, so the same session opened on a
   * laptop is still on the board the reader put it on.
   *
   * Derived rather than pushed through `setView`: writing the store from a media query
   * would make a phone silently change what a desktop window shows next, and there would
   * be no way back to the board for a reader who rotated a tablet.
   */
  const narrow = useNarrow();
  const view = narrow ? "list" : storedView;

  const [editingId, setEditingId] = useState<string | undefined>();
  /**
   * The ticket whose arrows the palette is asking about, and which question it is
   * asking. Page-local rather than in the store: it lives exactly as long as the overlay
   * it re-labels, and it is this page that publishes the palette's rows.
   */
  const [picker, setPicker] = useState<{
    kind: "link" | "unlink" | "priority";
    ticketId: string;
  }>();

  const teams = useTeams();
  /**
   * The two readings of one question, and never both at once.
   *
   * The list draws the stacked answer — that is what puts a true count on a group header
   * — while the board and the chart draw the flat one, so each view enables exactly the
   * door it renders. Holding both would be two fetches of one question that can answer
   * differently: a ticket edited between them lands in one and not the other, which is
   * the reason `useViewGroups` gives for replacing the flat call on screen 21 rather than
   * sitting beside it. `BoardView` asks `useTickets` for itself and keys the same entry,
   * so the board still pays for one fetch and not two.
   */
  const tickets = useTickets(view !== "list");
  const groups = useGroupedTickets(view === "list");
  const projects = useProjects();
  const sync = useSyncStatus();

  const patch = usePatchTicket();
  const link = useLinkDependency();
  const unlink = useUnlinkDependency();

  /**
   * The two writes the picker performs, taken as bare functions.
   *
   * `useMutation` hands back a fresh result object on every render, so the mutations
   * themselves cannot be dependencies of the [commands] memo below: the list would be a
   * new array on every render, `usePageShell` publishes on a change of identity, and
   * publishing is a `setState` in the shell — which renders this page again, which builds
   * another array. That is a loop with no exit, and pressing `d` on the timeline reached
   * it: React error #185, the error boundary, "This page couldn’t load". `mutate` is the
   * one part of a mutation result that keeps its identity across renders, which is why it
   * is what the memo is allowed to close over.
   */
  const linkMutate = link.mutate;
  const unlinkMutate = unlink.mutate;

  /** The strip under the top bar, which the shell draws and every route now shares. */
  const reportError = useReportError();

  /**
   * The box stays here, and stays a contains-match over the page — deliberately, now that
   * grouping and counting have gone to the server for being wrong at volume.
   *
   * It is not the same kind of question. A chip narrows the *answer*, so answering it over
   * a page gives a page of the wrong answer; this narrows what is *on screen* to get the
   * eye to a row the reader can already see is there, which is why it is a keystroke with
   * no round trip and why the comment below can say its job is to find a row rather than
   * to hide a plan. Making it a facet would also be a promise this schema cannot keep
   * cheaply: `ILIKE '%…%'` is a sequential scan on every keystroke, and doing it honestly
   * means `pg_trgm` and a GIN index — a migration, and a ticket of its own. Until then the
   * honest reading of the box is "search what is loaded", which is what it does.
   */
  const shown = useMemo(() => {
    /*
     * Named here and counted nowhere: `nameGroups` puts a reader's word on each of the
     * server's bucket keys and touches neither the order they arrived in nor the numbers
     * on them.
     *
     * A `status` resolver, because a status stopped naming itself in `KAN-28`: the word
     * is the team's, and across teams the bucket is a category. `bucketLabel` reads the
     * same scope the server bucketed by, so a header always names a bucket that is
     * actually there. The day a stacking control lands on the list, an assignee and a
     * project are ids and this is where those two lookups come in — a header reading a
     * UUID is what a missing one looks like.
     */
    const named = nameGroups(groups.data?.groups ?? [], groups.data?.groupBy ?? "status", {
      status: (key) => bucketLabel(teams.data ?? [], scope, key),
    });
    const needle = query.trim().toLowerCase();
    if (!needle) return named;
    return named
      .map((group) => ({ ...group, tickets: group.tickets.filter(matches(needle)) }))
      // A bucket the needle emptied is dropped rather than left as a caption over
      // nothing. With no needle typed, a header above no rows means "these are one
      // scroll away", which is true and worth drawing; while somebody is typing it would
      // mean the opposite, and `Done · 12` over a gap reads as a broken list.
      .filter((group) => group.tickets.length > 0);
  }, [groups.data, query, teams.data, scope]);

  /**
   * The list flattened back out, for everything that walks it rather than draws it — the
   * cursor, the palette, the action context. It is the same rows in the order the server
   * stacked them, so `j` still runs straight down the screen through the headers.
   */
  const filtered = useMemo(() => shown.flatMap((group) => group.tickets), [shown]);

  /**
   * The same box over the flat door, for the two views that draw that one.
   *
   * Not derivable from [shown]: the grouped query is switched off while the board or the
   * chart is up, so a board cursor read out of it would be a cursor over nothing.
   */
  const flatFiltered = useMemo(() => {
    const rows = tickets.data ?? [];
    const needle = query.trim().toLowerCase();
    return needle ? rows.filter(matches(needle)) : rows;
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
        : // Three views, two doors: only the list reads the stacked answer, and the board
          // reads the flat one it renders its own columns from.
          view === "board"
          ? flatFiltered
          : filtered,
    [view, tickets.data, flatFiltered, filtered],
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
   *
   * It stays an `<h1>` in the top bar rather than becoming the shell's breadcrumb, because
   * the two say different things: a crumb is a trail to somewhere, and this is the subject
   * of the page. `breadcrumbOf` answers with nothing at `/` for exactly that reason.
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

  /**
   * The palette is one overlay with three lists, so leaving it has to put the ordinary
   * one back — otherwise ⌘K afterwards would still be asking about a dependency.
   *
   * Read rather than cleared, now that the overlay is mounted by the shell and this page
   * no longer owns every way of closing it. Whatever closed it — `Escape`, the backdrop,
   * a command that ran — the question is over once the overlay is off screen, so this is
   * a fact about what is drawn and not a second piece of state to keep in step with it.
   * `startLink` and `startUnlink` overwrite the value before reopening, so the one left
   * behind is never read again.
   */
  const asking = overlay === "palette" ? picker : undefined;

  const ctx = useActionContext({
    tickets: visible,
    selected,
    move,
    startRename,
    startLink,
    startUnlink,
    reportError,
  });

  /**
   * The four gestures the registry cannot run, supplied by the page that can.
   *
   * This is what is left of a 60-line `keydown` handler. Each one needs something
   * `ActionContext` deliberately does not carry — the router, the palette's rows, the
   * store's `view` on the one route that draws all three drawings — and each is a real
   * registry action all the same, so `?` lists it and §6.5 can remap it. See
   * `lib/actions/claims.ts` for why this is a claim and not four more context fields.
   *
   * `⇧↵` is the exhibit. It could not be an action at all while a shortcut was one bare
   * key: `event.key` for Shift+Enter is `"Enter"`, the same string the panel's own `↵`
   * dispatches on. It is now `"Shift+Enter"` and the modifier is in the chord.
   */
  usePageActions({
    "ticket.openInPage": () => {
      if (selected) router.push(ticketHref(ticketAddress(selected)));
    },
    "ticket.priority.pick": () => {
      if (!selected) return;
      setPicker({ kind: "priority", ticketId: selected.id });
      open("palette");
    },
    "view.cycleDrawing": () => setView(VIEWS[(VIEWS.indexOf(view) + 1) % VIEWS.length]),
  });

  /**
   * The palette's rows, but only while it is being asked about an arrow.
   *
   * `undefined` the rest of the time, which is what lets the shell list the registry and
   * the teams itself — one assembly of that list rather than one per route. Asked for a
   * predecessor, the palette lists tickets instead of commands: same overlay, same
   * filtering, same keys, so `d` costs nobody a new mental model.
   */
  const commands = useMemo(() => {
    if (asking?.kind === "link") {
      return visible
        .filter((candidate) => candidate.id !== asking.ticketId)
        .map((candidate) => ({
          id: `timeline.link.${candidate.id}`,
          label: `Wait for ${candidate.identifier}: ${candidate.title}`,
          run: () => {
            linkMutate(
              { successorId: asking.ticketId, predecessorId: candidate.id },
              {
                // A cycle is a 409 naming the chain. It belongs on the screen the
                // arrow was drawn on, in the same strip every other refusal uses.
                onError: (error) => reportError(actionErrorMessage(error)),
                onSuccess: () => reportError(null),
              },
            );
            close();
          },
        }));
    }

    // The inverse list, in the same overlay. "Stop waiting for" against "Wait for", so
    // the two are legible as opposites rather than as two unrelated pickers.
    if (asking?.kind === "unlink") {
      const successorId = asking.ticketId;
      return predecessorsOf(ctx, successorId).map((predecessor) => ({
        id: `timeline.unlink.${predecessor.id}`,
        label: `Stop waiting for ${predecessor.identifier}: ${predecessor.title}`,
        run: () => {
          unlinkMutate(
            { successorId, predecessorId: predecessor.id },
            {
              // Nothing here is optimistic — freeing slack pulls nothing earlier — so a
              // refusal has no row snapping back to serve as its signal, and goes to the
              // strip every other failed action reports into.
              onError: (error) => reportError(actionErrorMessage(error)),
              onSuccess: () => reportError(null),
            },
          );
          close();
        },
      }));
    }

    /*
     * The third question, and the cheapest of the three: `⇧p` asks which priority, over
     * the five actions the registry already holds. Their `run` closes the palette itself,
     * so there is nothing to add but the label — which is the argument for asking here
     * rather than spending five more keys on a choice made once in a while.
     */
    if (asking?.kind === "priority") {
      return PRIORITY_ACTIONS.map((id) => {
        const action = actionById(id);
        return { id, label: action.label, run: () => action.run(ctx) };
      });
    }

    return undefined;
  }, [ctx, asking, visible, linkMutate, unlinkMutate, reportError, close]);

  usePageShell({ ctx, commands });

  return (
    <>
      <TopbarSlot>
        {/*
          * The heading and the count left this bar for the page below it — see the `<h1>`
          * further down. A team called "Plateforme & intégrations partenaires" had one
          * line here shared with a breadcrumb, four controls and a text box, so the one
          * word that says where you are was the first thing truncated. It has the page's
          * full width now, and wraps rather than ends in an ellipsis.
          *
          * What is left in the bar is what acts rather than what describes: the two view
          * toggles and `New`.
          */}
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

        {/* Hidden where two of its three buttons lead somewhere a phone does not go —
            `narrow` above is the same 720px, and it is what already forced the drawing
            back to the list. A toggle whose other two positions are refused would be
            three buttons with one honest one. */}
        <div className="segmented narrow-hidden" role="group" aria-label="View">
          <button
            type="button"
            aria-pressed={storedView === "list"}
            onClick={() => setView("list")}
          >
            List
          </button>
          <button
            type="button"
            aria-pressed={storedView === "board"}
            onClick={() => setView("board")}
          >
            Board
          </button>
          <button
            type="button"
            aria-pressed={storedView === "timeline"}
            onClick={() => setView("timeline")}
          >
            Timeline
          </button>
        </div>

        <NewMenu ctx={ctx} />
      </TopbarSlot>

      {/*
        * Where the list says what it is, at the top of the page rather than in the bar.
        *
        * `text-15` and free to wrap: this is the one place a team or a project name is
        * written out in full, and the names that most need reading — the long ones — are
        * exactly the ones the bar could not hold. The count rides with it because it is a
        * fact about this heading and nothing else.
        */}
      <div className="flex items-baseline gap-2.5 px-6 pt-4 max-[720px]:px-4 max-[720px]:pt-3">
        <h1 className="m-0 min-w-0 text-15 font-medium text-foreground">{heading}</h1>
        {/* The whole match while the list is up and nothing is typed, which is a number
            only the server has: `visible.length` counts the *page*, so an instance with
            two thousand tickets read `200` here — the same lie about a fetch that KAN-6
            took off the group headers. With a needle typed it is the rows on screen,
            because that is the question the box asked and nobody asked the server. */}
        <span className="shrink-0 font-mono text-11 text-faint">
          {view === "list" && query.trim() === "" ? (groups.data?.total ?? 0) : visible.length}
        </span>
      </div>

      {/*
        * The composed filters, and the one control that adds one.
        *
        * Not on the chart. The timeline draws its own query, which these facets do not
        * reach: a strip of chips over a chart they are not narrowing would say the plan
        * had been filtered when it had not. `visible` in this file already documents the
        * same split for the cursor, and for the same reason.
        */}
      {view !== "timeline" && <ListFilters />}

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

      {view === "timeline" ? (
        <TimelineView reportError={reportError} />
      ) : view === "board" ? (
        <BoardView reportError={reportError} />
      ) : groups.error ? (
        <div className="empty error">{(groups.error as Error).message}</div>
      ) : (
        <TicketList
          // `shown`, not `groups.data.groups`: these are the buckets already named and
          // already narrowed by the filter box, which is the thing the box was written
          // for. The counts inside them are still the server's.
          groups={shown}
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
              // The server's total, not the page's length: this is the number that decides
              // whether the sentence blames the filter box or says the scope is empty, and
              // a page that came back empty because the offset ran off the end would have
              // made it blame the wrong one.
              total={groups.data?.total ?? 0}
              filter={query}
              ticketsAnywhere={(teams.data ?? []).reduce((sum, team) => sum + team.ticketCount, 0)}
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
          {/*
            * Ten keys, and until this slice all ten were string literals — the only
            * key-printing surface in the app that did not read the registry. After §6.4
            * dropped `j` and `k` it would have gone on advertising them, and after §6.5
            * it would have advertised a keyboard the reader had personally changed. Every
            * entry now names an *action* and asks `hintFor` what reaches it, which is the
            * same function the `?` sheet and every row menu ask.
            */}
          <Keys keys={keys} ids={["ticket.moveDown", "ticket.moveUp"]}>move</Keys>
          {/* Absent for a read-only seat. The keys are already inert for them —
              `permits` sees to that — and a strip that advertises keys that do
              nothing teaches the wrong thing about the product on every screen. */}
          {ctx.canWrite && (
            <>
              <Keys
                keys={keys}
                ids={["ticket.status.backlog", "ticket.status.canceled"]}
                join="–"
              >
                status
              </Keys>
              <Keys keys={keys} ids={["ticket.create"]}>new</Keys>
            </>
          )}
          {/* The chart's keys are not guessable and are worth one line while it is
              on screen; `?` lists them all, grouped by the view they belong to. */}
          {view === "timeline" && (
            <>
              <Keys keys={keys} ids={["timeline.shiftEarlier", "timeline.shiftLater"]}>
                move
              </Keys>
              <Keys keys={keys} ids={["timeline.zoomOut", "timeline.zoomIn"]}>zoom</Keys>
              <Keys keys={keys} ids={["timeline.today"]}>today</Keys>
              <Keys keys={keys} ids={["timeline.link"]}>depends on</Keys>
            </>
          )}
          <Keys keys={keys} ids={["app.palette"]}>commands</Keys>
          <Keys keys={keys} ids={["app.settings"]}>settings</Keys>
          <Keys keys={keys} ids={["app.help"]}>help</Keys>
          <span style={{ flex: 1 }} />
          {me.data && <DevUserSwitcher email={me.data.user.email} />}
        </div>
      )}
    </>
  );
}

/**
 * The three drawings, in the order `Mod+v` walks them.
 *
 * The same order the segmented control draws, so the key and the buttons agree about what
 * "next" means — and it is written here rather than in `store/ui.ts` because it is a fact
 * about this page's controls, not about the store's type.
 */
const VIEWS = ["list", "board", "timeline"] as const;

/**
 * One entry of the status bar: the keys an intention answers, then what it does.
 *
 * Prints nothing at all when the intention has no key — which is now possible, since a
 * reader may unbind one. An empty `<kbd>` box beside a word is worse than a shorter strip.
 */
function Keys({
  ids,
  keys,
  join = " ",
  children,
}: {
  ids: string[];
  keys: Bindings;
  join?: string;
  children: ReactNode;
}) {
  const mac = isMac();
  const printed = ids.flatMap((id) => hintFor(actionById(id), keys, mac) ?? []);
  if (printed.length === 0) return null;

  return (
    <span>
      {printed.map((chord, at) => (
        <Fragment key={chord}>
          {at > 0 && join}
          <kbd>{chord}</kbd>
        </Fragment>
      ))}{" "}
      {children}
    </span>
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
