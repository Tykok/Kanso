"use client";

import Link from "next/link";
import { GroupLabel } from "@/components/ui/group-label";
import type { SavedView } from "@/lib/api";
import { useUi } from "@/store/ui";

/**
 * The team's saved views, with the count each one currently answers with.
 *
 * The count comes from the server rather than from the rows on screen: the sidebar shows
 * four views and only one of them is open, so three of the four numbers are about lists
 * nobody has fetched.
 */
export function ViewRail({ views, currentId }: { views: SavedView[]; currentId?: string }) {
  const openDialog = useUi((state) => state.openDialog);

  return (
    <>
      <GroupLabel className="pt-0">Saved views</GroupLabel>
      {views.length === 0 && <div className="px-1.5 py-1 text-12 text-faint">No saved view yet</div>}

      {views.map((view) => (
        <Link
          key={view.id}
          href={`/views/${view.id}`}
          data-testid="view-rail-row"
          aria-current={view.id === currentId}
          className={
            view.id === currentId
              ? "flex items-center gap-1 rounded-md bg-accent-soft px-1.5 py-[5px] font-medium text-foreground"
              : "flex items-center gap-1 rounded-md px-1.5 py-[5px] text-muted-foreground hover:bg-accent"
          }
        >
          <span className="min-w-0 flex-1 truncate">{view.name}</span>
          <span className="font-mono text-11 text-faint">{view.count}</span>
        </Link>
      ))}

      {/*
       * `{ kind: "saveView" }` is the dialog slice 0 reserved for this. The dialog itself is
       * not in slice C: it would have to live beside the other three in `components/dialogs/`
       * and be mounted from `app/page.tsx`, both of which are shared. So this opens the
       * reserved dialog and the integration pass mounts it — better than a second, private
       * dialog nobody else can reach, which is what building it here would leave behind.
       */}
      <button
        type="button"
        className="rounded-md px-1.5 py-[5px] text-left text-faint hover:bg-accent hover:text-foreground"
        onClick={() => openDialog({ kind: "saveView" })}
      >
        + Save the view
      </button>
    </>
  );
}
