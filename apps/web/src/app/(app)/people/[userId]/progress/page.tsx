import { Suspense } from "react";
import { PersonProgressView } from "@/components/organise/progress-others";

/**
 * Screen 41 — one person's figures, read by somebody who is not them.
 *
 * An address of its own rather than `/me?person=`, because `/me` is the caller by
 * definition — the navigation rework took the reader's name off that bar for exactly that
 * reason — and because one address that is sometimes a 403 is a link that fails silently
 * for whoever it was pasted to. The subject is in the path and the team stays in `?team=`,
 * which is the rule `lib/nav.ts` states for every screen here: the team is context, not the
 * subject.
 *
 * Nothing on this page is the permission. `ProgressAccess` refuses on the server, and this
 * page renders the refusal as a sentence — the ticket's whole argument is that a hidden
 * page whose endpoint answers anyway is not a rule.
 *
 * The `Suspense` boundary is required, not decorative: `useOrganiseTeam` reads `?team=` with
 * `useSearchParams`, and Next refuses to prerender a page that reads the query string
 * without one.
 */
export default async function PersonProgressPage({
  params,
}: {
  params: Promise<{ userId: string }>;
}) {
  const { userId } = await params;
  return (
    <Suspense fallback={<div className="empty">Loading…</div>}>
      <PersonProgressView userId={userId} />
    </Suspense>
  );
}
