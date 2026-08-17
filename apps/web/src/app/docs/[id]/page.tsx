"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { DocumentView } from "@/components/docs/document";
import { DocTree } from "@/components/docs/tree";
import { ApiError, api } from "@/lib/api";
import { useDocFolders, useDocPage, useDocPages, useMe, useTeams } from "@/lib/queries";

/**
 * Screen 07 — one document, at page width, with the tree beside it.
 *
 * A client component reading `useParams`: everything on the page is a query the rest of
 * the app already shares a cache with, and rendering the first paint on the server would
 * mean a second way of fetching a document.
 */
export default function DocPage() {
  const params = useParams<{ id: string }>();
  const id = typeof params.id === "string" ? params.id : "";

  const me = useMe();
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

  const signedOut = me.error instanceof ApiError && me.error.status === 401;
  if (signedOut) {
    return (
      <div className="centered">
        <span>Sign in to read this document.</span>
        <Link className="button" href="/login">
          Sign in
        </Link>
      </div>
    );
  }

  if (detail.isLoading) return <div className="centered">Loading…</div>;

  if (detail.error instanceof ApiError && detail.error.status === 404) {
    return (
      <div className="centered">
        <span>That document no longer exists.</span>
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
    <div className="grid min-h-screen grid-cols-1 sm:grid-cols-[248px_1fr]">
      <div className="hidden sm:flex sm:min-h-0 sm:flex-col">
        <DocTree
          folders={folders.data ?? []}
          pages={pages.data ?? []}
          currentPageId={detail.data.page.id}
        />
      </div>

      <DocumentView
        detail={detail.data}
        people={people.data ?? []}
        teamTickets={teamTickets.data ?? []}
        editable={editable}
      />
    </div>
  );
}
