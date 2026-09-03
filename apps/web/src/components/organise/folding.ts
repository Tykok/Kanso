import type { Group } from "./grouping";

/**
 * Sub-tickets in a grouped, virtualised list: which rows sit under which, and which of
 * them a fold hides.
 *
 * No React, no DOM — `environment: "node"`, like `lib/virtual.ts` and `work-buckets.ts`.
 * This is the rule, and the rule is where KAN-59 went wrong last time.
 *
 * ---------------------------------------------------------------------------
 * What a bucket count means once rows can be folded
 * ---------------------------------------------------------------------------
 * It means exactly what it meant before: **how many tickets match in this bucket**, from
 * the server's one `GROUP BY` over the whole match. It is not, and never was, "how many
 * rows are drawn underneath this header" — the page is capped at 200 rows while the count
 * is uncapped, so a bucket reading 340 has always drawn fewer rows than it claims.
 *
 * That is the load-bearing part of KAN-59's lesson. Folding is a *client-side*
 * visibility decision, so the honest response to it is to leave the count alone: a folded
 * child is still a ticket in that bucket, still work in that state, and a count that
 * dropped when somebody clicked a chevron would be answering a question nobody asked
 * ("how many rows can I currently see"). What must never happen — and this module's tests
 * pin it — is [count] being recomputed from the rows that survive a fold, because that is
 * precisely the disagreement KAN-59 was about, arriving from the other direction.
 *
 * ---------------------------------------------------------------------------
 * Grouping by status puts a child in its own bucket, not its parent's
 * ---------------------------------------------------------------------------
 * A child has its own status, so a parent in `Todo` with a finished child has that child
 * under `Done`. Nothing here moves it: the buckets are the server's answer and a client
 * that re-bucketed rows to keep families together would make every bucket disagree with
 * its own count, which is the failure this file exists to avoid.
 *
 * The consequence is that a fold only ever hides children that are in the *same* bucket
 * as their parent, and a parent shows a chevron only when it has some. A parent whose
 * children are all elsewhere is drawn as an ordinary row — it still carries its progress,
 * which is the part that does not depend on where the children were filed.
 *
 * ---------------------------------------------------------------------------
 * The one reordering, and why it is not the one `grouping.ts` forbids
 * ---------------------------------------------------------------------------
 * `grouping.ts` states that grouping never reorders inside a group, or the view's
 * `sortBy` silently stops working. Children are moved to sit directly after their parent,
 * which looks like exactly that and is not: the order is preserved **within each level**.
 * Parents keep the server's order among parents, siblings keep it among siblings, and no
 * row overtakes a row it is not related to. That is how a sort applies to a hierarchy at
 * all, and the alternative is worse than a reorder — a fold that hid three rows from the
 * middle of a bucket, nowhere near the chevron that was clicked.
 */
export type FoldableTicket = { id: string; parentId?: string | null };

export type Folded<T> = {
  /** The groups as drawn: children ordered under their parent, folded ones absent. */
  groups: (Omit<Group, "tickets"> & { tickets: T[] })[];
  /**
   * How many children each parent has *in its own bucket* — so a chevron is drawn only
   * where there is something to fold, and never on a parent whose children are elsewhere.
   */
  foldable: Map<string, number>;
  /** The ids drawn one level in, so a row knows to indent without re-deriving why. */
  nested: Set<string>;
};

/**
 * Orders children under their parents and drops the ones a collapsed parent is hiding.
 *
 * [count] is copied across untouched, deliberately and with a test on it.
 */
export function foldGroups<T extends FoldableTicket>(
  groups: readonly (Omit<Group, "tickets"> & { tickets: T[] })[],
  collapsed: ReadonlySet<string>,
): Folded<T> {
  const foldable = new Map<string, number>();
  const nested = new Set<string>();

  const folded = groups.map((group) => {
    const present = new Set(group.tickets.map((ticket) => ticket.id));
    // A parent only counts as a parent *here* if it is in this bucket too. A child whose
    // parent is filed under another status is drawn where the server put it, flat.
    const childrenOf = new Map<string, T[]>();
    const roots: T[] = [];
    for (const ticket of group.tickets) {
      const parentId = ticket.parentId;
      if (parentId != null && parentId !== ticket.id && present.has(parentId)) {
        const siblings = childrenOf.get(parentId) ?? [];
        siblings.push(ticket);
        childrenOf.set(parentId, siblings);
        nested.add(ticket.id);
      } else {
        roots.push(ticket);
      }
    }

    const tickets: T[] = [];
    for (const root of roots) {
      tickets.push(root);
      const children = childrenOf.get(root.id);
      if (children === undefined) continue;
      foldable.set(root.id, children.length);
      if (!collapsed.has(root.id)) tickets.push(...children);
    }

    // `count` is the server's, and it stays the server's.
    return { ...group, tickets };
  });

  return { groups: folded, foldable, nested };
}
