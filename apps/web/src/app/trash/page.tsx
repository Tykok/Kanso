"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { TrashView } from "@/components/trash/view";
import { ApiError } from "@/lib/api";
import { useMe } from "@/lib/queries";

/**
 * Screen 26, at a route of its own.
 *
 * A page rather than an overlay, and its own shell rather than the ticket list's: the same
 * shape `/settings` already has, for the same reason — this is somewhere you *go*, not a
 * way of looking at the work in front of you, and `app/page.tsx` is frozen for the fan-out
 * anyway. "Back" is the way out, as it is there.
 *
 * The sign-in redirect is `/settings`'s too. The reads behind this screen answer any
 * authenticated user, as every `GET` in Kanso does, but a page that draws nothing at all
 * to somebody with no session should say where to go rather than sit empty.
 */
export default function TrashPage() {
  const router = useRouter();
  const me = useMe();
  const signedOut = me.error instanceof ApiError && me.error.status === 401;

  useEffect(() => {
    if (signedOut) router.replace("/login");
  }, [signedOut, router]);

  if (me.isLoading) return <div className="centered">Loading…</div>;
  if (signedOut || !me.data) return <div className="centered">Signing in…</div>;

  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-5 px-5 pt-6 pb-16">
      <header className="flex items-baseline gap-3 border-b border-border pb-4">
        <Link className="button" href="/">
          Back
        </Link>
        <h1 className="text-15 font-medium tracking-tight">Trash and archives</h1>
      </header>

      {/* `overflow-hidden` so the header strip's own ground stops at the panel's corner
          rather than squaring it off — the strip is a full-bleed band, not a padded row. */}
      <div className="flex flex-col overflow-hidden rounded-panel border border-border bg-card shadow-flat">
        <TrashView />
      </div>
    </div>
  );
}
