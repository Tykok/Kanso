"use client";

import { useMemo } from "react";
import type { QueuedJob } from "@/lib/api";
import { useRetryFailedPushes, useSyncQueue } from "@/lib/queries";
import { cn } from "@/lib/utils";
import { SettingsInline, SettingsNote } from "./field";
import { queueLine } from "./sync-queue-line";

/**
 * The Notion outbox, read-only: what waits, what is pushing, what failed, and why.
 *
 * Built because 51 jobs once sat deferred for days with their reason in `last_error` and
 * no screen reading it. The one write here is `Retry failed`, the route the inbox row
 * already calls.
 */
export function SyncQueueSection() {
  const queue = useSyncQueue();
  const retry = useRetryFailedPushes();
  // The time of the answer, not of the render: a countdown computed against a clock the
  // data was not read at would drift between polls.
  const now = useMemo(() => new Date(queue.dataUpdatedAt), [queue.dataUpdatedAt]);
  const counts = queue.data?.counts ?? {};
  const heading = <h2 className="text-21 font-medium tracking-tight">Sync queue</h2>;
  const unreadable = queue.isError && (
    <SettingsNote error>
      The queue could not be read. It is tried again every few seconds.
    </SettingsNote>
  );

  // Nothing is drawn from a queue that has not been read. Zeros and "Nothing waiting."
  // before the first answer, or after a refused one, would assert the very silence this
  // screen exists to break.
  if (!queue.data) {
    return (
      <section className="flex flex-col gap-6">
        {heading}
        {unreadable || <SettingsNote>Reading the queue…</SettingsNote>}
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-6">
      {heading}
      {unreadable}

      <div className="flex gap-4 text-13 text-muted-foreground">
        <span>{counts.running ?? 0} pushing</span>
        <span>{counts.pending ?? 0} waiting</span>
        <span className={(counts.failed ?? 0) > 0 ? "text-urgent" : undefined}>
          {counts.failed ?? 0} failed
        </span>
      </div>

      <Rows
        title="In the queue"
        jobs={queue.data?.queued ?? []}
        now={now}
        empty="Nothing waiting."
      />

      <Rows title="Refused" jobs={queue.data?.failed ?? []} now={now} empty="Nothing refused." />
      {(queue.data?.failed.length ?? 0) > 0 && (
        <SettingsInline>
          <button className="button" disabled={retry.isPending} onClick={() => retry.mutate()}>
            Retry failed
          </button>
        </SettingsInline>
      )}
    </section>
  );
}

type RowsProps = { title: string; jobs: QueuedJob[]; now: Date; empty: string };

function Rows({ title, jobs, now, empty }: RowsProps) {
  return (
    <div className="flex flex-col gap-2.5 rounded-lg bg-card p-4">
      <span className="text-13 font-medium">{title}</span>
      {jobs.length === 0 ? (
        <SettingsNote>{empty}</SettingsNote>
      ) : (
        <div className="flex flex-col gap-0.5 text-12">
          {jobs.map((job) => (
            <Row key={job.id} job={job} now={now} />
          ))}
        </div>
      )}
    </div>
  );
}

function Row({ job, now }: { job: QueuedJob; now: Date }) {
  const { state, reason } = queueLine(job, now);
  return (
    <div
      className={cn(
        "grid grid-cols-[1fr_auto] items-start gap-x-2.5",
        "rounded-sm bg-background px-2.5 py-1.5",
      )}
    >
      {/* A gone entity has no name left to give, so its type and the head of its id stand in. */}
      <span className="truncate">{job.label ?? `${job.entity} ${job.entityId.slice(0, 8)}`}</span>
      <span className="text-11 text-faint">{state}</span>
      {reason && (
        <div className="col-span-2 text-muted-foreground">
          <span className="line-clamp-2">{reason.summary}</span>
          {reason.detail && (
            <details className="text-11">
              <summary className="cursor-pointer text-faint">Details</summary>
              <pre className="mt-1 max-h-48 overflow-auto whitespace-pre-wrap font-mono">
                {reason.detail}
              </pre>
            </details>
          )}
        </div>
      )}
    </div>
  );
}
