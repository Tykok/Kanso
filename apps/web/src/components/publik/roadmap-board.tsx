"use client";

import Link from "next/link";
import { useRoadmap } from "@/lib/queries/publik";
import { CATEGORY_COLORS, colourOf } from "@/lib/status";
import { CATEGORY_LABELS } from "@/lib/statuses";
import type { RoadmapEntry, RoadmapGroup } from "@/lib/api/publik";
import { deliveredOn } from "./delivered";
import { VoteButton } from "./vote-button";

/**
 * Screen 27: what is being worked on, in columns, votable, no account.
 *
 * The column headings are the five **categories**, since `KAN-90` — `CATEGORY_LABELS`,
 * the same module the app's own cross-team lists read. They were `STATUS_LABELS` on the
 * drawing's instruction that "the statuses are the application's own; nothing is reworded
 * for the shop window", and that instruction held while every team read the same six
 * words. This page has no team scope at all — it is every published ticket in the
 * instance — so the words would give it one column per word per team, with `Done` and
 * `Livré` side by side meaning the same thing. `RoadmapGroup` on the server is where the
 * argument is written out.
 *
 * Each *card* still prints its own team's word, so nothing is reworded for the shop
 * window where a reader is looking at one ticket.
 *
 * The server sends only the groups that hold something, so an instance whose published
 * work sits in three categories draws three columns, and the grid takes however many
 * arrive.
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
        <Column key={group.category} group={group} now={now} />
      ))}
    </div>
  );
}

function Column({ group, now }: { group: RoadmapGroup; now: Date }) {
  return (
    <div className="flex min-w-0 flex-col gap-2.5">
      <h2
        className="flex items-center gap-2 border-b-2 pb-2 text-12 font-medium"
        style={{ borderColor: CATEGORY_COLORS[group.category] }}
      >
        <span className="flex-1">{CATEGORY_LABELS[group.category]}</span>
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
  // The row's own category, off the payload — `categoryOf` is a map over Kanso's six and
  // would answer `undefined` for a word a team invented, drawing no rule and saying nothing.
  const moving = entry.category === "started";
  const delivered = entry.deliveredAt ? deliveredOn(entry.deliveredAt, now) : "";

  return (
    <article
      className="flex flex-col gap-2 rounded-lg bg-card px-3.5 py-3 shadow-flat"
      style={
        moving
          ? { boxShadow: `inset 2px 0 0 ${colourOf(entry.status, entry.category)}` }
          : undefined
      }
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
