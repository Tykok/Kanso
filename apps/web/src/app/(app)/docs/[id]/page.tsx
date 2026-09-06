"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect } from "react";
import { useQuery } from "@tanstack/react-query";
import { DocumentView } from "@/components/docs/document";
import { DocTree } from "@/components/docs/tree";
import { ShellAside, usePageShell } from "@/components/shell/topbar-slot";
import { ApiError, api } from "@/lib/api";
import { useDocFolders, useDocPage, useDocPages, useMe, useTeams } from "@/lib/queries";
import { useDocsUi } from "@/store/docs";

/**
 * Screen 07 — one document, at page width, with the tree beside it.
 *
 * A client component reading `useParams`: everything on the page is a query the rest of
 * the app already shares a cache with, and rendering the first paint on the server would
 * mean a second way of fetching a document.
 *
 * This was the worst of the four shell-less routes. It had no sidebar, and — unlike the
 * other three — not even a `Back` link, so a reader who followed a mention into a
 * document had no way out of it but the browser's own button. The shell gives it the
 * column, `Documents / <title>` in the bar, and the `×` that `esc` also runs.
 */
export default function DocPage() {
  const params = useParams<{ id: string }>();
  const id = typeof params.id === "string" ? params.id : "";

  const teams = useTeams();
  const detail = useDocPage(id);
  const teamId = detail.data?.page.teamId;

  const folders = useDocFolders(teamId);
  const pages = useDocPages(teamId);
  const people = useQuery({ queryKey: ["users"], queryFn: api.users });

  /**
   * The team's tickets: what `#` offers, and what a table block carrying a query reads.
   * `enabled` so a document nobody is editing costs no ticket query.
   */
  const teamTickets = useQuery({
    queryKey: ["tickets", "team", teamId ?? "", false],
    queryFn: () => api.tickets({ kind: "team", id: teamId as string }),
    enabled: teamId !== undefined,
  });

  // `Documents / Cycle 22 notes`. The index is a crumb here, unlike on the team-scoped
  // routes, because `/docs` is a page a reader can actually climb to from this one.
  usePageShell({ crumbs: { leaf: detail.data?.page.title } });

  const me = useMe();

  /**
   * Announcing this reader on the page — `KAN-25`.
   *
   * Setting the id is what puts the page's viewers topic into `topicsFor`, and the
   * *subscription* is what the server counts as presence: there is no announce call, so
   * there is none to forget. The cleanup is not tidiness for the same reason — a page left
   * set here is somebody who never left, in everybody else's roster.
   *
   * Keyed on the raw route id rather than on the loaded document, so presence begins with
   * the navigation instead of a round trip later. Subscribing to a page that turns out to
   * be a 404 costs a topic nobody publishes to.
   */
  const setOpenDocPage = useDocsUi((state) => state.setOpenDocPage);
  useEffect(() => {
    setOpenDocPage(id || undefined);
    return () => setOpenDocPage(undefined);
  }, [id, setOpenDocPage]);

  if (detail.isLoading) return <div className="centered">Loading…</div>;

  if (detail.error instanceof ApiError && detail.error.status === 404) {
    return (
      <div className="centered">
        <span>That document no longer exists.</span>
        {/* Kept where the four `Back` links were not: this one names a real parent and is
            the honest thing to offer somebody whose link has gone stale. */}
        <Link className="button" href="/docs">
          Back to documents
        </Link>
      </div>
    );
  }

  if (!detail.data) return <div className="centered">{detail.error ? "Could not load it." : "Loading…"}</div>;

  // Reads are open; writing is the server's call. `editable` on the team is exactly the
  // answer `TicketAccess` would give, so nothing here re-derives a membership rule.
  const editable = (teams.data ?? []).some(
    (team) => team.id === detail.data.page.teamId && team.editable && !team.archived,
  );

  return (
    <>
      {/* The same rail `/docs` publishes, and the same cancelled padding — see the note
          there. `currentPageId` is the one difference: on the index nothing is open. */}
      <ShellAside>
        <div className="-mx-2 -my-3 flex min-h-0 flex-col">
          <DocTree
            folders={folders.data ?? []}
            pages={pages.data ?? []}
            currentPageId={detail.data.page.id}
          />
        </div>
      </ShellAside>

      <DocumentView
        detail={detail.data}
        people={people.data ?? []}
        teamTickets={teamTickets.data ?? []}
        editable={editable}
        meId={me.data?.user.id}
      />
    </>
  );
}
