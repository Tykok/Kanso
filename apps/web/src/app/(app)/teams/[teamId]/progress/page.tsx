import { Suspense } from "react";
import { TeamProgressView } from "@/components/organise/progress-others";

/**
 * Screen 41 — a team's figures, in aggregates.
 *
 * The team is the subject here rather than the context, which is why it is in the path and
 * not in `?team=`. The parameter is still accepted and still carried on the links into this
 * page, because the sidebar's scope is read back out of it on arrival — so leaving it off
 * would land the reader on this team's figures with another team selected in the column
 * beside them.
 *
 * There is no ranking of people on this page and nothing on it to assemble one from: the
 * response has no per-person field, at the service and on the wire. The list of names at
 * the foot is sorted alphabetically and carries no number — see `progress-others.tsx`.
 */
export default async function TeamProgressPage({
  params,
}: {
  params: Promise<{ teamId: string }>;
}) {
  const { teamId } = await params;
  return (
    <Suspense fallback={<div className="empty">Loading…</div>}>
      <TeamProgressView teamId={teamId} />
    </Suspense>
  );
}
