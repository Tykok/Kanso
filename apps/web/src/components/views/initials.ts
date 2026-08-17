/**
 * The two letters an avatar carries.
 *
 * Four of slice A's screens draw one — the ticket page's assignee chip, the board card,
 * the project page's lead, the search preview — and the app had never drawn an avatar
 * before, so this is the first and only place the rule is written.
 *
 * Two words give their two initials; one word gives its first two letters, because a
 * single-letter circle reads as a bullet rather than as a person. Anything with no
 * letters in it at all gives the em dash the drawings use for "nobody", which is the
 * honest answer for an unassigned ticket and for a display name that is only emoji.
 */
export const NOBODY = "—";

export function initialsOf(displayName: string | undefined | null): string {
  const words = (displayName ?? "").trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return NOBODY;
  if (words.length === 1) {
    // `Array.from` rather than `slice(0, 2)`: a name starting with an astral character
    // would otherwise be cut in half and render as a replacement glyph.
    const letters = Array.from(words[0]).slice(0, 2).join("");
    return letters ? letters.toUpperCase() : NOBODY;
  }
  const first = Array.from(words[0])[0] ?? "";
  const last = Array.from(words[words.length - 1])[0] ?? "";
  const pair = `${first}${last}`;
  return pair ? pair.toUpperCase() : NOBODY;
}
