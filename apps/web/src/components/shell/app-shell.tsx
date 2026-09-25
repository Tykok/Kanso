"use client";

import { useCallback, useEffect, useRef, useState, type ReactNode } from "react";
import { usePathname, useRouter } from "next/navigation";
import { BrandSplash } from "@/components/brand-logo";
import { LoginScreen } from "@/components/login";
import { OfflineBanner } from "@/components/offline/banner";
import { useOfflineWatch } from "@/components/offline/watch";
import { ApiError } from "@/lib/api";
import { breadcrumbOf, sameScope } from "@/lib/nav";
import { useAuthMode, useMe, useSetupState } from "@/lib/queries";
import { useActionContext } from "@/lib/use-action-ctx";
import { cn } from "@/lib/utils";
import { useUi, type Scope } from "@/store/ui";
import { ShellOverlays } from "./overlays";
import { SidebarFrame, useSidebarPinned } from "./sidebar-frame";
import { Topbar } from "./topbar";
import { ShellChannelProvider, useShellChannel } from "./topbar-slot";
import { useShellKeys } from "./use-shell-keys";

/**
 * The application's frame, once.
 *
 * There were three of these — `app/page.tsx` inline, `OrganiseShell`, `ViewsShell` — and
 * four routes with none at all. `/trash`, `/settings`, `/docs` and `/docs/[id]` drew their
 * own page-width layout with a `Back` link, which is the whole of "quand je clique sur
 * Trash la sidebar disparaît": nothing disappeared, the destination never had one.
 *
 * The three shells were frozen deliberately, for a six-branch fan-out, and `OrganiseShell`
 * said in its own docstring that promoting it to an `app/(app)/layout.tsx` was "worth
 * doing in the integration pass, not from a branch that cannot see the other five". This
 * is that pass, and this is that layout.
 *
 * What it holds is only what every route shares: the grid, the two auth gates, the error
 * strip, and the effect that keeps the scope and the address bar saying the same thing.
 * Everything a page has of its own reaches it through `topbar-slot.tsx`, because a layout
 * cannot take props.
 */
export function AppShell({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const authMode = useAuthMode();
  const me = useMe();
  const setup = useSetupState();
  const { scope, setScope } = useUi();
  const pinned = useSidebarPinned();
  // Here rather than on the one page that draws the banner: a write queued on the board
  // is a write this shell has promised to send. `offline/watch.ts` says the rest.
  useOfflineWatch();

  /**
   * A failure belongs to the view it happened in, so the scope it was reported against is
   * stored with it and a scope change simply stops it applying. Clearing it from an effect
   * instead would leave one render showing a sentence about a team nobody is looking at
   * any more.
   *
   * The scope is read off the store at call time rather than closed over, so that
   * [reportError] never changes identity: pages hand it to `useActionContext`, which
   * memoises on it, and a callback that changed on every scope change would rebuild every
   * action in the registry with it. `getState()` is what zustand offers for exactly this
   * — a read outside a render, of the value that is current when the failure happens.
   */
  const [failure, setFailure] = useState<{ scope: Scope; message: string } | null>(null);
  const reportError = useCallback(
    (message: string | null) =>
      setFailure(message === null ? null : { scope: useUi.getState().scope, message }),
    [],
  );
  const shownError = failure && sameScope(failure.scope, scope) ? failure.message : null;

  const { channel, page, hasAside, setTopbarNode, setAsideNode } = useShellChannel(reportError);

  /**
   * The context for the chrome: the column's `+` buttons and row menus, the palette, the
   * keyboard. Inert on purpose — no rows, no cursor, no selection — because the routes
   * that draw a list publish their own, and the ones that do not have nothing for a
   * ticket action to act on. `OrganiseShell` built exactly this, for exactly this reason,
   * and its comment is worth keeping: `move` and `startRename` describe a cursor over
   * rows, and a cycle report has no cursor.
   */
  const noop = useCallback(() => {}, []);
  const inert = useActionContext({
    tickets: [],
    selected: undefined,
    move: noop,
    startRename: noop,
    startLink: noop,
    startUnlink: noop,
    reportError,
  });
  const ctx = page.ctx ?? inert;

  const { leave } = useShellKeys({ ctx, page });

  /**
   * An instance without an owner has nothing to show, and an account with no onboarding
   * stamp is sent to `/setup` too — every account-creation path stamps it now (claiming,
   * OIDC, the dev-header filter, accepting an invitation), so reaching here unstamped means
   * a row that predates one of those stamps. Both answers come from the setup endpoint: on
   * a backend that predates the wizard it 404s, and pushing anyone towards a route that
   * does not exist there is worse than a working list.
   */
  const needsSetup =
    setup.data !== undefined &&
    (setup.data.needsOwner || (me.data !== undefined && !me.data.preferences.onboardedAt));

  useEffect(() => {
    if (needsSetup) router.replace("/setup");
  }, [needsSetup, router]);

  /**
   * The scope, and the address bar, kept saying the same thing.
   *
   * Sixty-five files read `useUi().scope`, and converting them all to read the URL is a
   * different task with a different risk profile — so the store stays the source and the
   * address mirrors it. That buys back the two things the missing URL cost: a reload keeps
   * the scope, and a pasted link says what it shows. The second is the property `?team=`
   * was bolted onto `useOrganiseTeam` to get.
   *
   * One effect with two halves rather than two effects, because the halves are ordered and
   * that order is the only subtle thing here: read the address *before* writing it, or
   * arriving at `/?team=core` would clear the parameter on the first pass and put it back
   * on the second.
   *
   * `window.location` rather than `useSearchParams`, which would make every page in the
   * group need a `Suspense` boundary for the shell's sake; and `replaceState` rather than
   * `router.replace`, because picking a team is not a navigation and a real one would
   * re-run the route and refetch its payload on every click in the column. Next syncs its
   * own router with the native call, which keeps `useSearchParams` honest for the pages
   * that do read it.
   */
  const arrived = useRef(false);
  useEffect(() => {
    if (!arrived.current) {
      arrived.current = true;
      const params = new URLSearchParams(window.location.search);
      const team = params.get("team");
      const project = params.get("project");
      // An id naming nothing is adopted anyway rather than validated here: the teams have
      // not necessarily loaded, and every screen already falls back readably on a scope it
      // cannot resolve. Checking would need a query the shell has no business owning.
      if (team) setScope({ kind: "team", id: team });
      else if (project) setScope({ kind: "project", id: project });
    }

    if (pathname !== "/") return;
    // Off the store, not out of the closure: the half above may have just changed it, and
    // this render's `scope` predates that.
    const current = useUi.getState().scope;
    const query = current.kind === "all" ? "" : `?${current.kind}=${current.id}`;
    if (window.location.search === query) return;
    window.history.replaceState(null, "", `/${query}`);
  }, [pathname, scope, setScope]);

  if (me.isLoading || authMode.isLoading || setup.isLoading) {
    return <BrandSplash label="Loading…" />;
  }

  // Ahead of the sign-in screen: with no owner yet there is nobody to sign in as.
  if (needsSetup) return <BrandSplash label="Opening setup…" />;

  /**
   * One sign-in screen for the whole group.
   *
   * `OrganiseShell` redirected to `/` instead, and the four shell-less routes redirected
   * to `/login` from an effect — three answers to one question, two of which took the
   * reader off the page they had asked for. Rendering it in place means signing in returns
   * them to the address they arrived at.
   */
  if (me.error instanceof ApiError && me.error.status === 401) {
    return <LoginScreen mode={authMode.data} />;
  }

  return (
    <ShellChannelProvider value={channel}>
      <div
        className={cn(
          "grid h-screen max-[720px]:grid-cols-[1fr]",
          pinned ? "grid-cols-[248px_1fr]" : "grid-cols-[1fr]",
        )}
      >
        <SidebarFrame ctx={ctx} />

        <div className="flex min-h-0 min-w-0 flex-col">
          <Topbar
            ctx={ctx}
            crumbs={breadcrumbOf(pathname, page.crumbs)}
            slotRef={setTopbarNode}
            onLeave={leave}
            canLeave={pathname !== "/"}
          />

          {shownError && (
            <div className="topbar-error error" role="alert">
              <span>{shownError}</span>
              <button
                type="button"
                aria-label="Dismiss this message"
                title="Dismiss"
                onClick={() => setFailure(null)}
              >
                ×
              </button>
            </div>
          )}

          {/*
            * The writes this reader made that the network could not carry, wherever they
            * are standing — `KAN-88`. It drew on the inbox alone, which was the only
            * screen that could queue anything until ticket patches went through the same
            * queue; a status changed on the board would have shown as saved and said
            * nothing. Draws nothing at all when the queue is empty, which is normally.
            *
            * Beside the error strip rather than inside the page column: both are the
            * shell speaking about the session, not about the screen.
            */}
          <OfflineBanner />

          {/*
            * The rail, and the page beside it.
            *
            * Both branches keep `children` in the same position in this element's child
            * list. They have to: a rail announces itself from an effect *inside* the page,
            * so moving the page when the column appears would unmount the thing that
            * asked for it, which would take the column away again, forever.
            */}
          <div
            className={cn(
              "grid min-h-0 flex-1",
              hasAside ? "grid-cols-[240px_1fr] max-[720px]:grid-cols-[1fr]" : "grid-cols-[1fr]",
            )}
          >
            {hasAside && (
              <div
                ref={setAsideNode}
                className="flex min-h-0 flex-col gap-1 overflow-y-auto bg-card px-2 py-3 max-[720px]:hidden"
              />
            )}
            <div className="flex min-h-0 min-w-0 flex-col">{children}</div>
          </div>
        </div>

        <ShellOverlays ctx={ctx} commands={page.commands} />
      </div>
    </ShellChannelProvider>
  );
}
