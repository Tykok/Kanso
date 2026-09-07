"use client";

import { useEffect } from "react";
import { useOffline } from "@/store/offline";

/**
 * Keeps the store in step with the browser, and empties the queue when the network
 * comes back.
 *
 * Its own file, and mounted by `AppShell`: `KAN-88` routes every ticket patch through
 * this queue, so the flush cannot belong to the one screen that draws the banner. It
 * lived in `banner.tsx` while the only write it could hold was a notification marked
 * read — and you were on the inbox to make one.
 *
 * `online`/`offline` are the browser's own events and are the cheap half. The reliable
 * half is `withOfflineFallback`: `navigator.onLine` is false only when the OS is
 * certain, so a captive portal or a dead API reads as online right up until a request
 * fails. This hook therefore trusts the events to say "try again now" and never to say
 * "everything is fine".
 */
export function useOfflineWatch() {
  const { refresh, flush, setOnline } = useOffline();

  useEffect(() => {
    // A flush on mount, not just a read. The tab may have been closed through the whole
    // outage, in which case no `online` event ever fired for it and the writes on disk
    // would sit there until the reader made another one. If the network is still gone
    // this costs one failed request and leaves every write `queued`.
    void refresh().then(flush);

    const back = () => {
      setOnline(true);
      void flush();
    };
    const gone = () => setOnline(false);

    window.addEventListener("online", back);
    window.addEventListener("offline", gone);
    // The tab may have been asleep through the whole outage, in which case neither
    // event ever fired for it.
    if (navigator.onLine === false) gone();

    return () => {
      window.removeEventListener("online", back);
      window.removeEventListener("offline", gone);
    };
  }, [refresh, flush, setOnline]);
}
