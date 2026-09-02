"use client";

import type { ReactNode } from "react";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import type { MyWorkStrip } from "@/lib/api";
import { useMyStats } from "@/lib/queries";
import { cn } from "@/lib/utils";
import { AssignedTab } from "./assigned-tab";
import { BlockedTab } from "./blocked-tab";
import { DoneTab } from "./done-tab";
import { DueTab } from "./due-tab";
import { ProgressTab } from "./progress-tab";

/**
 * Screen `/me` — a personal home, with tabs like the data sources of a Notion database.
 *
 * This file owns two things and deliberately nothing else: which tab is on screen, and
 * the four-number strip above every one of them. Each tab is its own file and asks its
 * own questions, so the screen can grow a sixth reading without this one getting longer.
 *
 * **The tab is in `?tab=` and never in the path.** Two reasons, and the first is the one
 * the spec gives: §2's rule is that the route decides which sidebar row is lit, and a
 * path like `/me/due` would need a special case in `lib/nav.ts` to keep lighting the same
 * row — a special case in the one function that exists to have none. The second is that a
 * query parameter makes every tab a link somebody can paste, which a component's
 * `useState` would not.
 *
 * Assigned is what an absent or unrecognised `tab` resolves to. Unrecognised rather than
 * refused: a link to a tab a later version renamed must land on the screen it was about,
 * not on a 404 — the same reading `mergeBindings` gives an unknown action id.
 */

export const ME_TABS = [
  { id: "assigned", label: "Assigned" },
  { id: "due", label: "Due" },
  { id: "blocked", label: "Blocked" },
  { id: "done", label: "Done" },
  /**
   * The pace, and the one tab this branch does not draw.
   *
   * Screen 40 landed on `main` as `/progress` while this branch was open, and the
   * maintainer's ruling is that `/me` is the tabbed home and that screen is one of its
   * tabs. So the tab exists here and its body is a host — see `progress-tab.tsx`.
   */
  { id: "progress", label: "Progress" },
] as const;

export type MeTab = (typeof ME_TABS)[number]["id"];

const TAB_IDS: readonly string[] = ME_TABS.map((tab) => tab.id);

/** One component per tab, so the panel below is a lookup rather than a five-arm switch. */
const PANELS: Record<MeTab, () => ReactNode> = {
  assigned: AssignedTab,
  due: DueTab,
  blocked: BlockedTab,
  done: DoneTab,
  progress: ProgressTab,
};

export function MeView() {
  const router = useRouter();
  const pathname = usePathname();
  const asked = useSearchParams().get("tab");
  const stats = useMyStats();

  const tab: MeTab = TAB_IDS.includes(asked ?? "") ? (asked as MeTab) : "assigned";

  /**
   * `replace` and not `push`, on purpose.
   *
   * `Escape` and the top bar's `×` both run `app.back`, and a reader who has looked at
   * three tabs expects one press to leave the screen rather than three to walk back
   * through the readings they already saw. The address still says which tab is open, so
   * the property the parameter was chosen for — a link somebody can paste — is intact;
   * what is given up is only a history entry per glance.
   *
   * Every tab writes its parameter, Assigned included, so all five links are the same
   * shape. A bare `/me` is still read as Assigned above rather than rewritten on arrival:
   * an effect that changed the address behind the reader is how a `?tab=` ends up in
   * somebody's history for a click they never made.
   */
  const go = (next: MeTab) => router.replace(`${pathname}?tab=${next}`, { scroll: false });

  const Panel = PANELS[tab];

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <Strip
        strip={stats.data?.strip}
        failed={stats.error !== null && !stats.isPending}
        onSelect={go}
      />

      <div role="tablist" aria-label="My work" className="flex gap-5 border-b border-border px-6">
        {ME_TABS.map(({ id, label }) => (
          <button
            key={id}
            type="button"
            role="tab"
            id={`me-tab-${id}`}
            aria-controls="me-panel"
            aria-selected={tab === id}
            data-testid="me-tab"
            // A tab that is not selected is out of the tab order and the arrow keys walk
            // between them: that is the whole of what `role="tab"` promises a keyboard,
            // and wearing the role without keeping it is worse than a plain button. The
            // trash view's tabs are the same three lines, for the same reason.
            tabIndex={tab === id ? 0 : -1}
            onKeyDown={(event) => {
              const delta = event.key === "ArrowRight" ? 1 : event.key === "ArrowLeft" ? -1 : 0;
              if (delta === 0) return;
              event.preventDefault();
              const at = (TAB_IDS.indexOf(tab) + delta + ME_TABS.length) % ME_TABS.length;
              const next = ME_TABS[at].id;
              go(next);
              document.getElementById(`me-tab-${next}`)?.focus();
            }}
            onClick={() => go(id)}
            className={cn(
              "-mb-px pb-2.5 text-12",
              tab === id
                ? "font-medium text-foreground shadow-[inset_0_-2px_0_var(--primary)]"
                : "text-muted-foreground hover:text-foreground",
            )}
          >
            {label}
          </button>
        ))}
      </div>

      <div
        role="tabpanel"
        id="me-panel"
        aria-labelledby={`me-tab-${tab}`}
        className="flex min-h-0 flex-1 flex-col overflow-y-auto"
      >
        <Panel />
      </div>
    </div>
  );
}

/**
 * The four numbers, above every tab, each of which *is* a tab.
 *
 * All four count rows and not points — a ticket two people share is on both their plates
 * whole — which is why they can sit in one line without a unit between them. The points
 * live in the Done tab, where the halving that makes them a share can be explained beside
 * them.
 *
 * `openUnestimated` is deliberately not here. It is not a count of work: it is the reason
 * a measured pace understates, so it belongs beside a pace and nowhere else — which is
 * the head of the Done tab, where the points it cannot speak for are drawn. A fifth
 * number on this strip would be a figure with no tab to explain it.
 *
 * Each cell navigates to the tab that accounts for it, which is the whole design of the
 * strip: a number nobody can open is a number nobody can check.
 */
function Strip({
  strip,
  failed,
  onSelect,
}: {
  strip: MyWorkStrip | undefined;
  failed: boolean;
  onSelect: (tab: MeTab) => void;
}) {
  return (
    <div className="flex flex-col gap-1.5 px-6 pt-5 pb-4">
      <div className="flex flex-wrap gap-8" data-testid="me-strip">
        <Number value={strip?.open} label="open" onClick={() => onSelect("assigned")} />
        {/*
          * The one cell that may go red, and only when it is not zero: `inbox/tabs.tsx`
          * already argues that a red 0 is an alarm about nothing, and a zero overdue is
          * the best news on this strip. It stays the one alarm on the line — `blocked` is
          * a fact about a queue rather than a deadline anybody has missed, and two red
          * numbers side by side is two alarms competing for the same glance.
          */}
        <Number
          value={strip?.overdue}
          label="overdue"
          urgent={(strip?.overdue ?? 0) > 0}
          onClick={() => onSelect("due")}
        />
        <Number value={strip?.blocked} label="blocked" onClick={() => onSelect("blocked")} />
        <Number
          value={strip?.finishedThisWeek}
          label="finished this week"
          onClick={() => onSelect("done")}
        />
      </div>
      {failed && (
        <p className="m-0 text-11 text-faint">
          Kanso could not read your numbers. The tabs below are drawn from your own lists
          and still work.
        </p>
      )}
    </div>
  );
}

function Number({
  value,
  label,
  urgent,
  onClick,
}: {
  value: number | undefined;
  label: string;
  urgent?: boolean;
  onClick: () => void;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      data-testid="me-strip-number"
      aria-label={`${value ?? "no"} ${label} — open the tab that accounts for it`}
      className="flex flex-col items-start gap-0.5 rounded-sm text-left hover:opacity-80"
    >
      {/* An em dash and never a 0 before the answer lands. A zero would be a measurement,
          and there is nothing measured yet. */}
      <span
        className={cn(
          "text-21 leading-none font-medium tracking-[-0.02em]",
          value === undefined ? "text-faint" : urgent ? "text-urgent" : "text-foreground",
        )}
      >
        {value ?? "—"}
      </span>
      <span className="text-11 text-muted-foreground">{label}</span>
    </button>
  );
}
