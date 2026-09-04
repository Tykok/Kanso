import { useCallback, useLayoutEffect, type ReactNode } from "react";
import { render } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { ActionContext } from "@/lib/actions";
import {
  ShellChannelProvider,
  usePageShell,
  useShellChannel,
  type PageShell,
} from "./topbar-slot";

/**
 * The channel between a page and the shell, tested by rendering it.
 *
 * This is the first test in `apps/web` that mounts a component, and this file is the
 * reason: the React #185 loop fixed in `3cef443` was two pages handing the shell a fresh
 * array on every render, and it published, which re-rendered the shell, which re-rendered
 * the page, which built another array. Nine e2e specs died on it behind a "This page
 * couldn't load" boundary. All 988 unit tests passed with the crater in place, because not
 * one of them rendered anything.
 *
 * What is asserted is therefore not appearance but *identity*: how many distinct
 * statements the shell hears for one page. A loop is a count that keeps rising, and it is
 * visible here in a millisecond instead of a docker build and two minutes.
 *
 * `topbar-slot.tsx` reaches for no router, no store and no query client, which is what
 * lets this file mock nothing at all.
 */

/** Identity is the whole subject, so the contexts are bare objects wearing the type. */
const FIRST = {} as ActionContext;
const SECOND = {} as ActionContext;

/**
 * Everything the shell heard, one entry per commit.
 *
 * Recorded from a layout effect rather than during the render, so a render React discards
 * is not counted as something the shell was told.
 */
function Shell({ heard, children }: { heard: PageShell[]; children: ReactNode }) {
  const reportError = useCallback(() => {}, []);
  const { channel, page } = useShellChannel(reportError);
  useLayoutEffect(() => {
    heard.push(page);
  });
  return <ShellChannelProvider value={channel}>{children}</ShellChannelProvider>;
}

/**
 * A page as they are actually written: `ctx` memoised, `crumbs` an object literal.
 *
 * The literal is the point. `usePageShell` depends on the three crumb *names* and not on
 * the object holding them, and this is the only place that says so — put `crumbs` in that
 * dependency list and the second test below stops settling.
 *
 * `nonce` is a prop this component ignores: it is how a test forces a real re-render
 * without touching a single input `usePageShell` reads.
 */
function Page({ ctx }: { ctx: ActionContext; nonce: number }) {
  usePageShell({ ctx, crumbs: { team: "Core", project: "Onboarding", leaf: "KAN-142" } });
  return null;
}

/** `here` off is a route change and not the app closing: the shell has to outlive the page
 * for its withdrawal to be observable at all. */
function Tree({
  heard,
  ctx,
  nonce,
  here = true,
}: {
  heard: PageShell[];
  ctx: ActionContext;
  nonce: number;
  here?: boolean;
}) {
  return <Shell heard={heard}>{here ? <Page ctx={ctx} nonce={nonce} /> : null}</Shell>;
}

/** The distinct statements, in order. Re-renders the shell has for its own reasons show up
 * as repeats of the same object, and are not what any of this is about. */
const statements = (heard: PageShell[]) =>
  heard.filter((page, at) => at === 0 || page !== heard[at - 1]);

describe("the page-to-shell channel", () => {
  it("publishes once on mount, and the shell holds the page's own context", () => {
    const heard: PageShell[] = [];
    render(<Tree heard={heard} ctx={FIRST} nonce={1} />);

    // The empty statement the shell starts with, then the page's. Nothing else.
    expect(statements(heard)).toHaveLength(2);
    expect(statements(heard)[1].ctx).toBe(FIRST);
  });

  it("says nothing new when the page re-renders with the same inputs", () => {
    const heard: PageShell[] = [];
    const { rerender } = render(<Tree heard={heard} ctx={FIRST} nonce={1} />);
    rerender(<Tree heard={heard} ctx={FIRST} nonce={2} />);
    rerender(<Tree heard={heard} ctx={FIRST} nonce={3} />);

    // Still two: the page rendered three times and published on the first. This is the
    // assertion the render loop would have failed — as a count that never stops rising,
    // and then as React #185 once it passed fifty.
    expect(statements(heard)).toHaveLength(2);
    expect(statements(heard)[1].ctx).toBe(FIRST);
  });

  it("republishes when the context actually changes", () => {
    const heard: PageShell[] = [];
    const { rerender } = render(<Tree heard={heard} ctx={FIRST} nonce={1} />);
    rerender(<Tree heard={heard} ctx={SECOND} nonce={1} />);

    // Which is what keeps the two above from passing for the wrong reason: a hook that
    // published nothing, ever, would satisfy them both.
    expect(statements(heard).map((page) => page.ctx)).toEqual([undefined, FIRST, SECOND]);
  });

  it("withdraws the statement when the page leaves", () => {
    const heard: PageShell[] = [];
    const { rerender } = render(<Tree heard={heard} ctx={FIRST} nonce={1} />);
    rerender(<Tree heard={heard} ctx={FIRST} nonce={1} here={false} />);

    // The shell outlives the page, so a cursor, a picker or a claim on `Escape` left
    // behind here would be inherited by whatever route comes next.
    expect(heard[heard.length - 1].ctx).toBeUndefined();
  });
});
