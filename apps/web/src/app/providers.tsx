"use client";

import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { usePathname } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { applyEvent, useMe } from "@/lib/queries";
import { connectRealtime } from "@/lib/realtime";
import { applyPreferences, cachePreferences, readCachedPreferences } from "@/lib/theme";

/**
 * The route segments that answer without a session.
 *
 * The root layout wraps every route, including the three public ones, and both providers
 * below assume a signed-in reader: `useMe()` 401s and `connectRealtime()`'s STOMP
 * handshake needs the session cookie — so on `/roadmap` it retried every two seconds,
 * forever, at a visitor who has no account to offer it. Kept here rather than in
 * `PublicRoutes.kt`'s image: this list is about which *pages* are anonymous, and the
 * server's is about which *endpoints* are, and conflating them would let opening one
 * quietly open the other.
 */
const ANONYMOUS_SEGMENTS = ["/roadmap", "/about"] as const;

const isAnonymousRoute = (pathname: string | null) =>
  pathname !== null &&
  ANONYMOUS_SEGMENTS.some(
    (segment) => pathname === segment || pathname.startsWith(`${segment}/`),
  );

function Realtime() {
  const queryClient = useQueryClient();

  useEffect(() => connectRealtime((event) => applyEvent(queryClient, event.entity)), [queryClient]);

  return null;
}

/**
 * The server copy wins as soon as it lands, and the localStorage mirror is refreshed
 * from it so the next load starts on the right theme. Until then the mirror is what
 * the bootstrap script already applied — re-applying it rather than the defaults
 * keeps a dark instance dark while /api/me is in flight, and restores the attributes
 * React wipes off <html> on its development remount.
 */
function Appearance() {
  const me = useMe();
  const preferences = me.data?.preferences;

  useEffect(() => {
    applyPreferences(preferences ?? readCachedPreferences());
    if (preferences) cachePreferences(preferences);
  }, [preferences]);

  return null;
}

export function Providers({ children }: { children: ReactNode }) {
  const [queryClient] = useState(
    () =>
      new QueryClient({
        defaultOptions: {
          queries: {
            // The WebSocket is the invalidation signal, so polling on focus would
            // only duplicate work already done.
            refetchOnWindowFocus: false,
            staleTime: 30_000,
            retry: 1,
          },
        },
      }),
  );

  // A public page still gets the query client — it fetches the roadmap through it — and
  // still gets the cached theme applied, because a visitor's system preference is not a
  // thing the API has to answer for. What it does not get is anything that needs an
  // identity.
  const anonymous = isAnonymousRoute(usePathname());

  return (
    <QueryClientProvider client={queryClient}>
      {anonymous ? <CachedAppearance /> : <SignedIn />}
      {children}
    </QueryClientProvider>
  );
}

function SignedIn() {
  return (
    <>
      <Realtime />
      <Appearance />
    </>
  );
}

/** The theme the last signed-in session left in localStorage, or the defaults. */
function CachedAppearance() {
  useEffect(() => applyPreferences(readCachedPreferences()), []);
  return null;
}
