import { describe, expect, it } from "vitest";
import type { ActivityRow, KansoInstant, Ticket, TicketStatus } from "@/lib/api";
import {
  activitySentence,
  activityTime,
  donePercent,
  periodLabel,
  statusCounts,
} from "./project-copy";

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
    archived: false,
    mirror: { state: "disabled" },
    createdAt: "2026-08-01T00:00:00Z",
    updatedAt: "2026-08-01T00:00:00Z",
  };
}

const rows = (...statuses: TicketStatus[]) => statuses.map(ticket);

describe("statusCounts", () => {
  /**
   * The drawing's bar runs done → review → progress → todo → backlog, which is finished
   * to not-started and not `TICKET_STATUSES` order. It is the order the reader is being
   * asked a question in — how much of this is done — so it is the order here.
   */
  it("counts in the order the bar is drawn, finished first", () => {
    const counts = statusCounts(rows("todo", "done", "done", "in_review", "backlog"));
    expect(counts).toEqual([
      { status: "done", count: 2 },
      { status: "in_review", count: 1 },
      { status: "in_progress", count: 0 },
      { status: "todo", count: 1 },
      { status: "backlog", count: 1 },
      { status: "canceled", count: 0 },
    ]);
  });

  it("counts nothing as nothing rather than as an absent status", () => {
    expect(statusCounts([]).every((entry) => entry.count === 0)).toBe(true);
    expect(statusCounts([])).toHaveLength(6);
  });
});

describe("donePercent", () => {
  /**
   * Out of what still counts. A canceled ticket was abandoned, not delivered and not
   * outstanding, so counting it in the denominator would make abandoning work look like
   * falling behind — and counting it in the numerator would make it look like progress.
   */
  it("is what is finished out of what has not been abandoned", () => {
    expect(donePercent(statusCounts(rows("done", "done", "todo", "todo")))).toBe(50);
    expect(donePercent(statusCounts(rows("done", "done", "canceled", "canceled")))).toBe(100);
  });

  it("rounds rather than truncating, and answers zero for an empty project", () => {
    // 1 of 3 is 33.33…
    expect(donePercent(statusCounts(rows("done", "todo", "todo")))).toBe(33);
    // 2 of 3 is 66.66…, which truncation would report as 66.
    expect(donePercent(statusCounts(rows("done", "done", "todo")))).toBe(67);
    expect(donePercent(statusCounts([]))).toBe(0);
    // Every ticket canceled: nothing was delivered, and there is nothing left to deliver.
    expect(donePercent(statusCounts(rows("canceled")))).toBe(0);
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

describe("activitySentence", () => {
  const row = (kind: ActivityRow["kind"], payload: Record<string, unknown> = {}): ActivityRow => ({
    id: "row",
    entityType: "project",
    entityId: "project-1",
    // The whole `User`, because that is what `/api/activity` sends and what the row
    // type now says. The feed reads two of its fields; the fixture cannot narrow it.
    actor: {
      id: "user-1",
      email: "tykok@kanso.dev",
      displayName: "Tykok",
      instanceRole: "owner",
      hasPassword: true,
    },
    kind,
    payload,
    createdAt: "2026-08-17T14:20:00Z",
  });

  /**
   * The drawing's own three lines are the specification: "Tykok a passé KAN-142 en
   * cours", "2 pages poussées vers Notion", "Léa a terminé KAN-131". Two of the three
   * name a person and one does not, which is exactly the shape `actor: null` has.
   */
  it("names who did what, and what it was done to", () => {
    expect(activitySentence(row("status_changed", { ref: "KAN-142", to: "in_progress" }))).toBe(
      "Tykok moved KAN-142 to In progress",
    );
    expect(activitySentence(row("created", { ref: "KAN-150" }))).toBe("Tykok created KAN-150");
    expect(activitySentence(row("assigned", { ref: "KAN-142" }))).toBe(
      "Tykok took KAN-142",
    );
  });

  /** Closing a cycle writes this row on every ticket that did not fit into it. */
  it("says where work carried out of a closed cycle went", () => {
    expect(activitySentence(row("carried_over", { ref: "KAN-142", from: 24, to: 25 }))).toBe(
      "Tykok carried KAN-142 into cycle 25",
    );
    expect(activitySentence(row("carried_over"))).toBe("Tykok carried work into the next cycle");
  });

  /** The mirror is not a person, and "nobody pushed 2 pages" is not a sentence. */
  it("leaves the actor out when there was not one", () => {
    expect(activitySentence({ ...row("mirror_pushed"), actor: null })).toBe("Pushed to Notion");
  });

  /**
   * `payload` carries the before and after of a scalar change and nothing else, so a row
   * whose payload is empty still has to read as a sentence rather than as `undefined`.
   */
  it("reads as a sentence with an empty payload", () => {
    expect(activitySentence(row("renamed"))).toBe("Tykok renamed a ticket");
    expect(activitySentence(row("status_changed"))).toBe("Tykok changed a status");
  });
});

describe("activityTime", () => {
  // Fixed instants rather than `new Date()`: a test that reads the clock passes at 14:20
  // and fails at midnight.
  const now = new Date("2026-08-17T18:00:00Z");

  it("gives the hour for something that happened today", () => {
    expect(activityTime("2026-08-17T14:20:00Z", now, "UTC")).toBe("14:20");
  });

  it("says yesterday rather than a date nobody has to decode", () => {
    expect(activityTime("2026-08-16T09:04:00Z", now, "UTC")).toBe("yesterday");
  });

  it("gives the day for anything older", () => {
    expect(activityTime("2026-08-12T09:04:00Z", now, "UTC")).toBe("12 Aug");
    expect(activityTime("2025-12-31T09:04:00Z", now, "UTC")).toBe("31 Dec");
  });

  /**
   * An activity row is a *moment*, unlike a project bound, so it is converted into the
   * reader's zone — 23:30 UTC is already tomorrow in Paris, and a feed that said
   * "yesterday" about something the reader did an hour ago would be wrong.
   */
  it("converts, because a moment is not a day", () => {
    expect(activityTime("2026-08-17T23:30:00Z", new Date("2026-08-18T01:00:00Z"), "Europe/Paris")).toBe(
      "01:30",
    );
  });
});
