import { Suspense } from "react";
import { MeView } from "@/components/me/me-view";

/** `/me` — a personal home, and the first row of the sidebar's Views group. */
/**
 * The `Suspense` boundary is required, not decorative: `MeView` reads `?tab=` with
 * `useSearchParams`, and Next refuses to prerender a page that reads the query string
 * without one — the same reason the cycle, triage and workload pages each have theirs.
 * The fallback is the "Loading…" the tabs draw while their own queries are in flight, so
 * the boundary is invisible.
 */
export default function MePage() {
  return (
    <Suspense fallback={<div className="empty">Loading…</div>}>
      <MeView />
    </Suspense>
  );
}
