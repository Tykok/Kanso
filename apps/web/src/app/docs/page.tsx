"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useMemo, useState } from "react";
import { DocTree } from "@/components/docs/tree";
import { RecentlyChanged } from "@/components/docs/recent";
import { TemplateCards } from "@/components/docs/templates";
import { ApiError, api, type DocTemplate } from "@/lib/api";
import {
  useCreateDocFolder,
  useCreateDocPage,
  useDocFolders,
  useDocPages,
  useDocTemplates,
  useMe,
  useTeams,
} from "@/lib/queries";
import { useQuery } from "@tanstack/react-query";
import { useUi } from "@/store/ui";

/**
 * Screen 22 — the tree, the three templates, and what changed recently.
 *
 * Scoped to the team the sidebar has selected, and unscoped when it is showing every
 * team: reads are open, so "all documents" is a real answer here rather than a hole.
 * Writing needs a team, which is why the templates go quiet when the scope has none.
 */
export default function DocsPage() {
  const router = useRouter();
  const me = useMe();
  const teams = useTeams();
  const scope = useUi((state) => state.scope);

  const scopedTeamId = scope.kind === "team" ? scope.id : undefined;
  const folders = useDocFolders(scopedTeamId);
  const pages = useDocPages(scopedTeamId);
  const templates = useDocTemplates();
  const people = useQuery({ queryKey: ["users"], queryFn: api.users });

  const createPage = useCreateDocPage();
  const createFolder = useCreateDocFolder();
  const [error, setError] = useState<string | null>(null);

  /**
   * Where a new page goes when the reader has not said. The scoped team if there is one,
   * otherwise the first team they may write in — the server's own `editable`, never a
   * membership re-derived here.
   */
  const writableTeamId = useMemo(() => {
    const list = teams.data ?? [];
    if (scopedTeamId) return list.find((team) => team.id === scopedTeamId && team.editable)?.id;
    return list.find((team) => team.editable && !team.archived)?.id;
  }, [teams.data, scopedTeamId]);

  const signedOut = me.error instanceof ApiError && me.error.status === 401;
  if (signedOut) {
    return (
      <div className="centered">
        <span>Sign in to read the team&apos;s documents.</span>
        <Link className="button" href="/login">
          Sign in
        </Link>
      </div>
    );
  }

  const start = (template?: DocTemplate) => {
    if (!writableTeamId) return;
    setError(null);
    createPage.mutate(
      {
        teamId: writableTeamId,
        title: template ? template.name : "Untitled",
        templateSlug: template?.slug,
      },
      {
        onSuccess: (detail) => router.push(`/docs/${detail.page.id}`),
        onError: (cause) =>
          setError(cause instanceof ApiError ? cause.detail : "Could not create the page"),
      },
    );
  };

  const newFolder = () => {
    if (!writableTeamId) return;
    const name = window.prompt("Folder name");
    if (!name?.trim()) return;
    createFolder.mutate(
      { teamId: writableTeamId, name: name.trim() },
      {
        onError: (cause) =>
          setError(cause instanceof ApiError ? cause.detail : "Could not create the folder"),
      },
    );
  };

  return (
    <div className="grid min-h-screen grid-cols-1 sm:grid-cols-[288px_1fr]">
      <DocTree
        folders={folders.data ?? []}
        pages={pages.data ?? []}
        onNewFolder={writableTeamId ? newFolder : undefined}
      />

      <main className="flex min-w-0 flex-col">
        <header className="flex items-center gap-2.5 bg-card px-6 py-3 text-12 text-faint">
          <Link href="/" className="hover:text-foreground">
            Tickets
          </Link>
          <span aria-hidden>/</span>
          <h1 className="text-12 font-normal text-muted-foreground">Documents</h1>
          <span className="flex-1" />
          <button
            className="button"
            disabled={!writableTeamId || createPage.isPending}
            onClick={() => start()}
          >
            New page
          </button>
        </header>

        {error && (
          <div className="topbar-error error">
            <span>{error}</span>
            <button onClick={() => setError(null)}>Dismiss</button>
          </div>
        )}

        <div className="flex flex-col gap-7 px-8 py-6.5">
          <TemplateCards
            templates={templates.data ?? []}
            disabled={!writableTeamId || createPage.isPending}
            onChoose={start}
          />
          <RecentlyChanged
            pages={pages.data ?? []}
            folders={folders.data ?? []}
            people={people.data ?? []}
          />
        </div>
      </main>
    </div>
  );
}
