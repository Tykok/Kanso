import type { PullRequest } from "@/lib/api";

/**
 * What a pull request row on a ticket says, in one place.
 *
 * Pure and its own module rather than a helper inside the component, for the reason
 * `inbox/copy.ts` gives and for one more that is specific here: `apps/web`'s vitest runs
 * with `environment: "node"` and picks up `.ts` only, so a rule that lives inside a `.tsx`
 * has no test. The pill is a three-column truth table — exactly the thing that is wrong in a
 * way nobody notices, because every branch of it still renders *something*.
 */

/** The six a ticket can show, and the intent behind each. */
export type PrTone = "draft" | "review" | "blocked" | "approved" | "merged" | "closed";

export type PrPill = { label: string; tone: PrTone };

/**
 * The pill, read off `state`, `draft` and `reviewState` **together**.
 *
 * `state` alone is not what a person wants to know. "Open" is the answer to a question
 * nobody asked; whether anybody has looked at it yet is the one they did.
 *
 * The order of the branches is the precedence, and it is not arbitrary:
 *
 *  * `merged` and `closed` first, because they are terminal. A merged pull request that
 *    still carried `changes_requested` from before it was fixed would otherwise render as
 *    blocked forever — the review state is not cleared when a review is addressed, only
 *    superseded, so a terminal state has to win over it.
 *  * `draft` next, because a draft is not in review whatever a reviewer left on it.
 *  * then the review state, with absence meaning "In review": nobody has answered yet.
 */
export function prPill(pr: PullRequest): PrPill {
  if (pr.state === "merged") return { label: "Merged", tone: "merged" };
  if (pr.state === "closed") return { label: "Closed", tone: "closed" };
  if (pr.draft) return { label: "Draft", tone: "draft" };
  if (pr.reviewState === "changes_requested") {
    return { label: "Changes requested", tone: "blocked" };
  }
  if (pr.reviewState === "approved") return { label: "Approved", tone: "approved" };
  return { label: "In review", tone: "review" };
}

/** `tykok/kanso#418`, which is what a person pastes into a chat message. */
export function prRef(pr: PullRequest): string {
  return `${pr.repo}#${pr.number}`;
}

/**
 * Why this pull request is on this ticket — shown as a title attribute rather than a
 * visible line, because it matters only when somebody is surprised by it.
 *
 * The two halves are the two things that behave differently: whether it can move the
 * ticket, and whether re-parsing the branch or the body can take the link away.
 */
export function prLinkExplanation(pr: PullRequest): string {
  const effect = pr.closes
    ? "Closes this ticket when merged."
    : "Mentions this ticket; it will not move it.";
  const origin = pr.linkedByMember
    ? "Linked by a member, so an edit to the branch or the body will not remove it."
    : "Detected from the branch, the title or the body.";
  return `${effect} ${origin}`;
}

/**
 * Repository first, then descending number — the same order the server sends, restated
 * here so a client that merges two responses cannot draw them in arrival order.
 */
export function sortPullRequests(prs: PullRequest[]): PullRequest[] {
  return [...prs].sort(
    (a, b) => a.repo.localeCompare(b.repo) || b.number - a.number,
  );
}
