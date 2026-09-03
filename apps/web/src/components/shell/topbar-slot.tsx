"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import { createPortal } from "react-dom";
import type { ActionContext } from "@/lib/actions";
import type { CrumbNames } from "@/lib/nav";
import type { PaletteCommand } from "@/components/search/results";

/**
 * How a page reaches the shell that wraps it.
 *
 * A layout cannot take props from the page it renders — that is the one thing a layout
 * gives up in exchange for surviving navigation — and the six things a page needs to say
 * are all the same kind of statement: *this is what I am, act on me.* So they go through
 * one channel rather than six, and this file is it.
 *
 * Two mechanisms, and which one a value uses is decided by whether it is markup:
 *
 *  - **A portal**, for the controls and the rail. `AppShell` renders an empty `<div>` in
 *    the top bar and another in the grid, publishes their nodes here, and `<TopbarSlot>`
 *    and `<ShellAside>` render their children into them. Markup cannot go through state:
 *    JSX is a fresh object on every render, so publishing it would re-render the shell,
 *    which re-renders the page, which builds fresh JSX — a loop with no exit.
 *  - **State**, for the values that compare equal: the action context (memoised by
 *    `useActionContext`), the palette's extra commands, the breadcrumb's leaf, and two
 *    flags. React bails out of a `setState` that is `Object.is`-equal, which is what
 *    keeps the same loop from forming here.
 *
 * The alternative — one shell per page, each taking props — is what was there before, and
 * what broke: three copies of the frame, none able to see the other two, and every fix to
 * a frame made three times or wrong twice.
 */

/** What a page tells the shell about itself. Every field is optional; most pages set none. */
export type PageShell = {
  /**
   * What the registry may act on here — the rows, the cursor, the callbacks that only the
   * page has. The shell holds an inert context of its own for the routes that draw no
   * list, and this replaces it where there is something to act on.
   */
  ctx?: ActionContext;
  /**
   * Rows the palette lists on top of the registry's own. One caller: the ticket list,
   * whose `d` and `D` turn the palette into a predecessor picker over its loaded rows.
   */
  commands?: PaletteCommand[];
  /**
   * The names only the page knows: the team it resolved, the project a ticket is filed
   * in, and the record's own title — `Core / Cycle 24`, `Core / Onboarding / KAN-142`.
   *
   * The route decides the *shape* of the breadcrumb, in `lib/nav.ts`; this fills in the
   * words a URL cannot carry. Absent until the page's queries land, which is why
   * `breadcrumbOf` has a name for every record ready before the real one arrives.
   */
  crumbs?: CrumbNames;
  /**
   * What `Escape` means on this page, if it means something of its own. Returns whether
   * it took the key: `false` lets the shell go on and leave the page, which is what
   * `Escape` does everywhere else.
   *
   * A claim rather than a race. A page could instead handle `Escape` in its own listener
   * and `preventDefault()`, but that only works if the page's listener runs first, and
   * `saved-view.tsx` re-registers its listener on every render — so registration order is
   * not a contract anybody can rely on. Read out of a ref at event time, order stops
   * existing as a question.
   */
  onEscape?: () => boolean;
};

type Channel = {
  page: PageShell;
  publish: (page: PageShell) => void;
  /** Where `<TopbarSlot>` and `<ShellAside>` render into, once the shell has drawn them. */
  topbarNode: HTMLElement | null;
  asideNode: HTMLElement | null;
  /** Told by `<ShellAside>` on mount, so the grid can drop the column when nobody wants one. */
  setHasAside: (has: boolean) => void;
  /** The one direction that runs the other way: where a page's failures are drawn. */
  reportError: (message: string | null) => void;
};

const ShellChannel = createContext<Channel | null>(null);

/**
 * Builds the channel and hands back both halves: the value to provide, and the page's
 * own statement for the shell to read.
 *
 * Called by `AppShell` and nowhere else. It lives here rather than there so that the
 * contract — what a page may say, and how it is carried — is readable in one file.
 */
export function useShellChannel(reportError: (message: string | null) => void): {
  channel: Channel;
  page: PageShell;
  hasAside: boolean;
  setTopbarNode: (node: HTMLElement | null) => void;
  setAsideNode: (node: HTMLElement | null) => void;
} {
  const [page, setPage] = useState<PageShell>({});
  const [topbarNode, setTopbarNode] = useState<HTMLElement | null>(null);
  const [asideNode, setAsideNode] = useState<HTMLElement | null>(null);
  const [hasAside, setHasAside] = useState(false);

  const publish = useCallback((next: PageShell) => setPage(next), []);
  const channel = useMemo<Channel>(
    () => ({ page, publish, topbarNode, asideNode, setHasAside, reportError }),
    [page, publish, topbarNode, asideNode, reportError],
  );

  return { channel, page, hasAside, setTopbarNode, setAsideNode };
}

export function ShellChannelProvider({
  value,
  children,
}: {
  value: Channel;
  children: ReactNode;
}) {
  return <ShellChannel.Provider value={value}>{children}</ShellChannel.Provider>;
}

/**
 * Throws rather than returning null: a component reaching for the shell outside it is a
 * routing mistake, and a silent no-op would show up as a control that does nothing.
 */
function useChannel(): Channel {
  const channel = useContext(ShellChannel);
  if (!channel) throw new Error("Outside <AppShell>: no shell to publish into.");
  return channel;
}

/**
 * A page's statement about itself, made once and withdrawn when it leaves.
 *
 * The fields are listed as dependencies rather than the object, because the object is a
 * literal at every call site and would fire this effect on every render. Every field a
 * page passes therefore has to be stable across renders — memoised, a callback, a string
 * or a boolean — which is the same discipline `useActionContext` already imposes.
 */
export function usePageShell({ ctx, commands, crumbs, onEscape }: PageShell): void {
  const { publish } = useChannel();
  const { team, project, leaf } = crumbs ?? {};

  /*
   * Before the paint, not after it — which is a correctness requirement and not a
   * flicker preference.
   *
   * `use-shell-keys.ts` dispatches every key in the application against the context this
   * publishes. As a passive effect, the publish landed a task *later* than the render it
   * described: press a key that moves the cursor and then one that writes, fast, and the
   * second ran against the context from before the first — the status of the row the
   * cursor had just left, written and answered 200 with nothing on screen to say so.
   *
   * A layout effect is flushed synchronously in the same commit, so the `setState` below
   * re-renders the shell before the browser can deliver another keystroke. The cost is
   * one synchronous render on a cursor move, which was already happening — one frame
   * later, which is exactly the frame the bug lived in.
   */
  useLayoutEffect(() => {
    publish({ ctx, commands, crumbs: { team, project, leaf }, onEscape });
    // Withdrawn on the way out: the next route must not inherit a cursor, a picker or a
    // claim on `Escape` from the page the reader has just left.
    return () => publish({});
    // The three crumb names rather than the object that holds them: `crumbs` is written
    // as a literal at every call site, so depending on it would fire this on every
    // render, and firing it re-renders the shell, which re-renders the page.
  }, [publish, ctx, commands, team, project, leaf, onEscape]);
}

/**
 * Where a failure with no dialog to land in goes: the strip under the top bar.
 *
 * A page builds its `ActionContext` with this, which is what makes `ctx.unarchive`'s
 * 403s and 409s visible. `OrganiseShell` wired that callback to a `useState` whose value
 * it then never rendered, so on four routes a refused unarchive was silent — a menu entry
 * that did nothing and said nothing.
 */
export function useReportError(): (message: string | null) => void {
  return useChannel().reportError;
}

/**
 * The page's own controls, in the shell's top bar.
 *
 * The List/Board/Timeline strip, the filter box, `New`, a date range, a count. Rendered
 * here so they land in the one bar the shell draws — which is what stops the app growing
 * a second bar under the first, as four routes had already started to.
 */
export function TopbarSlot({ children }: { children: ReactNode }) {
  const { topbarNode } = useChannel();
  return topbarNode ? createPortal(children, topbarNode) : null;
}

/**
 * The rail at the left of the content: the cycle list, the triage queue, the saved views,
 * the document tree.
 *
 * A portal for the markup and a boolean for the column. The shell cannot draw a 240px
 * column on the chance that a page might fill it — an empty rail on the ticket list would
 * be a fifth of the window given to nothing — so mounting says "there is one", and the
 * grid gains the column on the render after.
 */
export function ShellAside({ children }: { children: ReactNode }) {
  const { asideNode, setHasAside } = useChannel();

  // `setHasAside` is a `useState` setter, so its identity never changes even though the
  // channel object around it is rebuilt — which is what keeps this to one announcement on
  // mount and one withdrawal on the way out.
  useEffect(() => {
    setHasAside(true);
    return () => setHasAside(false);
  }, [setHasAside]);

  return asideNode ? createPortal(children, asideNode) : null;
}
