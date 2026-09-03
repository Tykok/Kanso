import { describe, expect, it } from "vitest";
import type { PullRequest } from "@/lib/api";
import { prLinkExplanation, prPill, prRef, sortPullRequests } from "./pr-copy";

const pr = (over: Partial<PullRequest> = {}): PullRequest => ({
  repo: "tykok/kanso",
  number: 418,
  title: "Warn on overlap",
  url: "https://github.com/tykok/kanso/pull/418",
  state: "open",
  draft: false,
  authorLogin: "elie",
  headRef: "feat/kan-142-overlap-warning",
  closes: true,
  linkedByMember: false,
  ...over,
});

describe("what the pill says", () => {
  it("reads open with nobody reviewing as in review", () => {
    expect(prPill(pr())).toEqual({ label: "In review", tone: "review" });
  });

  it("reads a draft as draft, whatever else is true of it", () => {
    expect(prPill(pr({ draft: true })).label).toBe("Draft");
    expect(prPill(pr({ draft: true, reviewState: "approved" })).label).toBe("Draft");
  });

  it("names an approval and a block apart", () => {
    expect(prPill(pr({ reviewState: "approved" }))).toEqual({
      label: "Approved",
      tone: "approved",
    });
    expect(prPill(pr({ reviewState: "changes_requested" }))).toEqual({
      label: "Changes requested",
      tone: "blocked",
    });
  });

  it("reads merged and closed apart, which state alone can say", () => {
    expect(prPill(pr({ state: "merged" })).label).toBe("Merged");
    expect(prPill(pr({ state: "closed" })).label).toBe("Closed");
  });

  /**
   * The precedence that is easy to get wrong. A review state is superseded rather than
   * cleared when the changes are made, so a merged pull request can still carry
   * `changes_requested` — and reading the review state first would leave it blocked forever
   * on a ticket that is finished.
   */
  it("lets a terminal state win over a review that was never cleared", () => {
    expect(prPill(pr({ state: "merged", reviewState: "changes_requested" })).label).toBe(
      "Merged",
    );
    expect(prPill(pr({ state: "closed", draft: true })).label).toBe("Closed");
  });

  /**
   * The trap this file was written against: the server omits nulls, so an unreviewed pull
   * request arrives with **no** `reviewState` key rather than `reviewState: null`. Both
   * spellings have to land on the same pill, or a `=== null` test somewhere renders nothing.
   */
  it("treats an absent review state and an explicit undefined alike", () => {
    const absent = pr();
    delete (absent as { reviewState?: unknown }).reviewState;
    expect(prPill(absent).label).toBe("In review");
    expect(prPill(pr({ reviewState: undefined })).label).toBe("In review");
  });
});

describe("what a row prints", () => {
  it("names the repository and the number the way a person pastes it", () => {
    expect(prRef(pr())).toBe("tykok/kanso#418");
  });
});

describe("why a pull request is on a ticket", () => {
  it("separates what it will do from whether the link can vanish", () => {
    expect(prLinkExplanation(pr({ closes: true, linkedByMember: false }))).toBe(
      "Closes this ticket when merged. Detected from the branch, the title or the body.",
    );
    expect(prLinkExplanation(pr({ closes: false, linkedByMember: true }))).toBe(
      "Mentions this ticket; it will not move it. " +
        "Linked by a member, so an edit to the branch or the body will not remove it.",
    );
  });
});

describe("the order rows are drawn in", () => {
  it("groups by repository, newest number first", () => {
    const rows = sortPullRequests([
      pr({ repo: "tykok/kanso", number: 400 }),
      pr({ repo: "tykok/other", number: 7 }),
      pr({ repo: "tykok/kanso", number: 418 }),
    ]);
    expect(rows.map((r) => `${r.repo}#${r.number}`)).toEqual([
      "tykok/kanso#418",
      "tykok/kanso#400",
      "tykok/other#7",
    ]);
  });

  it("does not reorder the caller's array in place", () => {
    const given = [pr({ number: 1 }), pr({ number: 2 })];
    sortPullRequests(given);
    expect(given.map((r) => r.number)).toEqual([1, 2]);
  });
});
