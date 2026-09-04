"use client";

import { useRouter } from "next/navigation";
import { useMemo } from "react";
import { DocTree } from "@/components/docs/tree";
import { RecentlyChanged } from "@/components/docs/recent";
import { TemplateCards } from "@/components/docs/templates";
import { ShellAside, TopbarSlot, useReportError } from "@/components/shell/topbar-slot";
import { ApiError, api, type DocTemplate } from "@/lib/api";
import {
  useCreateDocFolder,
  useCreateDocPage,
  useDocFolders,
  useDocPages,
  useDocTemplates,
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
 *
 * The header it used to draw read `Tickets / Documents`, with `Tickets` a link home. That
 * crumb is gone rather than moved: `/docs` is not a child of the ticket list, and a trail
 * naming a parent the reader cannot climb to invites exactly one click and then loses
 * their place. The shell says `Documents`, from the route.
 */
export default function DocsPage() {
  const router = useRouter();
  const teams = useTeams();
  const scope = useUi((state) => state.scope);
  const reportError = useReportError();

  const scopedTeamId = scope.kind === "team" ? scope.id : undefined;
  const folders = useDocFolders(scopedTeamId);
  const pages = useDocPages(scopedTeamId);
  const templates = useDocTemplates();
  const people = useQuery({ queryKey: ["users"], queryFn: api.users });

  const createPage = useCreateDocPage();
  const createFolder = useCreateDocFolder();

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

  // The "sign in to read the team's documents" branch that stood here is the shell's
  // gate now — one sign-in screen for every route in the group, rendered in place, so
  // signing in returns the reader to the address they asked for.
  const start = (template?: DocTemplate) => {
    if (!writableTeamId) return;
    reportError(null);
    createPage.mutate(
      {
        teamId: writableTeamId,
        title: template ? template.name : "Untitled",
        templateSlug: template?.slug,
      },
      {
        onSuccess: (detail) => router.push(`/docs/${detail.page.id}`),
        onError: (cause) =>
          reportError(cause instanceof ApiError ? cause.detail : "Could not create the page"),
      },
    );
  };

  const newFolder = (name: string) => {
    if (!writableTeamId) return;
    createFolder.mutate(
      { teamId: writableTeamId, name },
      {
        onError: (cause) =>
          reportError(cause instanceof ApiError ? cause.detail : "Could not create the folder"),
      },
    );
  };

  return (
    <>
      {/*
        * The tree is the shell's rail now, not a column this page draws. `-mx-2 -my-3`
        * cancels the rail's own padding, because `DocTree` brings its own frame where the
        * three organising rails are bare lists that rely on the container's — one of them
        * had to give, and taking the padding off the container would re-indent all three.
        */}
      <ShellAside>
        <div className="-mx-2 -my-3 flex min-h-0 flex-col">
          <DocTree
            folders={folders.data ?? []}
            pages={pages.data ?? []}
            onCreateFolder={writableTeamId ? newFolder : undefined}
          />
        </div>
      </ShellAside>

      <TopbarSlot>
        <span className="flex-1" />
        <button
          className="button"
          disabled={!writableTeamId || createPage.isPending}
          onClick={() => start()}
        >
          New page
        </button>
      </TopbarSlot>

      <div className="flex flex-col gap-7 overflow-y-auto px-8 py-6.5 max-[720px]:gap-5 max-[720px]:px-4 max-[720px]:py-5">
        <TemplateCards
          templates={templates.data ?? []}
          disabled={!writableTeamId || createPage.isPending}
          onChoose={start}
        />
        {/* Five rows, the number screen 22 draws. Everything older is one column to
            the left in the tree, which is the list that is meant to be complete. */}
        <RecentlyChanged
          pages={(pages.data ?? []).slice(0, 5)}
          folders={folders.data ?? []}
          people={people.data ?? []}
        />
      </div>
    </>
  );
}
