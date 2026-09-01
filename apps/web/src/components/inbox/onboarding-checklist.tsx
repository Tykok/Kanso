"use client";

import { useMemo } from "react";
import { useSetupState, useTeams, useTickets } from "@/lib/queries";
import { categoryOf } from "@/lib/status";
import { cn } from "@/lib/utils";
import { checklist } from "./first-session";

/**
 * Screen 08's checklist, which lives in the sidebar's footer and disappears once the
 * four gestures are done.
 *
 * Its own component under `components/inbox/` because `components/sidebar.tsx` is
 * read-only to this branch. It needs **one mount point**: the first child of the
 * sidebar's existing `mt-auto` footer block (currently the "Show archived" toggle and
 * the sync line), which is exactly where the drawing puts it — above the Notion status
 * line, below everything else. The integration pass makes that edit; this file needs no
 * props, so it is one line.
 *
 * Returns null when complete, so the sidebar does not have to know when to stop drawing
 * it, and null while the three queries are still in flight — a checklist that flashes
 * "0 of 4" at somebody with a full board is worse than one that arrives a beat late.
 */
export function OnboardingChecklist() {
  const teams = useTeams();
  const tickets = useTickets();
  const setup = useSetupState();

  const state = useMemo(() => {
    const rows = tickets.data ?? [];
    return checklist({
      teams: (teams.data ?? []).length,
      tickets: rows.length,
      // "Moved it along" against the status the composer leaves a ticket in. See
      // `first-session.ts` for why this is the honest approximation available.
      advanced: rows.some((ticket) => {
        const category = categoryOf(ticket.status);
        return category !== "backlog" && category !== "unstarted";
      }),
      notionConfigured: setup.data?.notion.configured ?? false,
    });
  }, [teams.data, tickets.data, setup.data]);

  const loading = teams.isLoading || tickets.isLoading || setup.isLoading;
  if (loading || state.complete) return null;

  return (
    <section
      data-testid="onboarding-checklist"
      className="flex flex-col gap-2.5 rounded-lg bg-accent p-3"
      aria-label="Getting started"
    >
      <div className="flex items-center gap-2">
        <span className="flex-1 text-11 tracking-[0.1em] text-faint uppercase">Getting started</span>
        <span className="font-mono text-11 text-muted-foreground">
          {state.done}/{state.total}
        </span>
      </div>

      {/* One segment per step rather than a single filled bar: the steps are not done in
          order, so a percentage would say less than four boxes do. */}
      <div className="flex gap-[3px]" aria-hidden>
        {state.steps.map((step) => (
          <div
            key={step.id}
            className={cn("h-[3px] flex-1 rounded-sm", step.done ? "bg-primary" : "bg-border")}
          />
        ))}
      </div>

      <ol className="m-0 flex list-none flex-col gap-1.5 p-0 text-12">
        {state.steps.map((step) => {
          const isNext = state.next?.id === step.id;
          return (
            <li
              key={step.id}
              className={cn(
                "flex items-center gap-2",
                step.done ? "text-faint" : isNext ? "text-foreground" : "text-muted-foreground",
              )}
            >
              <span
                className={cn(
                  "flex size-[13px] shrink-0 items-center justify-center rounded-sm text-11",
                  step.done
                    ? "bg-status-done text-primary-foreground"
                    : cn("border-[1.5px]", isNext ? "border-primary" : "border-border"),
                )}
              >
                {step.done ? "✓" : ""}
              </span>
              <span className={step.done ? "line-through" : undefined}>{step.label}</span>
            </li>
          );
        })}
      </ol>
    </section>
  );
}
