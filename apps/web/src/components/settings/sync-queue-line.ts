import type { QueuedJob } from "@/lib/api";
import { type ReadableError, readableError } from "@/lib/sync-error";

/**
 * What one queue row says. A deferral refunds its attempt, so a job put back forever
 * reads `attempts = 0` with a reason — the stuck mirror this screen was built for — and
 * the reason is what tells it apart from a job that has simply not been tried yet.
 */
export type QueueLine = { state: string; reason: ReadableError | null };

export function queueLine(job: QueuedJob, now: Date): QueueLine {
  const reason = readableError(job.error);
  if (job.status === "running") return { state: "Pushing", reason };
  if (job.status === "failed") {
    return {
      state: `Failed after ${job.attempts} ${job.attempts === 1 ? "attempt" : "attempts"}`,
      reason,
    };
  }
  const wait = job.nextAttemptAt ? Date.parse(job.nextAttemptAt) - now.getTime() : 0;
  if (wait <= 0) return { state: "Waiting", reason };
  const seconds = Math.ceil(wait / 1000);
  return {
    state: seconds < 60 ? `Retry in ${seconds} s` : `Retry in ${Math.ceil(seconds / 60)} min`,
    reason,
  };
}
