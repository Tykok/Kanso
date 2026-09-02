"use client";

import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { emptyReason } from "./first-session";

/**
 * What an empty list says instead of apologising.
 *
 * Three screens in one component because they are one decision — see [emptyReason].
 * The component that hosts a ticket list hands over the three numbers and gets back the
 * right screen; it does not get to pick, because picking is where "nothing here" ends up
 * over a filter with fourteen tickets behind it.
 *
 * Needs one mount point: `components/tickets.tsx`'s own `tickets.length === 0` branch,
 * which currently prints "Nothing here. Press c to create a ticket." That file is
 * read-only to this branch, so the integration pass makes the swap. Reported.
 */
export function EmptyState({
  total,
  filter,
  ticketsAnywhere,
  onClearFilter,
  onSeeAll,
  onCreate,
  notionConnected,
  onConnectNotion,
}: {
  /** Tickets in this scope before the filter. */
  total: number;
  filter: string;
  /** Tickets anywhere in the instance. Tells a new install from a new team. */
  ticketsAnywhere: number;
  onClearFilter: () => void;
  onSeeAll: () => void;
  onCreate: () => void;
  notionConnected: boolean;
  onConnectNotion: () => void;
}) {
  const reason = emptyReason({ total, filter, ticketsAnywhere });

  if (reason.kind === "filtered") {
    return (
      <div
        data-testid="empty-filtered"
        className="flex flex-1 flex-col justify-center gap-3.5 px-10"
      >
        {/* Three faint rules where the rows would be: the shape of the thing that is
            missing, which is the drawing's own way of saying "a list belongs here". */}
        <div className="flex flex-col gap-px opacity-45" aria-hidden>
          <div className="h-px bg-border" />
          <div className="h-7" />
          <div className="h-px bg-border" />
          <div className="h-7" />
          <div className="h-px bg-border" />
        </div>

        <div className="flex flex-col gap-1">
          <span className="text-15 font-medium">Nothing matching “{reason.filter}”</span>
          <span className="text-12 text-muted-foreground">
            It is the filter, not the database: {reason.outside}{" "}
            {reason.outside === 1 ? "ticket exists" : "tickets exist"} outside it.
          </span>
        </div>

        <div className="flex gap-2">
          <Button variant="outline" onClick={onClearFilter}>
            Remove “{reason.filter}”
          </Button>
          <Button variant="outline" onClick={onSeeAll}>
            See all
          </Button>
        </div>
      </div>
    );
  }

  if (reason.kind === "emptyScope") {
    return (
      <div data-testid="empty-scope" className="empty flex-col gap-1">
        <span className="text-13 text-foreground">Nothing here yet</span>
        <span className="text-12 text-faint">
          Press <Kbd>c</Kbd> to create the first ticket in this view.
        </span>
        {/* The keystroke stays because it is the one that teaches the key, but it cannot
            be the only way in: this is the screen with nothing else on it to click, and
            it is reached by a scope change rather than by the first run, so nobody has
            been shown where `New` lives yet. Same label and same callback as the
            first-session card below, not a second wording of the same act — one gesture
            that two screens offer should not be two sentences to learn. */}
        <Button className="mt-3" onClick={onCreate}>
          Create the first one
        </Button>
      </div>
    );
  }

  return (
    <FirstSession
      onCreate={onCreate}
      notionConnected={notionConnected}
      onConnectNotion={onConnectNotion}
    />
  );
}

/**
 * Screen 08's other half: three gestures, and no tour.
 *
 * The drawing is explicit — "pas de surcouche, pas de bulles à cliquer : l'écran vide
 * fait le travail". So this is the page, not something over it: no overlay, no
 * step-through, nothing to dismiss. Somebody who ignores it entirely and presses `c`
 * has done step one, and the screen is gone.
 */
function FirstSession({
  onCreate,
  notionConnected,
  onConnectNotion,
}: {
  onCreate: () => void;
  notionConnected: boolean;
  onConnectNotion: () => void;
}) {
  return (
    <div data-testid="first-session" className="flex flex-1 justify-center overflow-y-auto px-10 pt-20">
      <div className="flex w-[760px] max-w-full flex-col gap-9">
        <div className="flex max-w-[560px] flex-col gap-3">
          <span className="text-11 tracking-[0.14em] text-faint uppercase">This team is ready</span>
          <h2 className="m-0 text-30 leading-tight font-medium tracking-tight text-pretty">
            Three gestures are enough to hold Kanso
          </h2>
          <p className="m-0 text-15 leading-relaxed text-muted-foreground text-pretty">
            Everything is on the keyboard, and nothing has to be memorised: the bar along
            the bottom keeps saying what is available here.
          </p>
        </div>

        <div className="grid gap-4 min-[900px]:grid-cols-3">
          {/* Step one carries a 2px inset rule in the accent — the only marked card,
              because it is the only one with something to press right now. */}
          <div className="flex flex-col gap-3 rounded-lg bg-card p-[18px] shadow-flat shadow-[inset_0_2px_0_var(--primary)]">
            <div className="flex items-center gap-2">
              <Kbd className="border-primary text-accent-ink">c</Kbd>
              <span className="text-11 tracking-[0.1em] text-faint uppercase">Step 1</span>
            </div>
            <span className="text-15 font-medium">Create a ticket</span>
            <p className="m-0 text-13 leading-relaxed text-muted-foreground text-pretty">
              A title is enough. The rest — project, date, priority — is settled afterwards,
              from the row.
            </p>
            <Button className="mt-auto self-start" onClick={onCreate}>
              Create the first one
            </Button>
          </div>

          <div className="flex flex-col gap-3 rounded-lg bg-card p-[18px] shadow-flat">
            <div className="flex items-center gap-2">
              <Kbd>1</Kbd>
              <span className="text-11 text-faint">–</span>
              <Kbd>6</Kbd>
              <span className="text-11 tracking-[0.1em] text-faint uppercase">Step 2</span>
            </div>
            <span className="text-15 font-medium">Move it along</span>
            <p className="m-0 text-13 leading-relaxed text-muted-foreground text-pretty">
              From backlog to canceled, in order. The row changes status without leaving the
              list.
            </p>
            <div className="mt-auto flex items-center gap-1.5 text-11 text-faint">
              <span className="size-2 rounded-full border-[1.5px] border-status-backlog" />
              <span>→</span>
              <span className="size-2 rounded-full border-[1.5px] border-status-progress" />
              <span>→</span>
              <span className="size-2 rounded-full border-[1.5px] border-status-done bg-status-done" />
            </div>
          </div>

          <div className="flex flex-col gap-3 rounded-lg bg-card p-[18px] shadow-flat">
            <div className="flex items-center gap-2">
              <Kbd>⌘K</Kbd>
              <span className="text-11 tracking-[0.1em] text-faint uppercase">Step 3</span>
            </div>
            <span className="text-15 font-medium">Find anything</span>
            <p className="m-0 text-13 leading-relaxed text-muted-foreground text-pretty">
              Tickets, documents and commands in the same field. If you are unsure, start
              there.
            </p>
            <span className="mt-auto text-11 text-faint">
              Also: <Kbd>?</Kbd> lists every shortcut
            </span>
          </div>
        </div>

        {/* Fourth, and deliberately not a step: Kanso works without Notion, and the
            drawing gives this row its own "Later" rather than a place in the sequence. */}
        {!notionConnected && (
          <div className="flex flex-wrap items-center gap-4 rounded-lg bg-accent p-[18px]">
            <div className="flex min-w-[240px] flex-1 flex-col gap-0.5">
              <span className="text-13 font-medium">Connect Notion, later if you prefer</span>
              <span className="text-12 text-muted-foreground text-pretty">
                Kanso works without it. Once connected, every write goes out to a copy the
                rest of the team can read.
              </span>
            </div>
            <Button variant="outline" onClick={onConnectNotion}>
              Connect now
            </Button>
          </div>
        )}
      </div>
    </div>
  );
}
