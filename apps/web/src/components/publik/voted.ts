/**
 * Which tickets this browser has already voted on.
 *
 * The server cannot answer this. It keys a vote to a day-scoped hash of an address and
 * keeps no way back to the voter — that is the whole point of `VoterKeys` — so "have I
 * voted" is a question only the visitor's own browser can hold. `localStorage`, not a
 * cookie: nothing is sent to the server, and a page with no session should not start one
 * just to remember a click.
 *
 * Being wrong is cheap in both directions. A cleared browser draws the control unpressed
 * and the next click is a no-op the server absorbs; a shared machine draws it pressed for
 * the wrong person, who loses a vote they had not cast. Neither is worth a session.
 *
 * Exposed as a `useSyncExternalStore` triple rather than read into state inside an
 * effect. That is what localStorage *is* — an external store — and reading it the other
 * way meant a render, an effect, and a second render on every roadmap load, plus a stale
 * control in a second tab. `getServerSnapshot` returns null because the server genuinely
 * knows nothing here, which is also what makes the first client render match it.
 */
const KEY = "kanso.publicVotes";

const listeners = new Set<() => void>();

/** Reads the set, tolerating anything at all in the slot — including a value we never wrote. */
export function parseVoted(raw: string | null): Set<string> {
  if (!raw) return new Set();
  try {
    const parsed: unknown = JSON.parse(raw);
    return new Set(Array.isArray(parsed) ? parsed.filter((k) => typeof k === "string") : []);
  } catch {
    return new Set();
  }
}

export const serialiseVoted = (keys: Set<string>): string => JSON.stringify([...keys]);

export function subscribeVoted(listener: () => void): () => void {
  listeners.add(listener);
  // `storage` fires in the *other* tabs, never the one that wrote, which is why this
  // module also notifies its own listeners in `rememberVote`. Both are needed.
  window.addEventListener("storage", listener);
  return () => {
    listeners.delete(listener);
    window.removeEventListener("storage", listener);
  };
}

/**
 * The raw string, not a parsed set: `useSyncExternalStore` compares snapshots by
 * identity, and a fresh `Set` on every read would loop forever.
 */
export const votedSnapshot = (): string | null => window.localStorage.getItem(KEY);

export const votedServerSnapshot = (): string | null => null;

export function rememberVote(key: string): void {
  const keys = parseVoted(votedSnapshot());
  keys.add(key);
  window.localStorage.setItem(KEY, serialiseVoted(keys));
  for (const listener of listeners) listener();
}
