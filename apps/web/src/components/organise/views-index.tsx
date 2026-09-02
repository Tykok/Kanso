"use client";

import Link from "next/link";
import { Row } from "@/components/ui/row";
import { TopbarSlot, usePageShell } from "@/components/shell/topbar-slot";
import { useSavedViews } from "@/lib/queries";
import { useOrganiseTeam } from "./team";

/**
 * `/views` — the list the sidebar's `Saved views` row points at.
 *
 * Not in the drawings, which only show a view already open. It exists because the nav row
 * has to land somewhere, and a row that navigates straight into whichever view happens to
 * be first would make the sidebar's meaning depend on an alphabetical accident.
 */
export function SavedViewsIndex() {
  const { team } = useOrganiseTeam();
  const views = useSavedViews(team?.id);

  // `Core / Saved views` — the leaf is the row's own label, so only the team is published.
  usePageShell({ crumbs: { team: team?.name } });

  return (
    <>
      <TopbarSlot>
        <span className="flex-1" />
        {views.data && <span>{views.data.length} views</span>}
      </TopbarSlot>

      {views.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {!views.isPending && (views.data ?? []).length === 0 && (
        <div className="empty flex-col gap-1">
          <span className="text-13 text-foreground">No saved view yet</span>
          <span className="text-12 text-faint">
            A view is a question you keep asking — a project, a status, a person.
          </span>
        </div>
      )}

      <div className="flex min-h-0 flex-1 flex-col gap-row overflow-y-auto p-2 px-3">
        {(views.data ?? []).map((view) => (
          <Link key={view.id} href={`/views/${view.id}`}>
            <Row className="grid grid-cols-[1fr_120px_60px]" data-testid="views-index-row">
              <span className="truncate text-foreground">{view.name}</span>
              <span className="truncate text-12 text-muted-foreground">
                {view.shared ? "Shared with the team" : "Only yours"}
              </span>
              <span className="text-right font-mono text-11 text-faint">{view.count}</span>
            </Row>
          </Link>
        ))}
      </div>
    </>
  );
}
