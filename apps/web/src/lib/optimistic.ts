// Type-only, both of them, for the reason `lib/realtime-events.ts` gives: what is left is
// a module of plain functions and two small classes, testable in `environment: "node"`
// with no React, no fetch and no DOM.
import type { Label, Ticket } from "./api";
import { findTicket, writeTickets, type EventCache } from "./realtime-events";

/**
 * What one mutation believes it is about to do to one thing.
 *
 * `null` means the thing goes away — a delete, an archive out of a list that hides
 * archived work. Written as a function of the current value rather than as a patch
 * object so that guesses *stack*: two of them in flight over the same row compose by
 * being applied in turn, which is the whole of [Guesses] below.
 */
export type Guess<T> = (value: T) => T | null;

type Layer<T> = { handle: number; subject: string; guess: Guess<T> };

/**
 * The guesses still in flight, and what the cache would say without them.
 *
 * Snapshot-and-restore is the obvious way to do an optimistic update and it is wrong the
 * moment two of them overlap: the second mutation snapshots the *first one's guess*, so
 * whichever fails first restores a picture that never existed — either resurrecting a
 * change that was refused, or throwing away one that was not.
 *
 * So nothing here restores. What is kept per subject is the last value the server
 * described, and the cache is always that value with every live guess folded over it,
 * oldest first. A guess settling is a layer being removed and the fold being run again;
 * a rollback is the same operation as a success, with one fewer layer. That makes both
 * orderings come out right without either mutation knowing the other exists.
 *
 * Pure bookkeeping: it holds no cache and writes nothing. [TicketGuesses] is what joins
 * it to one.
 */
export class Guesses<T> {
  private next = 1;
  private readonly layers: Layer<T>[] = [];
  private readonly bases = new Map<string, T | null>();

  /**
   * Records a guess about [subject].
   *
   * [base] is asked only the first time, because after that the cache already holds
   * base-plus-guesses and reading it again would fold the first guess into the base —
   * where no rollback could ever reach it.
   *
   * Answers handle `0` when there is nothing to guess *from*: a subject no cache holds
   * has nothing on screen to paint, and [close] on `0` is a no-op, so no caller needs a
   * branch for it.
   */
  open(
    subject: string,
    base: () => T | null | undefined,
    guess: Guess<T>,
  ): { handle: number; value: T | null | undefined } {
    if (!this.bases.has(subject)) {
      const found = base();
      if (found === undefined) return { handle: 0, value: undefined };
      this.bases.set(subject, found);
    }
    const handle = this.next++;
    this.layers.push({ handle, subject, guess });
    return { handle, value: this.value(subject) };
  }

  /**
   * The mutation came back, either way.
   *
   * [settled] is what the server said it wrote — a value replaces the base, `null` means
   * the subject is gone for good, and leaving it out keeps the base, which is what a
   * refusal means. Answers what the subject should now show, or `null` when the handle
   * was never open.
   */
  close(handle: number, settled?: T | null): { subject: string; value: T | null } | null {
    const index = this.layers.findIndex((layer) => layer.handle === handle);
    const layer = this.layers[index];
    if (!layer) return null;
    const { subject } = layer;
    this.layers.splice(index, 1);
    if (settled !== undefined) this.bases.set(subject, settled);

    const value = this.value(subject) ?? null;
    // Nothing in flight, nothing to fold: keeping the base would be this module slowly
    // becoming a second copy of the query cache.
    if (!this.layers.some((other) => other.subject === subject)) this.bases.delete(subject);
    return { subject, value };
  }

  /** The base with every live guess folded over it, oldest first. */
  value(subject: string): T | null | undefined {
    if (!this.bases.has(subject)) return undefined;
    // Annotated, not inferred: `?? null` widens to `NonNullable<T> | null`, and a guess
    // hands back a `T` that a caller is free to have made nullable.
    let value: T | null = this.bases.get(subject) ?? null;
    for (const layer of this.layers) {
      if (value === null) break;
      if (layer.subject === subject) value = layer.guess(value);
    }
    return value;
  }

  /**
   * A fresher truth about [subject], from wherever truth arrives — the realtime feed.
   *
   * It becomes the new base and the live guesses are folded back over it, so an event
   * about a row somebody else just edited lands without undoing a change of this
   * reader's own that the server has not been told about yet. A subject with nothing in
   * flight is handed straight back, untracked: this is not a place to accumulate rows.
   */
  rebase(subject: string, server: T): T | null {
    if (!this.bases.has(subject)) return server;
    this.bases.set(subject, server);
    return this.value(subject) ?? null;
  }

  /** Nothing anywhere is still in flight — so a refetch cannot land on top of a guess. */
  get idle(): boolean {
    return this.layers.length === 0;
  }
}

// --- tickets -----------------------------------------------------------------

/**
 * [Guesses] joined to the ticket cache.
 *
 * Every write goes through `writeTickets`, which is `lib/realtime-events.ts`'s own — a
 * guess about a row and an event about a row are the same operation with a different
 * source, and two implementations of "which cached lists hold this ticket" is exactly
 * the duplication that file was written to end. Painting through it is also what makes
 * the guess reach *every* list holding the row and the ticket page's single-row entry,
 * rather than only the one list the mutation's own hook happened to know a key for.
 */
export class TicketGuesses {
  private readonly guesses = new Guesses<Ticket>();
  private waiting: (() => void)[] = [];

  /** Paints a guess about one ticket. The handle settles it; `0` is nothing to settle. */
  open(cache: EventCache, id: string, guess: Guess<Ticket>): number {
    const { handle, value } = this.guesses.open(id, () => findTicket(cache, id), guess);
    if (handle) this.paint(cache, id, value);
    return handle;
  }

  /** The request came back. [settled] is the row the server wrote, or `null` if it is gone. */
  close(cache: EventCache, handle: number, settled?: Ticket | null): void {
    const done = this.guesses.close(handle, settled);
    if (done) this.paint(cache, done.subject, done.value);
    // Drained after the paint, and only on the way to idle: a settle that still leaves
    // another mutation in flight has not made the cache safe to write over.
    if (this.waiting.length && this.idle) {
      const tasks = this.waiting;
      this.waiting = [];
      for (const task of tasks) task();
    }
  }

  /**
   * Runs [task] once nothing of this tab's is in flight — now, if nothing is.
   *
   * `resumeAfterOutage` is the caller, and the only one: a reconnect is the single
   * operation here that writes over the whole cache rather than over rows it can name,
   * so it is the single one that has to wait for a guess it cannot see.
   *
   * The queue is drained rather than replaced, but a second outage while one is already
   * queued adds a second sweep of the same cache — harmless, and cheaper to allow than
   * to deduplicate.
   */
  whenIdle(task: () => void): void {
    if (this.idle) task();
    else this.waiting.push(task);
  }

  /**
   * A row that is new to the cache — what a create hands back.
   *
   * Not a guess, and deliberately not painted at a position: where the row sorts is the
   * server's order, so `writeTickets` refetches the keys that have to gain it and only
   * those.
   */
  arrived(cache: EventCache, row: Ticket): void {
    writeTickets(cache, { changed: new Map([[row.id, row]]), overlay: this.fold });
  }

  /**
   * `CacheTarget.overlay`. Bound, because it is handed to the realtime applier as a
   * value and would otherwise arrive without its ledger.
   */
  readonly fold = (ticket: Ticket): Ticket | null => this.guesses.rebase(ticket.id, ticket);

  get idle(): boolean {
    return this.guesses.idle;
  }

  private paint(cache: EventCache, id: string, value: Ticket | null | undefined): void {
    if (value === undefined) return;
    writeTickets(
      cache,
      value ? { changed: new Map([[id, value]]) } : { gone: new Set([id]) },
    );
  }
}

/**
 * One ledger per tab, beside the one query client it writes into.
 *
 * A module-level instance rather than a context, for the same reason `queryCache` is a
 * function over the client and not a hook: the realtime applier in `app/providers.tsx`
 * and the mutation hooks in `lib/queries` have to be talking about the same guesses, and
 * the socket's side of that is not React. The class is exported so tests get their own.
 */
export const ticketGuesses = new TicketGuesses();

/**
 * What a `PATCH /api/tickets/{id}` will have done to the row, as far as the client can
 * tell — which for these fields is exactly, because the server stores what it is sent.
 */
export function patchedTicket(
  body: Partial<Ticket>,
  unset: readonly string[] = [],
): Guess<Ticket> {
  return (ticket) => {
    const patched: Ticket = { ...ticket, ...body };
    // JSON cannot tell an absent key from an explicit null, so the server takes a list of
    // fields to clear. The guess has to clear them too, or the value the person just
    // removed sits there until the response lands.
    for (const field of unset) delete (patched as Record<string, unknown>)[field];
    // The mirror is asynchronous by design: the moment a row changes locally, Notion is
    // behind. Show that rather than imply it landed. A disabled mirror stays disabled —
    // a patch is not a thing that can switch it on.
    patched.mirror = {
      ...ticket.mirror,
      state: ticket.mirror.state === "disabled" ? "disabled" : "pending",
    };
    return patched;
  };
}

/** A delete. Out of every list that holds it, and the page 404s. */
export const removedTicket: Guess<Ticket> = () => null;

// --- labels ------------------------------------------------------------------

/**
 * The pills a ticket will be wearing, resolved out of its team's labels.
 *
 * Sorted by name because `LabelRepository.forTicket` is — guessing the set right and the
 * order wrong would still be a visible shuffle when the response lands, which is the
 * flicker an optimistic update exists to avoid. An id the catalogue cannot name is
 * dropped: a blank pill is a worse answer than a missing one, and the response is a
 * moment away.
 */
export function wornLabels(labelIds: readonly string[], catalogue: readonly Label[]): Label[] {
  const known = new Map(catalogue.map((label) => [label.id, label]));
  return labelIds
    .map((id) => known.get(id))
    .filter((label): label is Label => label !== undefined)
    .sort((a, b) => a.name.localeCompare(b.name));
}
