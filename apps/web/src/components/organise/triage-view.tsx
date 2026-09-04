"use client";

import { useCallback, useMemo, useState } from "react";
import { GroupLabel } from "@/components/ui/group-label";
import { Kbd } from "@/components/ui/kbd";
import { Backdrop } from "@/components/overlays";
import { RequestBases } from "@/components/settings/request-bases";
import { actionErrorMessage } from "@/lib/errors";
import { canConfigure as configures } from "@/lib/seat";
import type { Ticket, TriageDecision } from "@/lib/api";
import { ShellAside, TopbarSlot, usePageShell } from "@/components/shell/topbar-slot";
import { usePageActions } from "@/components/shell/use-shell-keys";
import { actionById } from "@/lib/actions";
import { isMac } from "@/lib/platform";
import { hintFor } from "@/lib/shortcuts";
import { useActionContext } from "@/lib/use-action-ctx";
import { useBindings } from "@/lib/use-bindings";
import { useDecide, useMe, useSimilar, useTriageQueue } from "@/lib/queries";
import { useOrganiseTeam } from "./team";

/**
 * The four rulings, each naming the registry action that carries its key and its label.
 *
 * They used to carry both here — a `key` and a `label` in this literal, dispatched by this
 * file's own `keydown` and printed on its own buttons. So the queue was the fourth place
 * in the app where a key was written down, and the only surface that could not be told
 * about a remap. `?` did not list these four at all. The keys are unchanged; where they
 * are written down is not.
 */
const DECISIONS: { action: string; decision: TriageDecision; primary?: boolean }[] = [
  { action: "triage.accept", decision: "accepted", primary: true },
  { action: "triage.defer", decision: "backlogged" },
  { action: "triage.duplicate", decision: "duplicate" },
  { action: "triage.reject", decision: "closed" },
];

/**
 * Screen 20 — one incoming ticket at a time, four keys, each advancing to the next.
 *
 * The cursor is an index into the queue rather than a ticket id: a decision removes the
 * ticket it was about, so after every write the id the cursor held no longer exists. An
 * index survives that and lands on whatever moved up into the slot, which is what "each
 * decision passes to the next ticket" means.
 */
export function TriageView() {
  const { team } = useOrganiseTeam();
  const queue = useTriageQueue(team?.id);
  const decide = useDecide();
  const [cursor, setCursor] = useState(0);
  const [error, setError] = useState<string | null>(null);
  /**
   * The requests panel, and who is looking at it.
   *
   * `useMe` is already mounted by the shell above this route, so this is a read of a cache
   * rather than a second request — and it decides what the panel *draws*, never what it may
   * do: all three of `RequestBaseController`'s routes ask the server again.
   */
  const [wiring, setWiring] = useState(false);
  const me = useMe();

  // Memoised because it feeds `useActionContext`, which memoises on it: `?? []` is a
  // fresh array every render, and the context rebuilt on every render would re-publish to
  // the shell and re-attach the one `keydown` listener with it.
  const items = useMemo(() => queue.data?.items ?? [], [queue.data]);
  /**
   * The queue shrinks under the cursor as decisions land, so the stored index can point
   * past the end. It is clamped here, during the render that needs it, rather than
   * corrected by an effect: writing state back from an effect is a second render for a
   * value this expression already knows, and the stored index is deliberately left alone
   * so that walking back with `p` returns to where it was.
   */
  const at = Math.min(cursor, Math.max(items.length - 1, 0));
  const current = items[at];
  const similar = useSimilar(current?.id);

  /**
   * `triage.duplicate` needs something to point at, and the only candidate on screen is
   * the top of the similarity list — which is exactly what the panel is for. With nothing,
   * it is refused rather than silently downgraded to "close": a duplicate of nothing is
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

  /**
   * The four rulings, claimed, and the cursor left to the registry.
   *
   * `rule` needs the queue, the cursor and the similarity list — three things
   * `ActionContext` does not carry and must not grow — so this screen supplies the bodies
   * and the registry owns the keys (`lib/actions/claims.ts`). `mode: "triage"` is what
   * lets `x` close a ticket here and archive one everywhere else.
   *
   * `n` `p` `↑` `↓` are gone from this file entirely: the context below publishes the
   * queue and a step, so they are `ticket.moveDown` and `ticket.moveUp` — the same two
   * actions the list and the board answer, which is "next row, written out by hand three
   * times" written out once. `selected` stays undefined on purpose, so the six status
   * keys and `↵` remain as inert as they were before this route had a context at all: a
   * triage decision is not a status change, and offering both would be two ways to rule.
   */
  usePageActions({
    "triage.accept": () => rule("accepted"),
    "triage.defer": () => rule("backlogged"),
    "triage.duplicate": () => rule("duplicate"),
    "triage.reject": () => rule("closed"),
  });

  const reportError = useCallback((message: string | null) => setError(message), []);
  const noop = useCallback(() => {}, []);
  const step = useCallback(
    (delta: number) => setCursor(Math.min(Math.max(at + delta, 0), Math.max(items.length - 1, 0))),
    [at, items.length],
  );
  const ctx = useActionContext({
    tickets: items,
    selected: undefined,
    move: step,
    startRename: noop,
    startLink: noop,
    startUnlink: noop,
    reportError,
  });

  // `Core / Triage`. The queue's own position is in the bar's right end, not in the crumb:
  // "3 of 40" is where the reader is *in* the queue, not where the queue is in the app.
  usePageShell({ ctx, crumbs: { team: team?.name } });

  const { keys } = useBindings();
  const mac = isMac();
  const keyOf = (id: string) => hintFor(actionById(id), keys, mac);

  return (
    <>
      <TopbarSlot>
        <span className="flex-1" />
        <span className="flex items-center gap-2">
          {items.length > 0 && (
            <span>
              {at + 1} of {queue.data?.total ?? items.length}
            </span>
          )}
          {/* Read off the effective bindings, not typed: these printed `j` and `k`, both
              of which §6.4 drops. */}
          {[keyOf("ticket.moveDown"), keyOf("ticket.moveUp")].flatMap((chord) =>
            chord === undefined ? [] : [<Kbd key={chord}>{chord}</Kbd>],
          )}
        </span>
      </TopbarSlot>

      <ShellAside>
        <Queue
          items={items}
          total={queue.data?.total ?? 0}
          at={at}
          onPick={setCursor}
          onWiring={() => setWiring(true)}
        />
      </ShellAside>

      {wiring && (
        /*
         * `Backdrop` and an inner `role="dialog"`, the way `ImportDialog` does it — not
         * `ui/dialog.tsx`, whose Radix root takes the focus and the `Escape` key, and this
         * screen's four ruling keys are published to the shell's one listener. A dialog that
         * fought that would be a keyboard question in a task that is not one; `KAN-69` owns
         * that family this batch.
         */
        <Backdrop onClose={() => setWiring(false)} panelClassName="w-[min(620px,94vw)]">
          <div
            role="dialog"
            aria-modal="true"
            aria-label="Notion requests"
            className="flex flex-col gap-2.5 p-2.5"
          >
            <RequestBases canConfigure={configures(me.data?.user.instanceRole)} />
            <div className="flex justify-end">
              <button type="button" className="button" onClick={() => setWiring(false)}>
                Close
              </button>
            </div>
          </div>
        </Backdrop>
      )}

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

          {/* `whitespace-pre-line`, following `publik/contributor.tsx`: a description is one
              text column and HTML collapses its newlines, which nothing on this screen
              noticed while every ticket in the queue had been typed into the composer as a
              paragraph. A siphoned Notion request is not that — it carries the preserved
              "Imported from Notion" section, one property per line — and collapsed it reads
              as `Client: Acme Urgence: Bloquant Deal size: 42000` in a single run-on
              sentence, which is the one part of a request the person triaging most needs to
              scan. The ticket page never showed this because it renders the description in a
              `<textarea>`, which keeps its own newlines. */}
          {current.description && (
            <p className="m-0 max-w-[640px] whitespace-pre-line leading-[1.65] text-muted-foreground">
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
                key={entry.action}
                type="button"
                className={entry.primary ? "button button-primary" : "button"}
                disabled={decide.isPending}
                onClick={() => rule(entry.decision)}
              >
                {actionById(entry.action).label}{" "}
                <span className="font-mono text-11 opacity-70">{keyOf(entry.action)}</span>
              </button>
            ))}
          </div>

          <span className="text-11 text-faint">
            Each decision moves to the next ticket. Nothing is lost: the queue keeps a record of
            what was closed.
          </span>
        </div>
      )}
    </>
  );
}

function Queue({
  items,
  total,
  at,
  onPick,
  onWiring,
}: {
  items: Ticket[];
  total: number;
  at: number;
  onPick: (index: number) => void;
  onWiring: () => void;
}) {
  return (
    <>
      <div className="flex flex-col items-start gap-0.5 px-1.5 pb-2.5">
        <span className="text-13 font-medium text-foreground">Triage queue</span>
        <span className="text-11 text-faint">{total} tickets · oldest first</span>
        {/*
         * The second way in to the requests base, the first being the settings page.
         *
         * It belongs on this screen because this is the screen the arrangement is *about*:
         * the reason a queue is empty, or full of pages nobody in Kanso wrote, is which
         * Notion base is wired to it — and answering that two routes away, in a settings tab,
         * is the mistake `KAN-53` corrected by putting the mirror's refused writes beside the
         * connection that produced them.
         *
         * In the header rather than under the rows, which is where it was first drawn: the
         * aside scrolls, so a fortieth ticket in the queue pushed the button off the bottom
         * of a panel that is the only place it appears. Seen in a capture, not reasoned about.
         *
         * Offered to every member, not only to a configurator, which is `KAN-55`'s
         * arbitration and not a decision retaken here: the panel behind it draws the
         * workspace for anybody and the team picker for the configurator alone. Hiding it
         * from a member would contradict that arbitration in the one place where a member is
         * looking at its consequences.
         */}
        <button type="button" className="button mt-1.5" onClick={onWiring}>
          Where these come from…
        </button>
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
          {/* `shrink-0` for the reason `WillSlip` in `cycle-view.tsx` carries it: a flex
              item that may shrink is allowed to break `KAN-36` after its hyphen, and the
              identifier would then sit on two lines over the title beside it. Nothing on
              screen shows it today only because this panel is capped at 640px — which is
              correct by accident, and stops being true the day somebody widens the cap.
              No width, because the identifier is the one cell here that cannot be
              abbreviated: it takes what it needs and the title truncates into the rest. */}
          <span className="shrink-0 font-mono text-11 text-faint">{row.ticket.identifier}</span>
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
