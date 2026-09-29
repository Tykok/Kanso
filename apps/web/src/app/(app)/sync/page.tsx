"use client";

import { SyncView } from "@/components/sync/sync-view";
import { useMe } from "@/lib/queries";
import { canConfigure } from "@/lib/seat";

/**
 * Where the sync status at the foot of the sidebar leads.
 *
 * A configurator's page, because `GET /api/admin/sync/queue` is: an error string is Notion
 * talking about a page by title. The sidebar gives a member the status as words rather
 * than as a link here, so the refusal below is for somebody who typed the address.
 */
export default function SyncPage() {
  const me = useMe();

  return (
    <div className="mx-auto flex w-full max-w-[760px] flex-col gap-5 overflow-y-auto px-5 pt-6 pb-16">
      <header className="border-b border-border pb-4">
        <h1 className="m-0 text-15 font-medium tracking-tight">Notion sync</h1>
      </header>

      {!me.data ? (
        <p className="text-12 text-faint">Loading…</p>
      ) : canConfigure(me.data.user.instanceRole) ? (
        <SyncView />
      ) : (
        <p className="text-12 text-faint">
          Which pages Notion refused, and why, is the instance owner&apos;s or an admin&apos;s
          to read.
        </p>
      )}
    </div>
  );
}
