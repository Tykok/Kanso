"use client";

import type { DocViewer } from "@/lib/api";

/**
 * Who else is reading this page, in the top bar — `KAN-25`.
 *
 * Initials rather than avatars: `users.avatar_url` is null for every account created by
 * the first-run wizard or by dev mode, so a row of avatars would be a row of broken images
 * on the instances most likely to be running this. The full name is the `title`, and the
 * `aria-label` on the group is what an e2e spec and a screen reader both read.
 *
 * **The reader themselves is not drawn.** They know they are here, and a chip for them
 * would make "is anybody else on this page" a question of counting to two. That filtering
 * is the caller's — the roster the server sends is the whole truth about the page, and a
 * component that dropped a name would make the endpoint's answer untestable against what
 * is on screen.
 */
export function DocPresence({ viewers }: { viewers: DocViewer[] }) {
  if (viewers.length === 0) return null;

  return (
    <span
      data-testid="doc-viewers"
      aria-label={`Also reading: ${viewers.map((viewer) => viewer.displayName).join(", ")}`}
      className="flex items-center gap-1"
    >
      {viewers.map((viewer) => (
        <span
          key={viewer.userId}
          data-testid="doc-viewer"
          title={`${viewer.displayName} is reading this page`}
          className="flex size-[22px] items-center justify-center rounded-full bg-accent-soft text-11 font-medium text-accent-ink"
        >
          {initials(viewer.displayName)}
        </span>
      ))}
    </span>
  );
}

/**
 * Two letters at most, from the first and last word.
 *
 * `Array.from` and not `[0]`: a name beginning with an emoji or an accented character
 * outside the BMP would be cut mid-codepoint by an index, and the chip would render a
 * replacement glyph. Dev mode makes accounts from e-mail local parts, so this sees more
 * odd names than a product with a sign-up form would.
 */
function initials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean);
  if (words.length === 0) return "?";
  const first = Array.from(words[0])[0] ?? "";
  const last = words.length > 1 ? (Array.from(words[words.length - 1])[0] ?? "") : "";
  return (first + last).toUpperCase();
}
