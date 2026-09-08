import { describe, expect, it } from "vitest";
import type { KansoInstant, Team, Ticket, TicketStatus } from "@/lib/api";
import { donePercent, healthLabel, periodLabel, statusCounts } from "./project-copy";

const day = (value: string): KansoInstant => ({ at: `${value}T00:00:00Z`, hasTime: false });

function ticket(status: TicketStatus): Ticket {
  return {
    id: `${status}-${Math.random()}`,
    identifier: "KAN-1",
    number: 1,
    teamId: "team",
    title: "A ticket",
    status,
    priority: "none",
    assigneeIds: [],
    docIds: [],
    customFields: {},
    // Required on `Ticket` for the reason `customFields` beside it is: the server always
    // sends the key, so a factory that omits it is not a ticket the API can produce.
    pullRequests: [],
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  };
}

const rows = (...statuses: TicketStatus[]) => statuses.map(ticket);

/**
 * The one team these rows are in, with its own words — `KAN-90`.
 *
 * `statusCounts` groups by *category* now, and a category is a fact only a team's
 * catalogue knows. This fixture renames two of the six and invents a seventh, so the
 * assertions below say something a version keyed on the words could not: the bar counts
 * `Devis` under `backlog` without ever having heard of it.
 */
const teams = [
  {
    id: "team",
    statuses: [
      { key: "backlog", label: "Boîte", category: "backlog", position: 0 },
      { key: "devis", label: "Devis", category: "backlog", position: 1 },
      { key: "todo", label: "Qualifié", category: "unstarted", position: 2 },
      { key: "in_progress", label: "En cours", category: "started", position: 3 },
      { key: "in_review", label: "Relecture", category: "started", position: 4 },
      { key: "done", label: "Livré", category: "completed", position: 5 },
      { key: "canceled", label: "Sans suite", category: "canceled", position: 6 },
    ],
  },
] as unknown as Team[];

describe("statusCounts", () => {
  /**
   * The drawing's bar runs done → review → progress → todo → backlog, which is finished
   * to not-started and not `DEFAULT_STATUSES` order. It is the order the reader is being
   * asked a question in — how much of this is done — so it is the order here.
   */
  it("counts in the order the bar is drawn, finished first", () => {
    const counts = statusCounts(teams, rows("todo", "done", "done", "in_review", "backlog"));
    expect(counts).toEqual([
      { status: "completed", count: 2 },
      { status: "started", count: 1 },
      { status: "unstarted", count: 1 },
      { status: "backlog", count: 1 },
      { status: "canceled", count: 0 },
    ]);
  });

  it("counts nothing as nothing rather than as an absent segment", () => {
    expect(statusCounts(teams, []).every((entry) => entry.count === 0)).toBe(true);
    // Five and not six since `KAN-90`: the bar's segments are the five meanings, so a
    // project holding nothing still draws every one of them at zero — a gap where a
    // segment emptied, rather than a bar that reorders itself as work moves.
    expect(statusCounts(teams, [])).toHaveLength(5);
  });

  // The whole of `KAN-90` on this file: a word the bar has never heard of is counted, by
  // its meaning, in the segment its team put it in.
  it("counts a word a team invented, under what that team said it means", () => {
    const counts = statusCounts(teams, rows("devis", "devis", "done"));

    expect(counts).toEqual([
      { status: "completed", count: 1 },
      { status: "started", count: 0 },
      { status: "unstarted", count: 0 },
      { status: "backlog", count: 2 },
      { status: "canceled", count: 0 },
    ]);
  });
});

describe("donePercent", () => {
  /**
   * Out of what still counts. A canceled ticket was abandoned, not delivered and not
   * outstanding, so counting it in the denominator would make abandoning work look like
   * falling behind — and counting it in the numerator would make it look like progress.
   */
  it("is what is finished out of what has not been abandoned", () => {
    expect(donePercent(statusCounts(teams, rows("done", "done", "todo", "todo")))).toBe(50);
    expect(donePercent(statusCounts(teams, rows("done", "done", "canceled", "canceled")))).toBe(100);
  });

  it("rounds rather than truncating, and answers zero for an empty project", () => {
    // 1 of 3 is 33.33…
    expect(donePercent(statusCounts(teams, rows("done", "todo", "todo")))).toBe(33);
    // 2 of 3 is 66.66…, which truncation would report as 66.
    expect(donePercent(statusCounts(teams, rows("done", "done", "todo")))).toBe(67);
    expect(donePercent(statusCounts(teams, []))).toBe(0);
    // Every ticket canceled: nothing was delivered, and there is nothing left to deliver.
    expect(donePercent(statusCounts(teams, rows("canceled")))).toBe(0);
  });
});

describe("periodLabel", () => {
  /**
   * Formatted from the `YYYY-MM-DD` string and never through `new Date(...).getDate()`:
   * a bound with `hasTime: false` names a *day*, and a reader west of UTC would be shown
   * the day before the one that was posted.
   */
  it("reads both bounds as days, in the reader's own zone or not at all", () => {
    expect(periodLabel(day("2026-08-04"), day("2026-09-30"))).toBe("4 Aug → 30 Sep");
    expect(periodLabel(day("2026-01-01"), day("2026-12-31"))).toBe("1 Jan → 31 Dec");
  });

  it("says which bound it has when it has only one", () => {
    expect(periodLabel(day("2026-08-04"), undefined)).toBe("from 4 Aug");
    expect(periodLabel(undefined, day("2026-09-30"))).toBe("until 30 Sep");
  });

  /** An em dash and not an empty string: the row exists, and it has no answer. */
  it("says nothing rather than nothing at all", () => {
    expect(periodLabel(undefined, undefined)).toBe("—");
  });
});


/**
 * The decision this whole feature turns on, in one function: absence is not `on_track`.
 *
 * "Nobody has said" and "somebody looked and said it is fine" are different facts. A
 * client that renders the first as the second turns every project in the instance green on
 * the day this ships — including the ones nobody has ever assessed — and a reader who
 * learns that green is the resting state stops reading green at all.
 */
describe("healthLabel", () => {
  it("names each of the three", () => {
    expect(healthLabel("on_track")).toBe("On track");
    expect(healthLabel("at_risk")).toBe("At risk");
    expect(healthLabel("off_track")).toBe("Off track");
  });

  it("says nobody has said, rather than saying it is fine", () => {
    expect(healthLabel(undefined)).toBe("No update yet");
    expect(healthLabel(undefined)).not.toBe(healthLabel("on_track"));
  });
});

