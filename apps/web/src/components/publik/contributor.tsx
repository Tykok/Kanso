"use client";

import Link from "next/link";
import { useContributorPage } from "@/lib/queries/publik";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";
import type { ContributorPage } from "@/lib/api/publik";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  BEFORE_YOU_START,
  CONTRIBUTING_URL,
  DISCUSSIONS_URL,
  REVIEW_PROMISE,
  WHY_YOU_CAN_SEE_THIS,
} from "./copy";
import { VoteButton } from "./vote-button";

/** Screen 28: one open ticket, and the context somebody needs to start on it. */
export function Contributor({ ticketKey }: { ticketKey: string }) {
  const page = useContributorPage(ticketKey);

  if (page.isPending) return <p className="px-6 py-12 text-13 text-faint md:px-10">Loading…</p>;

  if (page.isError || !page.data) {
    return (
      <div className="flex flex-col gap-3 px-6 py-16 md:px-10">
        <h1 className="text-21 font-medium">Not published</h1>
        {/* Deliberately the same message for "no such ticket" and "not public": the
            server answers 404 for both, and saying which it was would tell a stranger
            that the ticket exists. */}
        <p className="max-w-lg text-13 text-muted-foreground">
          There is no published ticket <span className="font-mono">{ticketKey}</span>. It may
          never have existed, or it may not be part of the public roadmap.
        </p>
        <Link href="/roadmap" className="text-13 text-primary hover:underline">
          Back to the roadmap
        </Link>
      </div>
    );
  }

  return (
    <div className="grid flex-1 items-start gap-0 lg:grid-cols-[1fr_400px]">
      <Explanation page={page.data} />
      <Aside page={page.data} />
    </div>
  );
}

function Explanation({ page }: { page: ContributorPage }) {
  return (
    <div className="flex flex-col gap-6 px-6 py-8 md:px-10">
      <div className="flex max-w-2xl flex-col gap-2.5">
        <span className="font-mono text-11 uppercase tracking-[0.12em] text-faint">
          {/* "N available" counts published tickets nobody has claimed. Once the
              foundation's labels land this narrows to the `good first step` label, which
              is strictly fewer tickets — so the number can only ever have been too
              generous, never a claim that no ticket backs. */}
          Unclaimed · {page.unclaimedCount} available
        </span>
        <h1 className="text-30 font-medium leading-tight tracking-[-0.02em]">{page.title}</h1>
        <div className="flex flex-wrap items-center gap-2">
          <Badge variant="outline" className="gap-2 bg-card font-normal text-muted-foreground">
            <span
              aria-hidden
              className="size-2 rounded-full border-[1.5px]"
              style={{ borderColor: STATUS_COLORS[page.status] }}
            />
            {STATUS_LABELS[page.status]}
          </Badge>
          {page.unclaimed ? (
            <Badge variant="outline" className="bg-card font-normal text-faint">
              nobody on it
            </Badge>
          ) : null}
          <span className="font-mono text-11 text-faint">{page.key}</span>
          <VoteButton ticketKey={page.key} votes={page.votes} />
        </div>
      </div>

      {page.explanation ? (
        <p className="max-w-2xl whitespace-pre-line text-13 leading-relaxed text-muted-foreground">
          {page.explanation}
        </p>
      ) : null}

      {page.whereToLook.length > 0 ? (
        <section className="flex max-w-2xl flex-col gap-2.5">
          <h2 className="text-11 uppercase tracking-[0.1em] text-faint">Where to look</h2>
          <ul className="flex flex-col gap-0.5 text-12">
            {page.whereToLook.map((pointer) => (
              <li
                key={pointer.path}
                className="flex h-8 items-center gap-3 rounded-md bg-card px-3"
              >
                <span className="truncate font-mono text-muted-foreground">{pointer.path}</span>
                <span className="flex-1" />
                {pointer.note ? <span className="shrink-0 text-faint">{pointer.note}</span> : null}
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <div className="flex flex-wrap gap-2.5">
        {/* Both lead to the repository, because that is where a stranger can actually
            say either of these things: the ticket's own discussion is the foundation's
            `comments` table, which the schema does not have yet, and an anonymous
            visitor has no account to post with in any case. */}
        <Button size="lg" asChild>
          <a href={DISCUSSIONS_URL} target="_blank" rel="noreferrer">
            I&apos;ll take it
          </a>
        </Button>
        <Button size="lg" variant="outline" asChild>
          <a href={DISCUSSIONS_URL} target="_blank" rel="noreferrer">
            Ask a question
          </a>
        </Button>
      </div>
    </div>
  );
}

function Aside({ page }: { page: ContributorPage }) {
  return (
    <aside className="flex h-full flex-col gap-6 bg-card px-7 py-6 lg:border-l lg:border-rule">
      <section className="flex flex-col gap-3">
        <h2 className="text-11 uppercase tracking-[0.1em] text-faint">Before you start</h2>
        <ul className="flex flex-col gap-2.5 text-12 text-muted-foreground">
          {BEFORE_YOU_START.map((step, index) => (
            <li key={step.text} className="flex gap-2.5">
              {/* The first two are ticked in the drawing because they are things the
                  project has already done for you; the last two are yours. Not state —
                  there is no session here to hold any. */}
              <span
                aria-hidden
                style={{ color: index < 2 ? STATUS_COLORS.done : undefined }}
                className={index < 2 ? undefined : "text-faint"}
              >
                {index < 2 ? "✓" : "○"}
              </span>
              <span className="flex-1">
                {step.text}
                {"code" in step && step.code ? (
                  <>
                    {" "}
                    <code className="font-mono text-11">{step.code}</code>
                  </>
                ) : null}
              </span>
            </li>
          ))}
        </ul>
        <a
          href={CONTRIBUTING_URL}
          target="_blank"
          rel="noreferrer"
          className="text-12 text-primary hover:underline"
        >
          Read the contributing guide
        </a>
      </section>

      <hr className="border-border" />

      <section className="flex flex-col gap-3">
        <h2 className="text-11 uppercase tracking-[0.1em] text-faint">Who can help</h2>
        {page.helpers.length === 0 ? (
          <p className="text-12 text-faint">
            Nobody has joined this team yet — the discussions are the place to ask.
          </p>
        ) : (
          <ul className="flex flex-col gap-2.5 text-12">
            {page.helpers.map((helper) => (
              <li key={helper.displayName} className="flex items-center gap-2.5">
                <span
                  aria-hidden
                  className="grid size-6 shrink-0 place-items-center rounded-full bg-accent-soft text-11 text-accent-ink"
                >
                  {initialsOf(helper.displayName)}
                </span>
                <span className="flex-1">
                  <span className="font-medium">{helper.displayName}</span>
                  {helper.role === "admin" ? (
                    <span className="text-faint"> · reviews the changes</span>
                  ) : null}
                </span>
              </li>
            ))}
          </ul>
        )}
        <p className="text-11 text-faint">{REVIEW_PROMISE}</p>
      </section>

      {page.otherFirstSteps.length > 0 ? (
        <>
          <hr className="border-border" />
          <section className="flex flex-col gap-3">
            <h2 className="text-11 uppercase tracking-[0.1em] text-faint">
              Other unclaimed tickets
            </h2>
            <ul className="flex flex-col gap-0.5 text-12">
              {page.otherFirstSteps.map((entry) => (
                <li key={entry.key}>
                  <Link
                    href={`/roadmap/${entry.key}`}
                    className="flex h-8 items-center gap-2.5 rounded-sm bg-background px-2.5 text-muted-foreground hover:text-foreground"
                  >
                    <span className="font-mono text-11 text-faint">{entry.key}</span>
                    <span className="truncate">{entry.title}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </section>
        </>
      ) : null}

      <p className="mt-auto rounded-lg bg-background px-3.5 py-3 text-12 text-muted-foreground">
        {WHY_YOU_CAN_SEE_THIS}
      </p>
    </aside>
  );
}

/**
 * `J. Salas` → `JS`. First letter of the first two words, which is what the drawing
 * shows and what survives a name with one word, three words, or an initial with a dot.
 */
function initialsOf(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? "")
    .join("");
}
