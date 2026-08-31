import type { NotionPeopleView, NotionPersonSeen } from "@/lib/api";

/**
 * A Notion person a screen is asking about, joined with what the standing correspondence
 * (`notionPeopleApi.view()`) already knows — the same shape whether the person came from
 * `people-seen`'s narrower "who the plan met" answer or from the workspace's full member
 * list the settings screen reads directly. Shared here so the import step and the settings
 * screen cannot pre-fill, or label a row "suggested", on different terms.
 */
export type PersonRow = {
  id: string;
  name?: string;
  email?: string;
  /** What `users.notion_person_id` already says. Never a guess. */
  userId?: string;
  /** A proposal — matched by email, then by name — never applied without a decision. */
  suggestedUserId?: string;
};

/** The account a row's `<select>` opens on: the confirmed link first, the suggestion next. */
export function preselectedAccount(row: { userId?: string; suggestedUserId?: string }): string {
  return row.userId ?? row.suggestedUserId ?? "";
}

/**
 * True for a row that would leave a guess in place if nobody looked at it — the case the
 * word "suggested" exists to flag, so accepting it reads as a decision rather than a
 * default nobody noticed. False once the correspondence already confirms the row, even
 * when the confirmed account happens to equal the guess.
 */
export function isSuggested(row: { userId?: string; suggestedUserId?: string }): boolean {
  return row.userId === undefined && row.suggestedUserId !== undefined;
}

/**
 * `people-seen`'s answer joined against the workspace's standing correspondence, so step 4
 * can pre-fill a suggestion it did not itself ask for. A person `people-seen` reports but
 * the correspondence has never heard of — the read is unavailable, or the page names a
 * guest outside `GET /users` — still gets a row, with neither a link nor a suggestion.
 *
 * Sorted by name then id, because `people-seen` promises no order of its own and a row
 * list that reshuffled itself on every refetch would be a screen fighting the reader
 * trying to look down it.
 */
export function seenPeopleRows(seen: NotionPersonSeen[], view: NotionPeopleView): PersonRow[] {
  const known = new Map(view.people.map((match) => [match.notion.id, match]));
  return seen
    .map((person): PersonRow => {
      const match = known.get(person.id);
      return {
        id: person.id,
        name: person.name ?? match?.notion.name,
        email: match?.notion.email,
        userId: match?.userId,
        suggestedUserId: match?.suggestedUserId,
      };
    })
    .sort((a, b) => (a.name ?? "").localeCompare(b.name ?? "") || a.id.localeCompare(b.id));
}
