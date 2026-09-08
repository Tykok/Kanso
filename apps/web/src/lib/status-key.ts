/**
 * The key a status label produces — the second copy of `dev.kanso.domain.statusKeyOf`.
 *
 * A second copy on purpose, and the house already accepts that shape for exactly this
 * reason: `lib/status-order.ts` keeps `CATEGORY_ORDER` beside the server's, pinned equal by
 * a test on each side, because sending it would put a fetch between a control and knowing
 * how to draw itself. The same argument applies here and one more with it — the statuses
 * screen refuses a duplicate *before* the request, so the reader gets the sentence as they
 * type rather than after a round trip. A pre-check that derived the key differently from
 * the server would refuse a word the server accepts, or accept one it refuses, and both
 * are worse than no pre-check.
 *
 * `statusKeyTable` in `status-key.test.ts` is the table both sides are asserted against.
 *
 * Case is dropped and accents fold, so `In Progress`, `in progress` and `IN  PROGRESS` are
 * one key — the primary key over `(team_id, key)` refuses the second, and two spellings of
 * one word being the same row is better than a rule every interface has to enforce.
 *
 * Returns `null` for a label with nothing alphanumeric in it, where the Kotlin throws
 * `BadRequestException`. The shapes differ because the jobs do: the server is refusing a
 * request, this is deciding whether to show a field as invalid.
 */
export function statusKeyOf(label: string): string | null {
  const folded = label
    // NFD splits `é` into `e` plus a combining mark; `\p{Mn}` is that mark's category, and
    // stripping it is what folds the accent. The same two steps as the Kotlin, in order.
    .normalize("NFD")
    .replace(/\p{Mn}+/gu, "")
    .toLowerCase();
  const key = folded.replace(/[^a-z0-9]+/g, "_").replace(/^_+|_+$/g, "");
  return key === "" ? null : key;
}
