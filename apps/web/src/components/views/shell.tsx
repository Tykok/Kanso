"use client";

import { useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useState } from "react";
import { availableActions, hintOf, resolveShortcut } from "@/lib/actions";
import { ApiError, type Ticket } from "@/lib/api";
import { isMac } from "@/lib/platform";
import { useAuthMode, useMe, usePreferences, useSyncStatus } from "@/lib/queries";
import { useActionContext } from "@/lib/use-action-ctx";
import { cn } from "@/lib/utils";
import { useUi } from "@/store/ui";
import { BrandSplash } from "../brand-logo";
import { CommandPalette } from "../command-palette";
import { Composer } from "../composer";
import { DetailPanel } from "../detail-panel";
import { DispositionDialog } from "../dialogs/disposition-dialog";
import { ProjectDialog } from "../dialogs/project-dialog";
import { TeamDialog } from "../dialogs/team-dialog";
import { HelpOverlay } from "../help-overlay";
import { LoginScreen } from "../login";
import { SettingsPanel } from "../settings/panel";
import { Sidebar } from "../sidebar";

/**
 * The chrome screens 03 and 05 sit in.
 *
 * `app/page.tsx` builds this inline for the list, the board and the chart, and it is
 * frozen for the fan-out — so the two routes slice A adds cannot reuse it and must not
 * edit it. This is the smaller half of it: the sidebar, the overlays, and the keyboard.
 * Deliberately not everything the page has — there is no filter box, no view control and
 * no cursor to move, because a page showing one record has nothing to filter or step
 * through.
 *
 * `onNavigate` is why the sidebar is safe here. Its scope rows only call `setScope`; on a
 * route that is not the list, a changed scope with nobody drawing it would leave the
 * reader on this page wondering what their click did. Pushing `/` is what makes picking a
 * destination mean going there, which is the prop's documented purpose.
 */
export function ViewsShell({
  tickets,
  selected,
  footer,
  children,
}: {
  /** What the registry may act on here. One ticket on 03, the project's rows on 05. */
  tickets: Ticket[];
  selected?: Ticket;
  /** The drawing's own footer strip; each screen names different keys. */
  footer?: React.ReactNode;
  children: React.ReactNode;
}) {
  const router = useRouter();
  const authMode = useAuthMode();
  const me = useMe();
  const preferences = usePreferences();
  const sync = useSyncStatus();
  const { overlay, dialog, scope, open, close } = useUi();
  const [error, setError] = useState<string | null>(null);

  const reportError = useCallback((message: string | null) => setError(message), []);
  const noop = useCallback(() => {}, []);

  const ctx = useActionContext({
    tickets,
    selected,
    // No cursor on either screen: 03 draws one record and 05 draws a summary, so `j`
    // and `k` have nothing to step through and say so by doing nothing.
    move: noop,
    startRename: noop,
    startLink: noop,
    startUnlink: noop,
    reportError,
  });

  /**
   * Escape leaves. Two meanings in one key, in the order the reader expects: it closes
   * whatever is open over the page first, and only takes them off the page when there is
   * nothing left to close. `router.back()` rather than `push("/")` — the ticket page is
   * reached from the list, the board, the palette and a link in a document, and going
   * back to where you came from is what "esc" promised on the screen you left. With no
   * history to go back into — a pasted link in a fresh tab — it lands on the list, because
   * a key that does nothing is indistinguishable from a key that is broken.
   */
  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      const typing =
        event.target instanceof HTMLElement &&
        ["INPUT", "TEXTAREA", "SELECT"].includes(event.target.tagName);

      if (overlay !== "none" || dialog.kind !== "none") {
        if (event.key === "Escape") close();
        return;
      }

      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        open("palette");
        return;
      }

      if (event.key === "Escape" && !typing) {
        event.preventDefault();
        if (window.history.length > 1) router.back();
        else router.push("/");
        return;
      }

      if (typing || event.metaKey || event.ctrlKey || event.altKey) return;

      // The same registry the list dispatches on, asked in `list` mode: these screens
      // draw records rather than a chart, so the chart's keys are not theirs to answer.
      const action = resolveShortcut(event.key, "list");
      if (!action || !action.when(ctx)) return;
      event.preventDefault();
      action.run(ctx);
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  }, [ctx, overlay, dialog, open, close, router]);

  const commands = useMemo(
    () =>
      availableActions(ctx).map((action) => ({
        id: action.id,
        label: action.label,
        hint: hintOf(action, isMac()),
        run: () => action.run(ctx),
      })),
    [ctx],
  );

  if (me.isLoading || authMode.isLoading) return <BrandSplash label="Loading…" />;
  if (me.error instanceof ApiError && me.error.status === 401) {
    return <LoginScreen mode={authMode.data} />;
  }

  const syncSummary = !sync.data
    ? ""
    : sync.data.mirrorEnabled
      ? `Notion: ${sync.data.bootstrapped ? "connected" : "not bootstrapped"}`
      : "Notion mirror off";

  return (
    <div
      className={cn(
        "grid h-screen max-[720px]:grid-cols-[1fr]",
        preferences.sidebarVisible ? "grid-cols-[248px_1fr]" : "grid-cols-[1fr]",
      )}
    >
      {preferences.sidebarVisible && (
        <div className="contents max-[720px]:hidden">
          <Sidebar ctx={ctx} syncSummary={syncSummary} onNavigate={() => router.push("/")} />
        </div>
      )}

      <div className="flex min-h-0 min-w-0 flex-col">
        {error && (
          <div className="topbar-error error" role="alert">
            <span>{error}</span>
            <button type="button" aria-label="Dismiss this message" onClick={() => setError(null)}>
              ×
            </button>
          </div>
        )}
        {children}
        {preferences.showStatusBar && footer && <div className="statusbar">{footer}</div>}
      </div>

      {overlay === "composer" && <Composer scope={scope} onClose={close} />}
      {overlay === "palette" && <CommandPalette commands={commands} onClose={close} />}
      {overlay === "help" && <HelpOverlay onClose={close} />}
      {overlay === "settings" && <SettingsPanel onClose={close} />}
      {/* `detail` is reachable from here: `↵` in the palette opens the panel when the
          preference says panel, and the reader may well be on a project page. */}
      {overlay === "detail" && selected && (
        <DetailPanel
          ticket={selected}
          projects={ctx.projects}
          onPatch={(body) => ctx.patchTicket({ id: selected.id, ...body })}
          onDelete={() => {
            ctx.deleteTicket(selected.id);
            router.push("/");
          }}
          onClose={close}
        />
      )}

      {/* The sidebar's `+` buttons and row menus open these. Rendered here because a
          control that sets a dialog nobody draws is a dead click, and the sidebar is
          the same component on this route as on the list. */}
      {dialog.kind === "team" && (
        <TeamDialog id={dialog.id} parentTeamId={dialog.parentTeamId} onClose={close} />
      )}
      {dialog.kind === "project" && (
        <ProjectDialog id={dialog.id} teamId={dialog.teamId} onClose={close} />
      )}
      {dialog.kind === "disposition" && (
        <DispositionDialog target={dialog.target} severity={dialog.severity} onClose={close} />
      )}
    </div>
  );
}
