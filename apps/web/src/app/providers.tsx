"use client";

import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { usePathname } from "next/navigation";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { useMe, useTeams } from "@/lib/queries";
import { connectRealtime, type RealtimeConnection } from "@/lib/realtime";
import { queryCache, RealtimeCache, topicsFor } from "@/lib/realtime-events";
import { applyPreferences, cachePreferences, readCachedPreferences } from "@/lib/theme";
import { useUi } from "@/store/ui";

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

/**
 * The change feed, wired to the cache.
 *
 * Two lifetimes, two effects. The socket is opened once per session; the topics follow
 * whatever the list is scoped to, which changes every time someone clicks a team in the
 * sidebar. Reading `useTeams` here is not a spare query — it is the hierarchy
 * [topicsFor] needs to subscribe to a scoped team's descendants as well as itself, and
 * every screen that can be scoped has already loaded it.
 */
function Realtime() {
  const queryClient = useQueryClient();
  const scope = useUi((state) => state.scope);
  const view = useUi((state) => state.view);
  const teams = useTeams().data;
  const topics = useMemo(() => topicsFor(scope, view, teams ?? []), [scope, view, teams]);

  const applier = useMemo(
    () =>
      new RealtimeCache({
        cache: queryCache(queryClient),
        // A row this reader may not read is a refusal, not a failure: the applier reads
        // "no row" and widens, which is what the whole-key invalidation always did.
        fetchTicket: (id) => api.ticket(id).catch(() => undefined),
      }),
    [queryClient],
  );

  // Neither effect assumes it runs first. The socket subscribes to the last topics the
  // other one recorded, and the other one re-subscribes whichever socket is open —
  // so mounting, scoping to another team, and replacing the socket all end up asking
  // for the same set, in any order.
  const connection = useRef<RealtimeConnection | null>(null);
  const wanted = useRef<readonly string[]>([]);

  useEffect(() => {
    const socket = connectRealtime((event) => applier.receive(event));
    connection.current = socket;
    socket.subscribeTo(wanted.current);
    return () => {
      connection.current = null;
      socket.close();
      applier.cancel();
    };
  }, [applier]);

  useEffect(() => {
    wanted.current = topics;
    connection.current?.subscribeTo(topics);
  }, [topics]);

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
