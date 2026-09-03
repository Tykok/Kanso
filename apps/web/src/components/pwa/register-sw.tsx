"use client";

import { useEffect } from "react";
import { WEB_VERSION } from "@/lib/version";

/**
 * Installs `public/sw.js`, which is what makes an offline reload arrive (KAN-24).
 *
 * Mounted from `app/(app)/layout.tsx` and so only for a signed-in reader. The public
 * routes — `/roadmap`, `/about` — are a shop window that works offline by being a page,
 * and giving a visitor a worker would be caching a shell they have no session for. The
 * registration is origin-wide once it exists, which is fine: the worker's rules are about
 * paths, not about who is asking.
 *
 * Renders nothing. It is an effect with no UI on purpose — there is no "app installed"
 * banner and no custom install button, because `beforeinstallprompt` does not exist on
 * iOS Safari and a prompt that appears on one platform is a second, worse install story
 * beside the browser's own.
 *
 * `?v=` is load-bearing, not a cache-buster out of habit: the worker reads it back off
 * its own URL to name its cache, and a changed script URL is what makes the browser treat
 * a deploy as a new worker. `updateViaCache: "none"` stops the browser answering the
 * update check from its HTTP cache, which is the other half of "a new build can win".
 */
export function RegisterServiceWorker() {
  useEffect(() => {
    if (!("serviceWorker" in navigator)) return;

    // After paint. Registering during hydration competes with the requests that draw the
    // first screen, and the worker is for the *next* load — it has nothing to offer this
    // one, so it can wait for an idle moment.
    const register = () => {
      void navigator.serviceWorker
        .register(`/sw.js?v=${encodeURIComponent(WEB_VERSION)}`, {
          scope: "/",
          updateViaCache: "none",
        })
        // Swallowed deliberately, and this is the only failure worth being quiet about:
        // a browser with storage denied, a private window, or an insecure origin refuses
        // to register, and none of those is something the reader can act on. The app is
        // fully functional without a worker; it simply has no offline reload.
        .catch(() => undefined);
    };

    if (document.readyState === "complete") {
      register();
      return;
    }
    window.addEventListener("load", register, { once: true });
    return () => window.removeEventListener("load", register);
  }, []);

  return null;
}
