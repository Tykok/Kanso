import type { QueuedJob } from "@/lib/api";
import { queueLine } from "./queue-line";

type JobRowProps = {
  job: QueuedJob;
  now: Date;
  /** Only a refused row has one: a waiting row is already going to be tried. */
  onRetry?: () => void;
  retrying?: boolean;
};

export function JobRow({ job, now, onRetry, retrying }: JobRowProps) {
  const { state, reason } = queueLine(job, now);
  return (
    <li className="flex flex-col gap-1 border-b border-border px-4 py-2.5 last:border-b-0">
      <div className="flex items-baseline gap-2.5">
        {/* A gone entity has no name left to give, so its type and the head of its id
            stand in. */}
        <span className="min-w-0 flex-1 truncate text-13">
          {job.label ?? `${job.entity} ${job.entityId.slice(0, 8)}`}
        </span>
        <span className="text-11 text-faint">{job.entity}</span>
        <span className="text-11 text-faint">{state}</span>
        {onRetry && (
          <button className="button" disabled={retrying} onClick={onRetry}>
            Retry
          </button>
        )}
      </div>
      {reason && (
        <div className="text-12 text-muted-foreground">
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
    </li>
  );
}
