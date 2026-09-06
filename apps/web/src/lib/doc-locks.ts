import { ApiError, type DocBlock, type DocBlockLock } from "@/lib/api";

/**
 * `KAN-25`'s lock, as the screen has to reason about it.
 *
 * Pure functions over the two facts the server sends — **who** holds a block and **when it
 * frees itself** — so that every one of them is testable in `environment: "node"` and none
 * of them is a `useEffect` nobody can assert. What is left in the components is drawing.
 *
 * The one thing this module is *for*, and the reason it exists rather than three inline
 * ternaries: a lock is a **refusal**, and this repository has already shipped a silent
 * refusal twice. `useReportError` exists because a refused unarchive said nothing on four
 * routes. A greyed-out paragraph that does not name anybody is that bug with better
 * manners, so the sentence a blocked person reads is built here, once, and used by both
 * the badge on the block and the line under the top bar.
 */

/**
 * How long to wait before renewing a claim, from the claim itself.
 *
 * **Derived, not a constant.** The TTL is `KANSO_DOCS_LOCK_TTL` on the server and the
 * browser is never told what it is — only when *this* lock lapses. A constant here would
 * be a second number facing the first one across a network, and the failure when they
 * disagree is silent and one-sided: an instance tuned down to three seconds would have
 * every caret lose its block between renewals, and the person typing would find their
 * paragraph refused mid-sentence with no idea why. A third of the remaining window
 * renews twice before lapsing, which survives one dropped request and a slow one.
 *
 * Floored at a second so a lock that is already expiring cannot spin the renewal into a
 * request loop, and that floor is the only number in this file that is not the server's.
 */
export function renewDelayMs(lock: DocBlockLock, now: Date): number {
  const remaining = new Date(lock.freesAt).getTime() - now.getTime();
  if (!Number.isFinite(remaining)) return MIN_RENEW_MS;
  return Math.max(MIN_RENEW_MS, Math.round(remaining / 3));
}

const MIN_RENEW_MS = 1_000;

/**
 * Seconds until the block frees itself, never negative.
 *
 * Clamped at zero rather than allowed to go negative because a lapsed lock is not a lock
 * — the server stops reporting it on the next read — and a countdown that goes on into
 * `-4 s` would describe a state nothing is in.
 */
export function freesInSeconds(lock: DocBlockLock, now: Date): number {
  const remaining = new Date(lock.freesAt).getTime() - now.getTime();
  if (!Number.isFinite(remaining)) return 0;
  return Math.max(0, Math.ceil(remaining / 1000));
}

/**
 * The lock on this block held by **somebody else**, or nothing.
 *
 * The whole of "may I type here" in one call, and it answers `undefined` in the two cases
 * that mean yes: nobody holds it, or this reader does. `lockedBy` is absent rather than
 * null when there is no lock — Jackson omits nulls — which `?.` handles and a `=== null`
 * test would not.
 */
export function heldByOther(block: DocBlock, meId: string | undefined): DocBlockLock | undefined {
  const lock = block.lockedBy;
  if (!lock) return undefined;
  return lock.userId === meId ? undefined : lock;
}

/**
 * What the person who cannot type is told, in one sentence.
 *
 * Three facts, and the ticket's brief insists on all three: **who** has it, that it
 * **frees itself**, and **when**. Dropping the third turns "wait" into "give up" — a
 * reader who is not told the block comes back has no reason to believe it will, and will
 * either reload the page or go and interrupt somebody.
 *
 * Built on the client from `freesAt` rather than printed from the server's own message,
 * so the countdown is right at the moment it is *rendered*. The server's sentence says
 * "in a moment" for exactly this reason: it cannot know how long its answer spent in
 * flight, and it is the fallback for a client that never reads these two fields.
 *
 * "frees itself" and not "will be released": nobody has to do anything, which is the
 * reassuring half and the true one — `V40`'s expiry is a timestamp compared on read, so
 * it lapses whether or not the holder's browser, the API or a sweeper is alive.
 */
export function lockSentence(holder: string, freesAt: string, now: Date): string {
  const seconds = freesInSeconds({ freesAt } as DocBlockLock, now);
  if (seconds <= 0) return `${holder} is editing this block. It is freeing itself now.`;
  return `${holder} is editing this block. It frees itself in ${seconds} s.`;
}

/**
 * The badge drawn on the block itself: a name and a countdown, and nothing else.
 *
 * Short because it sits inside the paragraph's own row, beside text somebody is reading.
 * The sentence goes in the top bar, where [lockSentence] has room to explain; this is the
 * label that says which paragraph the sentence is about.
 */
export function lockBadge(lock: DocBlockLock, now: Date): string {
  const seconds = freesInSeconds(lock, now);
  return seconds <= 0 ? `${lock.displayName} · freeing` : `${lock.displayName} · ${seconds} s`;
}

/** What a refused write carries back: who has it, and when it lets go. */
export type LockRefusal = { holder: string; freesAt: string };

/**
 * Reads a 409 back into the two facts, or answers nothing.
 *
 * `ApiError.body` is the whole RFC 7807 document and `ApiExceptionHandler.blockLocked`
 * puts `holder` and `freesAt` on it as extra members — the shape `countsChanged` already
 * established for "a refusal the person can act on". Anything else, including a 409 from
 * somewhere else entirely, answers `undefined` and the caller falls back to `detail`,
 * which is why the server writes that message for a person in the first place.
 *
 * Typed defensively on purpose: this parses a network payload, and a `holder` that
 * arrived as a number would otherwise reach the screen as `[object Object] is editing
 * this block`.
 */
export function lockRefusal(error: unknown): LockRefusal | undefined {
  if (!(error instanceof ApiError) || error.status !== 409) return undefined;
  const body = error.body as Record<string, unknown> | undefined;
  const holder = body?.holder;
  const freesAt = body?.freesAt;
  if (typeof holder !== "string" || typeof freesAt !== "string") return undefined;
  return { holder, freesAt };
}

/**
 * The line `useReportError` prints for a refused keystroke.
 *
 * Falls back to the error's own `detail` rather than to silence, and that fallback is the
 * point of this function existing beside [lockRefusal]: every path through it says
 * *something*. A refusal this screen could not parse still reaches the person as the
 * server's own sentence, which names the holder too.
 */
export function refusalMessage(error: unknown, now: Date): string | null {
  const refusal = lockRefusal(error);
  if (refusal) return lockSentence(refusal.holder, refusal.freesAt, now);
  if (error instanceof ApiError) return error.detail;
  return error instanceof Error ? error.message : null;
}
