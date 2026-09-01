import { describe, expect, it } from "vitest";
import { TICKET_STATUSES, type TicketStatus } from "@/lib/api";
import { categoryOf, type StatusCategory } from "@/lib/status";
import {
  inOrder,
  isCounted,
  isOpen,
  LOAD_ORDER,
  PROGRESS_ORDER,
  statusesWhere,
  WORKFLOW_ORDER,
} from "./status-order";

/**
 * The three orders, and the proof that separating them from membership moved nothing.
 *
 * Every sequence asserted below is quoted from the constant it replaced — `STATUS_ORDER`
 * in `organise/grouping.ts`, the two `BAR_ORDER`s in `organise/burndown.ts` and
 * `views/project-copy.ts`, `PLOTTED` in `organise/workload-view.tsx`. They are written out
 * as literals rather than derived from anything, because a refactor that computed its own
 * expectation would pass whatever it did.
 */

describe("the orders themselves", () => {
  it("stacks a grouped list downwards as the work flows", () => {
    expect([...WORKFLOW_ORDER]).toEqual([
      "backlog",
      "todo",
      "in_progress",
      "in_review",
      "done",
      "canceled",
    ]);
  });

  it("runs a proportion bar finished-first, left to right", () => {
    expect([...PROGRESS_ORDER]).toEqual([
      "done",
      "in_review",
      "in_progress",
      "todo",
      "backlog",
      "canceled",
    ]);
  });

  /**
   * Not the reverse of [PROGRESS_ORDER] restricted to the open statuses, which would run
   * `in_review, in_progress, todo, backlog`. The workload bar has no `done` at its left to
   * measure a descent against — it is open-only by construction — so its leftmost segment
   * is what is actually moving rather than what is furthest along. Merging the two would
   * swap `in_progress` and `in_review` on screen 23.
   */
  it("puts what is moving at the left of a load bar, which no other order does", () => {
    expect([...LOAD_ORDER]).toEqual(["in_progress", "in_review", "todo", "backlog"]);
    expect([...LOAD_ORDER]).not.toEqual(inOrder(statusesWhere(isOpen), PROGRESS_ORDER));
  });

  /**
   * `TICKET_STATUSES` happens to be spelled in the same sequence as [WORKFLOW_ORDER], and
   * that coincidence is not allowed to become the definition: the vocabulary is a set the
   * server also holds, the order is a product decision the page boundary of every grouped
   * view is cut against. Reordering the one must not restack the other.
   */
  it("is a separate list from the vocabulary, over exactly the same statuses", () => {
    expect([...WORKFLOW_ORDER].sort()).toEqual([...TICKET_STATUSES].sort());
    expect([...PROGRESS_ORDER].sort()).toEqual([...TICKET_STATUSES].sort());
  });
});

describe("membership, read off the category", () => {
  it("counts everything that is not abandoned, as the cycle report does", () => {
    // `CycleService.COUNTED_STATUSES`, which is what `CycleReport.byStatus` is keyed by.
    expect(statusesWhere(isCounted)).toEqual([
      "backlog",
      "todo",
      "in_progress",
      "in_review",
      "done",
    ]);
  });

  it("holds open everything neither category that ends a ticket, as the workload does", () => {
    // `WorkloadService.OPEN_STATUSES`, which is what `WorkloadRow.byStatus` is keyed by.
    expect(statusesWhere(isOpen)).toEqual(["backlog", "todo", "in_progress", "in_review"]);
  });

  it("asks the category and never a literal, so the two can never disagree", () => {
    for (const status of TICKET_STATUSES) {
      expect(statusesWhere(isOpen).includes(status)).toBe(isOpen(categoryOf(status)));
      expect(statusesWhere(isCounted).includes(status)).toBe(isCounted(categoryOf(status)));
    }
  });

  /**
   * The failure this whole split exists to prevent: a seventh status arrives, and a chart
   * that hard-coded its membership silently stops drawing part of the work. Membership is
   * derived, so it appears; the order has not been told where to put it, so it appears at
   * the end rather than in an invented position.
   */
  it("draws a status nobody has placed yet, last rather than not at all", () => {
    const blocked = "blocked" as TicketStatus;
    const open = [...statusesWhere(isOpen), blocked];

    expect(inOrder(open, LOAD_ORDER)).toEqual([
      "in_progress",
      "in_review",
      "todo",
      "backlog",
      blocked,
    ]);
  });

  it("keeps two unplaced statuses in the order they were handed over", () => {
    const order: TicketStatus[] = ["done"];
    const statuses = ["todo", "done", "backlog"] as TicketStatus[];

    expect(inOrder(statuses, order)).toEqual(["done", "todo", "backlog"]);
  });
});

/**
 * What each of the five sites draws, asserted as the exact sequence it drew before.
 *
 * This is the ticket's whole claim in one block: four client lists and one `CASE` on the
 * server, expressed as one of three orders applied to a membership the category answers,
 * and not one of them moves.
 */
describe("the sites, unchanged", () => {
  it("stacks the grouped list and the server's buckets the same way", () => {
    expect(inOrder(TICKET_STATUSES, WORKFLOW_ORDER)).toEqual([
      "backlog",
      "todo",
      "in_progress",
      "in_review",
      "done",
      "canceled",
    ]);
  });

  it("draws the cycle's progress bar over what still counts as work", () => {
    expect(inOrder(statusesWhere(isCounted), PROGRESS_ORDER)).toEqual([
      "done",
      "in_review",
      "in_progress",
      "todo",
      "backlog",
    ]);
  });

  it("draws the project's proportion bar over all six, abandoned last", () => {
    expect(inOrder(TICKET_STATUSES, PROGRESS_ORDER)).toEqual([
      "done",
      "in_review",
      "in_progress",
      "todo",
      "backlog",
      "canceled",
    ]);
  });

  it("draws the workload bar over what is still open", () => {
    expect(inOrder(statusesWhere(isOpen), LOAD_ORDER)).toEqual([
      "in_progress",
      "in_review",
      "todo",
      "backlog",
    ]);
  });
});

/**
 * The one order that crosses the wire.
 *
 * `TicketQueryRepository.statusRank` orders the buckets of a grouped page in SQL, and the
 * page boundary is cut against that order — so the client may not restack what arrives.
 * The two cannot share a value: an ordering is a rendering decision, not data, and sending
 * it would make every chart wait on a fetch to know how to draw itself. They share this
 * written sequence instead, pinned here and again in `domain/StatusOrderTest.kt`, so the
 * copy that drifts fails a test on the side that drifted.
 */
describe("agreement with the server", () => {
  it("ranks the buckets in the sequence the SQL does", () => {
    const asTheServerRanksThem = ["backlog", "todo", "in_progress", "in_review", "done", "canceled"];

    expect([...WORKFLOW_ORDER]).toEqual(asTheServerRanksThem);
  });

  it("places every status the server can rank, so no bucket falls off the end", () => {
    // The SQL's `Else` is a real branch: a status the `CASE` does not name sorts after all
    // of them. Both sides name all six, and this is what says so.
    const unplaced = TICKET_STATUSES.filter((status) => !WORKFLOW_ORDER.includes(status));
    expect(unplaced).toEqual([]);
  });
});

describe("categories", () => {
  // Guards the two predicates against the day a sixth category is added: they are written
  // as exclusions, so a new category is open and counted unless somebody says otherwise.
  it("reads the same five meanings the server does", () => {
    const categories = new Set<StatusCategory>(TICKET_STATUSES.map(categoryOf));
    expect([...categories].sort()).toEqual([
      "backlog",
      "canceled",
      "completed",
      "started",
      "unstarted",
    ]);
  });
});
