"use client";

import { QueryClient, QueryClientProvider, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState, type ReactNode } from "react";
import { applyEvent, useMe } from "@/lib/queries";
import { connectRealtime } from "@/lib/realtime";
import { applyPreferences, cachePreferences, readCachedPreferences } from "@/lib/theme";

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

  return (
    <QueryClientProvider client={queryClient}>
      <Realtime />
      <Appearance />
      {children}
    </QueryClientProvider>
  );
}
