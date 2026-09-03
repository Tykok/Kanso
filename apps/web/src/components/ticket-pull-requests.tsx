"use client";

import { useState } from "react";
import type { PullRequest, Ticket } from "@/lib/api";
import {
  prLinkExplanation,
  prPill,
  prRef,
  sortPullRequests,
  type PrTone,
} from "./github/pr-copy";

/**
 * A ticket's pull requests, and the branch to start them from.
 *
 * The rules live in `github/pr-copy.ts` and this file draws them, which is the split
 * `inbox/copy.ts` established and which `apps/web`'s vitest config makes structural rather
 * than stylistic: the suite is `environment: "node"` over `.ts` only, so a truth table
 * inside a `.tsx` is a truth table with no test.
 *
 * **Nothing is drawn when there is nothing to say** — no empty heading, no "no pull
 * requests yet". `TicketFields` records the reason: a row that says "none" is a row that
 * teaches a reader to ignore that part of the panel. A draft has no branch and no links, so
 * the whole section is absent for one.
 */
export function TicketPullRequests({
  ticket,
}: {
  /** Only the three fields this reads, the narrowness `TicketFields` takes. */
  ticket: Pick<Ticket, "pullRequests" | "branchName">;
}) {
  // `?? []` rather than trusting the type: this component is drawn from a cache entry that a
  // build one deploy older may have written without the key, and the required-ness of
  // `pullRequests` is a promise about the *server*, not about localStorage.
  const rows = sortPullRequests(ticket.pullRequests ?? []);

  if (rows.length === 0 && ticket.branchName == null) return null;

  return (
    <div className="flex flex-col gap-2">
      <span className="text-11 text-faint">Pull requests</span>
      {ticket.branchName != null && <BranchToCopy branch={ticket.branchName} />}
      {rows.map((pr) => (
        <PullRequestRow key={`${pr.repo}#${pr.number}`} pr={pr} />
      ))}
    </div>
  );
}

/**
 * The branch name, selectable and copyable.
 *
 * A read-only `<input>` and not a `<code>`, following `CopyableSecret`: the selection is the
 * guarantee and the button is the convenience. `navigator.clipboard` is absent on any
 * instance served over plain HTTP to something that is not localhost, and the optional call
 * quietly does nothing there — so a person can still select the field and copy by hand.
 *
 * There is no `[Open the PR]` beside it, and that absence is the honest state of this build
 * rather than an oversight: opening a pull request needs the write path and the two
 * identities, which are part five of the design. What a ticket offers today is the name.
 */
function BranchToCopy({ branch }: { branch: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <div className="flex min-w-0 items-center gap-2">
      <input
        readOnly
        aria-label="Branch name for this ticket"
        className="min-w-0 flex-1 text-12"
        style={{ fontFamily: "var(--font-mono)" }}
        value={branch}
        // Selects from offset 0 rather than `select()`, the fix `selectFromTheStart`
        // documents: a caret left at the end scrolls the field past the `feat/kan-142`
        // prefix, which is the only part of a branch name a person checks.
        onFocus={(event) => {
          event.currentTarget.setSelectionRange(0, branch.length, "backward");
          event.currentTarget.scrollLeft = 0;
        }}
      />
      <button
        className="button"
        onClick={() => {
          void navigator.clipboard?.writeText(branch).then(() => setCopied(true));
        }}
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

/**
 * `tykok/kanso#418`, the title, a pill, the author, and the link out.
 *
 * A plain `<a>` and not `next/link`: this leaves the app. `rel="noreferrer"` alongside
 * `target="_blank"` because the destination is a URL the server read off a webhook payload,
 * and `noopener` is what stops it reaching back into this tab.
 */
function PullRequestRow({ pr }: { pr: PullRequest }) {
  const pill = prPill(pr);

  return (
    <div className="flex min-w-0 items-center gap-2" title={prLinkExplanation(pr)}>
      <a
        className="min-w-0 flex-1 truncate text-13 hover:underline"
        href={pr.url}
        target="_blank"
        rel="noopener noreferrer"
      >
        <span className="text-faint" style={{ fontFamily: "var(--font-mono)" }}>
          {prRef(pr)}
        </span>{" "}
        {pr.title}
      </a>
      {/* Not a closing link, so it displays and does nothing. Marked, because a person
          looking at a merged pull request on a ticket that did not move needs the reason
          on screen rather than in a tooltip they have no cause to open. */}
      {!pr.closes && <span className="text-11 text-faint">mention</span>}
      <PrStatePill label={pill.label} tone={pill.tone} />
      {pr.authorLogin != null && (
        <span className="shrink-0 text-11 text-faint">@{pr.authorLogin}</span>
      )}
    </div>
  );
}

/**
 * The tone-to-colour map, in one place.
 *
 * Six tones and six entries: a `Record<PrTone, string>` rather than a lookup with a
 * fallback, so adding a seventh tone to `PrTone` is a type error here instead of a pill
 * that silently renders unstyled.
 */
const TONE_CLASS: Record<PrTone, string> = {
  draft: "text-faint",
  review: "text-foreground",
  blocked: "text-destructive",
  approved: "text-success",
  // `text-accent-ink` and **not** `text-accent`: `tokens.css` says outright that shadcn's
  // `--accent` is the hover fill rather than the brand colour, so `text-accent` is a pale
  // tint of the page ground — a "Merged" pill that is very nearly invisible, and invisible
  // in a way no test would fail on. `--accent-ink` is the token for the accent read as text.
  merged: "text-accent-ink",
  closed: "text-faint",
};

function PrStatePill({ label, tone }: { label: string; tone: PrTone }) {
  return (
    <span className={`shrink-0 whitespace-nowrap text-11 ${TONE_CLASS[tone]}`}>{label}</span>
  );
}
