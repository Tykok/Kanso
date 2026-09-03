/*
 * Kanso's service worker: the app shell offline, and nothing else (KAN-24).
 *
 * The offline write queue in `components/offline/queue.ts` was half of a feature. It
 * survives a reload in IndexedDB, but there was no read cache and no worker, so reloading
 * with no network gave a blank page — and the queue it had carefully kept was the one
 * thing the reader could no longer see. This file exists to make that reload arrive.
 *
 *
 * WHAT IS CACHED, AND WHY EACH RULE IS SAFE
 *
 * 1. `/api/**` — NEVER. Not read from a cache, not written to one, not intercepted at
 *    all: the `fetch` handler returns before touching it, so those requests reach the
 *    network exactly as they would with no worker installed.
 *
 *    Two reasons, and either alone would be enough. The first is correctness: this is a
 *    tracker, people act on what it shows them, and a stale ticket served from disk is
 *    worse than a spinner because it looks current. The second is privacy: a Cache
 *    Storage bucket is per *origin*, not per session, so one person's ticket list cached
 *    here would be readable by whoever opens this browser next. Not caching it is the
 *    only version of that guarantee that cannot be got wrong later.
 *
 * 2. `/_next/static/**` — cache-first, forever. Every filename in there is content-
 *    hashed by the build, so a given URL's bytes cannot change; "stale" is not a state it
 *    has. This is the whole of the JS, the CSS and the self-hosted Public Sans that
 *    `next/font` emits, which is what makes an offline reload paint something.
 *
 * 3. Navigations (the HTML document) — network-first, cache as a fallback. Cached at all
 *    only because the document carries no identity: the shell is client-rendered, the
 *    reader comes from `/api/me`, and the same bytes are served to everyone. Network
 *    *first* is what stops this being the classic PWA failure where a worker pins an old
 *    bundle: online, the newest document always wins, and it is the document that names
 *    which chunks to load.
 *
 * 4. Everything else same-origin — passed through untouched. That deliberately includes
 *    Next's RSC payloads (`?_rsc=`), which are data with no freshness rule of their own,
 *    so a client-side navigation offline fails rather than lying. A full reload is the
 *    gesture that works offline, which is the gesture the ticket is about.
 *
 *
 * HOW THIS RELATES TO THE OTHER TWO CACHES
 *
 * There are three now, and they do not overlap by construction:
 *   - react-query holds server data in memory for the life of a tab, and is authoritative
 *     while online;
 *   - the STOMP channel invalidates and patches *that* cache when the server changes;
 *   - this one holds only the shell and immutable build output — no server data at all.
 *
 * So there is nothing for them to disagree about. The rule that keeps it that way is rule
 * 1: the moment a service worker starts caching `/api`, it becomes a fourth opinion about
 * ticket state that the realtime channel cannot reach and cannot invalidate.
 *
 *
 * HOW AN UPDATE WINS
 *
 * `register()` is called with `/sw.js?v=<commit>` and this file reads that `v` back off
 * its own URL for the cache name. A deploy therefore changes the worker's script URL,
 * which is what makes the browser treat it as a different worker — no byte-diffing of a
 * file whose contents never change. `updateViaCache: "none"` at the registration and
 * `Cache-Control: no-store` on this route (see `next.config.ts`) mean the check is a real
 * request every time rather than a 24-hour-old HTTP cache entry.
 *
 * `skipWaiting` + `clients.claim` so the new worker takes over immediately, and `activate`
 * deletes every cache whose name is not this build's. Combined with network-first
 * navigations that makes the update path one online reload: reload → newest document →
 * newest chunk URLs → old caches dropped. There is no state in which a reader is stuck on
 * an old bundle with no way out, which is the failure mode this comment exists to rule
 * out.
 */

const VERSION = new URL(self.location.href).searchParams.get("v") || "dev";

/** Namespaced and versioned: `activate` deletes every `kanso-` cache that is not this. */
const CACHE = `kanso-shell-${VERSION}`;

/**
 * The list, because it is the address the app opens on and the only document a cold
 * install can be sure it will be asked for.
 *
 * Precaching stops here rather than trying to name chunks. Their filenames are decided by
 * the build, so a hand-written list would be wrong on the next deploy; they are cached as
 * they are used instead. The honest promise is therefore "a screen you have opened online
 * reloads offline", not "the whole app works having never been run".
 */
const SHELL = "/";

const isStatic = (pathname) =>
  pathname.startsWith("/_next/static/") || pathname.startsWith("/icons/");

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.add(new Request(SHELL, { cache: "reload" })))
      // A failed precache must not fail the install: the worker is still useful for the
      // runtime rules, and refusing to install would leave the reader with no worker at
      // all because one request lost a race with a flaky network.
      .catch(() => undefined)
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) =>
        Promise.all(
          names
            .filter((name) => name.startsWith("kanso-") && name !== CACHE)
            .map((name) => caches.delete(name)),
        ),
      )
      .then(() => self.clients.claim()),
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  // Another origin's problem. Nothing here is cross-origin by design — the font is
  // self-hosted precisely so nothing leaves this origin at runtime — so this is a guard
  // rather than a case.
  if (url.origin !== self.location.origin) return;

  // Rule 1. Ahead of every other test, because this is the one that must never be
  // weakened by a later branch: an authenticated answer is not ours to keep.
  if (url.pathname.startsWith("/api/")) return;

  // Rule 2. Immutable by construction, so a hit needs no revalidation.
  if (isStatic(url.pathname)) {
    event.respondWith(
      caches.match(request).then(
        (hit) =>
          hit ??
          fetch(request).then((response) => {
            // Only a real 200. An opaque or errored response cached under a hashed URL
            // would be permanent, since nothing ever revalidates one.
            if (response.ok && response.type === "basic") {
              const copy = response.clone();
              caches.open(CACHE).then((cache) => cache.put(request, copy));
            }
            return response;
          }),
      ),
    );
    return;
  }

  // Rule 3. Network-first, so the newest document always wins while online.
  if (request.mode === "navigate") {
    event.respondWith(
      fetch(request)
        .then((response) => {
          if (response.ok && response.type === "basic") {
            const copy = response.clone();
            caches.open(CACHE).then((cache) => cache.put(request, copy));
          }
          return response;
        })
        .catch(async () => {
          const cache = await caches.open(CACHE);
          // This address if it has been seen, otherwise the shell — which boots the same
          // client-side app and lets its own router resolve the path.
          return (
            (await cache.match(request)) ??
            (await cache.match(SHELL)) ??
            new Response(
              "<!doctype html><meta charset=utf-8><title>Kanso — offline</title>" +
                "<p style=\"font:14px system-ui;padding:2rem\">Kanso has not been opened " +
                "on this device while online, so there is nothing stored to show yet.",
              { status: 503, headers: { "Content-Type": "text/html; charset=utf-8" } },
            )
          );
        }),
    );
    return;
  }

  // Rule 4. Everything else, including RSC payloads: untouched.
});
