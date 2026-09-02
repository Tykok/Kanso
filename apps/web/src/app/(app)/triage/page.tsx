import { Suspense } from "react";
import { TriageView } from "@/components/organise/triage-view";

/** Screen 20. */
/**
 * The `Suspense` boundary is required, not decorative: `useOrganiseTeam` reads `?team=` with
 * `useSearchParams`, and Next refuses to prerender a page that reads the query string
 * without one. The fallback is the same "Loading…" the screens draw while their own queries
 * are in flight, so the boundary is invisible.
 */
export default function TriagePage() {
  return (
    <Suspense fallback={<div className="empty">Loading…</div>}>
      <TriageView />
    </Suspense>
  );
}
