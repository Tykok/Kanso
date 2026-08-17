import { Suspense } from "react";
import { WorkloadView } from "@/components/organise/workload-view";

/** Screen 23. */
/**
 * The `Suspense` boundary is required, not decorative: `useOrganiseTeam` reads `?team=` with
 * `useSearchParams`, and Next refuses to prerender a page that reads the query string
 * without one. The fallback is the same "Loading…" the screens draw while their own queries
 * are in flight, so the boundary is invisible.
 */
export default function WorkloadPage() {
  return (
    <Suspense fallback={<div className="empty">Loading…</div>}>
      <WorkloadView />
    </Suspense>
  );
}
