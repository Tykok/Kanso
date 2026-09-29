"use client";

import { useMemo, type ReactNode } from "react";
import type { QueuedJob } from "@/lib/api";
import {
  useRetryFailedPushes,
  useRetrySyncJob,
  useSyncQueue,
  useSyncStatus,
} from "@/lib/queries";
import { summaryOf } from "@/lib/sync-status";
import { cn } from "@/lib/utils";
import { JobRow } from "./job-row";

/**
 * The Notion outbox: what Notion refused and why, then what is still to go.
 *
 * Refusals first because they are the only rows that wait on a person — everything in the
 * queue is going to be tried again without anyone asking. Built because 51 jobs once sat
 * deferred for days with their reason in `last_error` and no screen reading it, and moved
 * out of the settings because it is a place to look at, not a thing to set.
 */
export function SyncView() {
  const queue = useSyncQueue();
  const status = useSyncStatus();
  const retryAll = useRetryFailedPushes();
  const retryOne = useRetrySyncJob();
  // The time of the answer, not of the render: a countdown computed against a clock the
  // data was not read at would drift between polls.
  const now = useMemo(() => new Date(queue.dataUpdatedAt), [queue.dataUpdatedAt]);
  const unreadable = queue.isError && (
    <p className="text-12 text-urgent">
      The queue could not be read. It is tried again every few seconds.
    </p>
  );

  // Nothing is drawn from a queue that has not been read. Zeros and "Nothing waiting."
  // before the first answer, or after a refused one, would assert the very silence this
  // screen exists to break.
  if (!queue.data) {
    return unreadable || <p className="text-12 text-faint">Reading the queue…</p>;
  }

  const counts = queue.data.counts;
  const failed = queue.data.failed;

  return (
    <div className="flex flex-col gap-5">
      {unreadable}
      <p className="text-12 text-muted-foreground">{summaryOf(status.data)}</p>

      <div className="grid grid-cols-3 gap-2.5">
        <Count label="Failed" value={counts.failed ?? 0} urgent />
        <Count label="Waiting" value={counts.pending ?? 0} />
        <Count label="Pushing" value={counts.running ?? 0} />
      </div>

      <Panel
        title="Refused by Notion"
        action={
          (counts.failed ?? 0) > 1 && (
            <button
              className="button"
              disabled={retryAll.isPending}
              onClick={() => retryAll.mutate()}
            >
              Retry all
            </button>
          )
        }
        jobs={failed}
        empty="Nothing refused."
        row={(job) => (
          <JobRow
            key={job.id}
            job={job}
            now={now}
            onRetry={() => retryOne.mutate(job.id)}
            retrying={retryOne.isPending && retryOne.variables === job.id}
          />
        )}
      />

      <Panel
        title="In the queue"
        jobs={queue.data.queued}
        empty="Nothing waiting."
        row={(job) => <JobRow key={job.id} job={job} now={now} />}
      />
    </div>
  );
}

/** The whole queue's count, not the length of a list the API stops at fifty. */
function Count({ label, value, urgent }: { label: string; value: number; urgent?: boolean }) {
  return (
    <div className="flex flex-col gap-0.5 rounded-lg bg-card px-4 py-3">
      {/* Not through `cn`: tailwind-merge reads `text-urgent` as a size and drops `text-21`. */}
      <span className={`text-21 font-medium ${urgent && value > 0 ? "text-urgent" : ""}`}>
        {value}
      </span>
      <span className="text-11 text-faint">{label}</span>
    </div>
  );
}

type PanelProps = {
  title: string;
  action?: ReactNode;
  jobs: QueuedJob[];
  empty: string;
  row: (job: QueuedJob) => ReactNode;
};

function Panel({ title, action, jobs, empty, row }: PanelProps) {
  return (
    <section
      className={cn(
        "flex flex-col overflow-hidden",
        "rounded-panel border border-border bg-card shadow-flat",
      )}
    >
      <header className="flex items-center gap-2.5 border-b border-border px-4 py-2.5">
        <h2 className="m-0 flex-1 text-13 font-medium">{title}</h2>
        {action}
      </header>
      {jobs.length === 0 ? (
        <p className="px-4 py-3 text-12 text-faint">{empty}</p>
      ) : (
        <ul className="m-0 list-none p-0">{jobs.map(row)}</ul>
      )}
    </section>
  );
}
