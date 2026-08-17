"use client";

import { useEffect, useState } from "react";
import { GroupLabel } from "@/components/ui/group-label";
import { Kbd } from "@/components/ui/kbd";
import { actionErrorMessage } from "@/lib/errors";
import type { Ticket, TriageDecision } from "@/lib/api";
import { useDecide, useSimilar, useTriageQueue } from "@/lib/queries";
import { OrganiseShell, useOrganiseTeam } from "./shell";

/**
 * Screen 20 — one incoming ticket at a time, four keys, each advancing to the next.
 *
 * The cursor is an index into the queue rather than a ticket id: a decision removes the
 * ticket it was about, so after every write the id the cursor held no longer exists. An
 * index survives that and lands on whatever moved up into the slot, which is what "each
 * decision passes to the next ticket" means.
 */
const DECISIONS: { key: string; decision: TriageDecision; label: string; primary?: boolean }[] = [
  { key: "a", decision: "accepted", label: "Accept into the cycle", primary: true },
  { key: "b", decision: "backlogged", label: "Send to backlog" },
  { key: "d", decision: "duplicate", label: "Mark duplicate" },
  { key: "x", decision: "closed", label: "Close without action" },
];

export function TriageView() {
  const { team } = useOrganiseTeam();
  const queue = useTriageQueue(team?.id);
  const decide = useDecide();
  const [cursor, setCursor] = useState(0);
  const [error, setError] = useState<string | null>(null);

  const items = queue.data?.items ?? [];
  /**
   * The queue shrinks under the cursor as decisions land, so the stored index can point
   * past the end. It is clamped here, during the render that needs it, rather than
   * corrected by an effect: writing state back from an effect is a second render for a
   * value this expression already knows, and the stored index is deliberately left alone
   * so that walking back with `k` returns to where it was.
   */
  const at = Math.min(cursor, Math.max(items.length - 1, 0));
  const current = items[at];
  const similar = useSimilar(current?.id);

  /**
   * `d` needs something to point at, and the only candidate on screen is the top of the
   * similarity list — which is exactly what the panel is for. With nothing to point at,
   * `d` is refused rather than silently downgraded to "close": a duplicate of nothing is
   * not a duplicate, and the server refuses it too.
   */
  const rule = (decision: TriageDecision) => {
    if (current === undefined) return;
    const duplicateOfId = similar.data?.[0]?.ticket.id;
    if (decision === "duplicate" && duplicateOfId === undefined) {
      setError("Nothing here looks like a duplicate of this ticket.");
      return;
    }
    setError(null);
    decide.mutate(
      { ticketId: current.id, decision, duplicateOfId: decision === "duplicate" ? duplicateOfId : undefined },
      { onError: (failure) => setError(actionErrorMessage(failure)) },
    );
  };

  useEffect(() => {
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.metaKey || event.ctrlKey || event.altKey) return;
      const target = event.target;
      if (target instanceof HTMLElement && ["INPUT", "TEXTAREA", "SELECT"].includes(target.tagName)) return;

      if (event.key === "j" || event.key === "ArrowDown") {
        event.preventDefault();
        setCursor(Math.min(at + 1, Math.max(items.length - 1, 0)));
        return;
      }
      if (event.key === "k" || event.key === "ArrowUp") {
        event.preventDefault();
        setCursor(Math.max(at - 1, 0));
        return;
      }
      const ruling = DECISIONS.find((entry) => entry.key === event.key);
      if (ruling) {
        event.preventDefault();
        rule(ruling.decision);
      }
    };
    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
  });

  return (
    <OrganiseShell
      breadcrumb={
        <>
          <span>{team?.name ?? "…"}</span>
          <span>/</span>
          <span className="text-muted-foreground">Triage</span>
        </>
      }
      trailing={
        <span className="flex items-center gap-2">
          {items.length > 0 && (
            <span>
              {at + 1} of {queue.data?.total ?? items.length}
            </span>
          )}
          <Kbd>j</Kbd>
          <Kbd>k</Kbd>
        </span>
      }
      aside={<Queue items={items} total={queue.data?.total ?? 0} at={at} onPick={setCursor} />}
    >
      {queue.isPending && <div className="px-4 py-12 text-center text-faint">Loading…</div>}

      {!queue.isPending && current === undefined && (
        <div className="empty flex-col gap-1">
          <span className="text-13 text-foreground">Nothing to triage</span>
          <span className="text-12 text-faint">
            Every ticket in this team has been ruled on or is already in a cycle.
          </span>
        </div>
      )}

      {current && (
        <div className="flex min-h-0 flex-1 flex-col gap-[22px] overflow-y-auto px-8 py-6">
          <div className="flex flex-col gap-2">
            <span className="font-mono text-11 text-faint">
              {current.identifier} · opened {ago(current.createdAt)}
            </span>
            <h2 className="m-0 text-21 leading-[1.3] font-medium tracking-[-0.014em]">
              {current.title}
            </h2>
          </div>

          {current.description && (
            <p className="m-0 max-w-[640px] leading-[1.65] text-muted-foreground">
              {current.description}
            </p>
          )}

          <Similar rows={similar.data ?? []} />

          {error && (
            <div className="topbar-error" role="alert">
              <span>{error}</span>
            </div>
          )}

          <div className="mt-auto flex flex-wrap gap-2">
            {DECISIONS.map((entry) => (
              <button
                key={entry.key}
                type="button"
                className={entry.primary ? "button button-primary" : "button"}
                disabled={decide.isPending}
                onClick={() => rule(entry.decision)}
              >
                {entry.label} <span className="font-mono text-11 opacity-70">{entry.key}</span>
              </button>
            ))}
          </div>

          <span className="text-11 text-faint">
            Each decision moves to the next ticket. Nothing is lost: the queue keeps a record of
            what was closed.
          </span>
        </div>
      )}
    </OrganiseShell>
  );
}

function Queue({
  items,
  total,
  at,
  onPick,
}: {
  items: Ticket[];
  total: number;
  at: number;
  onPick: (index: number) => void;
}) {
  return (
    <>
      <div className="flex flex-col gap-0.5 px-1.5 pb-2.5">
        <span className="text-13 font-medium text-foreground">Triage queue</span>
        <span className="text-11 text-faint">{total} tickets · oldest first</span>
      </div>
      {items.map((ticket, index) => (
        <button
          key={ticket.id}
          type="button"
          data-testid="triage-row"
          aria-current={index === at}
          onClick={() => onPick(index)}
          className={
            index === at
              ? "flex flex-col gap-0.5 rounded-md bg-accent-soft px-2.5 py-2 text-left shadow-[inset_2px_0_0_var(--primary)]"
              : "flex flex-col gap-0.5 rounded-md px-2.5 py-2 text-left text-muted-foreground hover:bg-accent"
          }
        >
          <span
            className={index === at ? "font-mono text-11 text-accent-ink" : "font-mono text-11 text-faint"}
          >
            {ticket.identifier}
          </span>
          <span className="text-12 leading-[1.4]">{ticket.title}</span>
          <span className="text-11 text-faint">{ago(ticket.createdAt)}</span>
        </button>
      ))}
    </>
  );
}

/** "Looks like KAN-142 — 68 %". Trigram similarity on the title, scored by Postgres. */
function Similar({ rows }: { rows: { ticket: Ticket; similarity: number }[] }) {
  if (rows.length === 0) return null;

  return (
    <div className="flex max-w-[640px] flex-col gap-2.5 rounded-lg bg-card p-4">
      <GroupLabel className="px-0 pt-0">Looks like</GroupLabel>
      {rows.map((row) => (
        <div key={row.ticket.id} className="flex items-center gap-2.5 text-12" data-testid="similar-row">
          <span className="font-mono text-11 text-faint">{row.ticket.identifier}</span>
          <span className="flex-1 truncate text-muted-foreground">{row.ticket.title}</span>
          <span className="text-11 text-faint">{row.similarity} %</span>
        </div>
      ))}
    </div>
  );
}

/** Coarse on purpose: the queue is about "how long has this waited", not about clocks. */
function ago(iso: string): string {
  const hours = Math.floor((Date.now() - new Date(iso).getTime()) / 3_600_000);
  if (hours < 1) return "just now";
  if (hours < 24) return `${hours} h ago`;
  const days = Math.floor(hours / 24);
  return days === 1 ? "1 day ago" : `${days} days ago`;
}
