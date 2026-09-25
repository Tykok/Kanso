"use client";

import Link from "next/link";
import { useSyncStatus } from "@/lib/queries";
import { remainingOf, summaryOf } from "@/lib/sync-status";
import { useSyncWave } from "@/lib/use-sync-wave";

/**
 * The mirror's state, at the foot of the sidebar and in the mobile drawer.
 *
 * One component rather than a string threaded through five files, so the two places
 * cannot drift. A link only for a configurator: the queue section is refused to anyone
 * else, and a link that leads to a refusal is worse than words.
 */
export function SyncStatus({ canConfigure }: { canConfigure: boolean }) {
  const sync = useSyncStatus();
  const progress = useSyncWave(remainingOf(sync.data));
  const text = summaryOf(sync.data);
  if (!text) return null;

  return (
    <div className="flex flex-col gap-1.5 text-11 text-faint">
      {canConfigure ? (
        <Link href="/settings?section=sync-queue" className="hover:text-muted-foreground">
          {text}
        </Link>
      ) : (
        <span>{text}</span>
      )}
      {progress !== null && (
        <div
          role="progressbar"
          aria-label="Notion sync progress"
          aria-valuemin={0}
          aria-valuemax={100}
          aria-valuenow={Math.round(progress * 100)}
          className="h-1 overflow-hidden rounded-full bg-accent"
        >
          <div
            className="h-full bg-primary transition-[width] duration-500"
            style={{ width: `${progress * 100}%` }}
          />
        </div>
      )}
    </div>
  );
}
