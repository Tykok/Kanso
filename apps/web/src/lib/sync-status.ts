import type { SyncStatus } from "@/lib/api";

/**
 * What is still to push: waiting *and* in flight. The line used to count `pending` alone,
 * so a batch of ten claimed at once read as "0 queued" while all ten were on the wire.
 */
export function remainingOf(status: SyncStatus | undefined): number {
  return (status?.jobs.pending ?? 0) + (status?.jobs.running ?? 0);
}

export function summaryOf(status: SyncStatus | undefined): string {
  if (!status) return "";
  if (!status.mirrorEnabled) return "Notion mirror off";
  const remaining = remainingOf(status);
  const failed = status.jobs.failed ?? 0;
  return [
    `Notion: ${status.bootstrapped ? "connected" : "not bootstrapped"}`,
    remaining > 0 ? `${remaining} queued` : null,
    failed > 0 ? `${failed} failed` : null,
  ]
    .filter(Boolean)
    .join(" · ");
}
