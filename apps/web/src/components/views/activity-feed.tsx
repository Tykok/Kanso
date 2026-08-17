"use client";

import { useActivity } from "@/lib/queries";
import type { ActivityRow } from "@/lib/api";
import { GroupLabel } from "../ui/group-label";
import { activitySentence, activityTime } from "./project-copy";

/**
 * The feed screen 05 draws down its right-hand column.
 *
 * It reads `GET /api/activity?entityType=&entityId=`, which is slice 0's endpoint and is
 * not on slice A's branch. So the component is written against the shape and fails quiet:
 * a project page whose feed is missing is still a project page, and a red strip about an
 * endpoint the reader never asked for teaches them nothing. `useActivity` does not retry,
 * so the cost of the endpoint's absence is one 404 per mount.
 *
 * Newest first is the server's order and not re-sorted here — the index is
 * `created_at DESC` precisely so a feed can be rendered in the order it arrives.
 */
export function ActivityFeed({
  entityType,
  entityId,
}: {
  entityType: ActivityRow["entityType"];
  entityId: string;
}) {
  const activity = useActivity(entityType, entityId);

  // Nothing at all rather than a heading over an apology. The three states that get here
  // — in flight, refused, empty — all mean "there is nothing to read yet".
  if (!activity.data || activity.data.length === 0) return null;

  // Read once per render, not per row: eleven rows formatted against eleven slightly
  // different "now"s could print two different answers for one minute.
  const now = new Date();
  const timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone;

  return (
    <div className="flex flex-col gap-2">
      <GroupLabel className="pt-0 px-0 pb-0">Activity</GroupLabel>
      <ul data-testid="activity-feed" className="m-0 flex list-none flex-col gap-2.5 p-0 text-12 text-muted-foreground">
        {activity.data.map((row) => (
          <li key={row.id} className="flex gap-2.5">
            <time
              className="font-mono text-11 text-faint"
              // The machine-readable instant beside the human one: the column says
              // "yesterday", and a reader who needs the actual moment can still get it.
              dateTime={row.createdAt}
            >
              {activityTime(row.createdAt, now, timeZone)}
            </time>
            <span className="flex-1 text-pretty">{activitySentence(row)}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
