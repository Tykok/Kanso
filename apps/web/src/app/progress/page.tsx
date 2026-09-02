import { Suspense } from "react";
import { ProgressView } from "@/components/organise/progress-view";

/** Screen 40 — the page a person opens on themselves. */
/**
 * The `Suspense` boundary is required, not decorative: `useOrganiseTeam` reads `?team=`
 * with `useSearchParams`, and Next refuses to prerender a page that reads the query string
 * without one. The fallback is the same "Loading…" the other organising screens draw while
 * their queries are in flight, so the boundary is invisible.
 */
export default function ProgressPage() {
  return (
    <Suspense fallback={<div className="empty">Loading…</div>}>
      <ProgressView />
    </Suspense>
  );
}
