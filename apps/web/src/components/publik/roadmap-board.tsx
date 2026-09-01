"use client";

import Link from "next/link";
import { useRoadmap } from "@/lib/queries/publik";
import { categoryOf, STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import type { RoadmapEntry, RoadmapGroup } from "@/lib/api/publik";
import { deliveredOn } from "./delivered";
import { VoteButton } from "./vote-button";

/**
 * Screen 27: what is being worked on, in columns, votable, no account.
 *
 * The column headings are `STATUS_LABELS` — the same module the ticket list and the
 * timeline read. That is the drawing's own instruction ("the statuses are the
 * application's own; nothing is reworded for the shop window") taken literally: there is
 * no second vocabulary here to drift from the first, and renaming a status renames it
 * everywhere at once, including on the page strangers read.
 *
 * The server sends only the groups that hold something, so an instance whose published
 * work sits in four statuses draws the four the drawing draws, and the grid takes
 * however many arrive.
 */
export function RoadmapBoard() {
  const roadmap = useRoadmap();
  const now = new Date();

  if (roadmap.isPending) {
    return <p className="py-12 text-13 text-faint">Loading the roadmap…</p>;
  }

  if (roadmap.isError) {
    return (
      <p className="error py-12 text-13">
        The roadmap could not be loaded. This instance may not be answering right now.
      </p>
    );
  }

  const groups = roadmap.data?.groups ?? [];
  if (groups.length === 0) {
    // Distinguished from a failure on purpose, and worded so it reads as a fact about
    // this instance rather than a broken page: nothing has been published yet.
    return (
      <p className="py-12 text-13 text-faint">
        Nothing is published yet. Work becomes visible here when a maintainer marks a
        ticket public.
      </p>
    );
  }

  return (
    <div className="grid gap-5 grid-cols-[repeat(auto-fit,minmax(min(100%,240px),1fr))]">
      {groups.map((group) => (
        <Column key={group.status} group={group} now={now} />
      ))}
    </div>
  );
}

function Column({ group, now }: { group: RoadmapGroup; now: Date }) {
  return (
    <div className="flex min-w-0 flex-col gap-2.5">
      <h2
        className="flex items-center gap-2 border-b-2 pb-2 text-12 font-medium"
        style={{ borderColor: STATUS_COLORS[group.status] }}
      >
        <span className="flex-1">{STATUS_LABELS[group.status]}</span>
        <span className="font-mono text-11 text-faint">{group.count}</span>
      </h2>
      {group.tickets.map((entry) => (
        <RoadmapCard key={entry.key} entry={entry} now={now} />
      ))}
    </div>
  );
}

/**
 * The 2px rule down the left edge appears on work in progress and nowhere else — the
 * drawing marks the moving column and leaves the others flat, which is what makes "in
 * progress" findable without a legend.
 */
function RoadmapCard({ entry, now }: { entry: RoadmapEntry; now: Date }) {
  const moving = categoryOf(entry.status) === "started";
  const delivered = entry.deliveredAt ? deliveredOn(entry.deliveredAt, now) : "";

  return (
    <article
      className="flex flex-col gap-2 rounded-lg bg-card px-3.5 py-3 shadow-flat"
      style={moving ? { boxShadow: `inset 2px 0 0 ${STATUS_COLORS[entry.status]}` } : undefined}
    >
      <Link
        href={`/roadmap/${entry.key}`}
        className="text-13 leading-snug text-foreground hover:text-accent-ink"
      >
        {entry.title}
      </Link>
      <div className="flex items-center gap-2.5 text-11 text-faint">
        {delivered ? (
          // A delivered ticket carries its date instead of a vote control: voting for
          // something that already shipped asks a question with no answer left in it.
          <span>{delivered}</span>
        ) : (
          <VoteButton ticketKey={entry.key} votes={entry.votes} />
        )}
        <span className="font-mono">{entry.key}</span>
      </div>
    </article>
  );
}
