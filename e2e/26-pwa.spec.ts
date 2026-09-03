import { expect, test } from "@playwright/test";
import { ADMIN, MEMBER, openAs, seedInstance } from "./support";

/**
 * 26. Installed, and honest offline.
 *
 * The three claims KAN-24 has to make good, none of which a unit test can reach: the app
 * installs, a reload with no network arrives instead of showing a blank page, and the
 * worker's cache holds nothing that belongs to a session.
 *
 * That last one is the reason this file exists at all. A Cache Storage bucket is scoped to
 * the *origin*, not to a session, so a worker that cached one person's ticket list would
 * hand it to whoever opened the browser next — and nothing in the application would look
 * wrong while it happened. It is asserted here by enumerating what is actually stored,
 * rather than by trusting the rule in `public/sw.js` to have been implemented.
 */

/** Every URL the worker has actually stored, across every cache it owns. */
const storedUrls = (page: import("@playwright/test").Page) =>
  page.evaluate(async () => {
    const names = await caches.keys();
    const urls: string[] = [];
    for (const name of names) {
      const cache = await caches.open(name);
      for (const request of await cache.keys()) urls.push(request.url);
    }
    return { names, urls };
  });

/** Resolves once a worker controls the page, so the assertions are not racing it. */
const controlled = (page: import("@playwright/test").Page) =>
  page.evaluate(async () => {
    const registration = await navigator.serviceWorker.ready;
    if (navigator.serviceWorker.controller) return registration.active?.scriptURL ?? "";
    await new Promise<void>((resolve) =>
      navigator.serviceWorker.addEventListener("controllerchange", () => resolve(), {
        once: true,
      }),
    );
    return registration.active?.scriptURL ?? "";
  });

test.describe("26. the installed app", () => {
  test.beforeAll(seedInstance);

  test("the manifest describes something installable", async ({ browser }) => {
    const page = await openAs(browser, ADMIN);

    // Linked from the document, which is half of what makes a browser offer to install.
    const href = await page.locator('link[rel="manifest"]').getAttribute("href");
    expect(href, "the document must link a manifest").toBeTruthy();

    const response = await page.request.get(new URL(href!, page.url()).toString());
    expect(response.ok()).toBeTruthy();
    const manifest = (await response.json()) as {
      name?: string;
      start_url?: string;
      display?: string;
      icons?: { sizes?: string; purpose?: string }[];
      shortcuts?: unknown[];
    };

    expect(manifest.name).toBe("Kanso");
    expect(manifest.start_url).toBe("/");
    // Installability's own requirement, and the reason the shell is worth caching: a
    // standalone window has no browser chrome to fall back on.
    expect(manifest.display).toBe("standalone");

    const sizes = (manifest.icons ?? []).map((icon) => icon.sizes);
    expect(sizes, "a 192 and a 512 are what a launcher asks for").toEqual(
      expect.arrayContaining(["192x192", "512x512"]),
    );
    expect(
      (manifest.icons ?? []).some((icon) => icon.purpose === "maskable"),
      "a launcher that masks icons needs one drawn for the crop",
    ).toBeTruthy();

    // Asserted absent on purpose: an OS jump list would be a second navigation idea
    // beside the sidebar and the palette. If somebody adds one, this should be the
    // conversation rather than a silent change.
    expect(manifest.shortcuts, "the installed app keeps one navigation idea").toBeUndefined();

    await page.context().close();
  });

  test("a worker takes control and caches the shell, never the API", async ({ browser }) => {
    const page = await openAs(browser, ADMIN);
    const scriptUrl = await controlled(page);

    // The `?v=` is what makes a deploy a different worker rather than a byte-identical
    // file the browser decides not to replace.
    expect(scriptUrl).toContain("/sw.js?v=");

    // Give the runtime rules something to have cached, then read the disk back.
    await page.reload();
    await controlled(page);
    const { names, urls } = await storedUrls(page);

    expect(names.length, `caches: ${names.join(", ")}`).toBeGreaterThan(0);
    expect(names.every((name) => name.startsWith("kanso-shell-"))).toBeTruthy();

    /*
     * The whole point. Not "no ticket list" — nothing under `/api` at all, because the
     * rule the worker implements is "do not intercept", and a single stored `/api` URL
     * would mean that rule had been weakened somewhere.
     */
    const api = urls.filter((url) => new URL(url).pathname.startsWith("/api/"));
    expect(api, `the worker must store no authenticated response: ${api.join(", ")}`).toEqual([]);

    // And it did cache the things it promised to, or the offline reload below is luck.
    expect(
      urls.some((url) => new URL(url).pathname === "/"),
      `the shell document must be stored: ${urls.join(", ")}`,
    ).toBeTruthy();
    expect(
      urls.some((url) => new URL(url).pathname.startsWith("/_next/static/")),
      "the build's immutable output must be stored",
    ).toBeTruthy();

    await page.context().close();
  });

  test("offline, the shell arrives and the API fails honestly", async ({ browser }) => {
    const page = await openAs(browser, ADMIN);
    await controlled(page);
    await page.reload();
    await controlled(page);

    await page.context().setOffline(true);
    await page.reload();

    // The document and its scripts came off the disk. Before this branch the same
    // gesture produced the browser's own error page and no Kanso at all.
    await expect(page).toHaveTitle(/Kanso/);
    expect(
      await page.evaluate(() => document.querySelector("body")?.childElementCount ?? 0),
      "the shell must have rendered, not merely been served",
    ).toBeGreaterThan(0);

    /*
     * The other half of the promise, and the one that keeps the tracker truthful: reads
     * are not served from a cache, so offline they fail rather than answering with
     * yesterday's tickets.
     */
    const apiReachable = await page.evaluate(async () => {
      try {
        const response = await fetch("/api/me", { cache: "no-store" });
        return response.ok;
      } catch {
        return false;
      }
    });
    expect(apiReachable, "an API read offline must fail, not answer from a cache").toBe(false);

    await page.context().setOffline(false);
    await page.context().close();
  });

  test("switching identity leaves nothing of the last one readable", async ({ browser }) => {
    const first = await openAs(browser, ADMIN);
    await controlled(first);
    await first.reload();
    await controlled(first);
    await first.context().close();

    // A different person, same browser profile is what a shared machine is. The worker's
    // caches are per origin, so this session sees whatever the last one left behind.
    const second = await openAs(browser, MEMBER);
    await controlled(second);
    const { urls } = await storedUrls(second);

    expect(
      urls.filter((url) => new URL(url).pathname.startsWith("/api/")),
      "no API response may survive an identity change, because none is ever stored",
    ).toEqual([]);

    /*
     * Read through the bodies too, not just the URLs. The shell document is cached and is
     * served to every identity, so this is the assertion that says it earns that: if a
     * future change ever server-rendered the reader's name into it, the shared cache
     * would leak it and this fails.
     */
    const leaked = await second.evaluate(async (who) => {
      const names = await caches.keys();
      for (const name of names) {
        const cache = await caches.open(name);
        for (const request of await cache.keys()) {
          const response = await cache.match(request);
          if (!response) continue;
          const type = response.headers.get("content-type") ?? "";
          if (!type.includes("html") && !type.includes("json")) continue;
          if ((await response.text()).includes(who)) return request.url;
        }
      }
      return null;
    }, ADMIN);
    expect(leaked, `a cached response named the previous reader: ${leaked}`).toBeNull();

    await second.context().close();
  });

  test("a new build supersedes the old worker and drops its cache", async ({ browser }) => {
    const page = await openAs(browser, ADMIN);
    await controlled(page);
    await page.reload();
    await controlled(page);

    const before = (await storedUrls(page)).names;
    expect(before.length).toBeGreaterThan(0);

    /*
     * A deploy, simulated at the seam that actually changes: the script URL. This is what
     * `RegisterServiceWorker` does with a new `NEXT_PUBLIC_KANSO_COMMIT`, so registering a
     * different `?v=` here exercises the same install/activate path a real build takes —
     * without needing two images to prove the code runs.
     */
    const after = await page.evaluate(async () => {
      await navigator.serviceWorker.register("/sw.js?v=next-build", {
        scope: "/",
        updateViaCache: "none",
      });

      /*
       * Polled on the outcome rather than on a worker state, because the two are not the
       * same moment: `registration.active` names the new worker as soon as it starts
       * activating, while the cleanup runs inside that handler's `waitUntil` and finishes
       * later. Waiting on the state read the caches mid-sweep and made this fail against a
       * worker that was behaving correctly.
       */
      const deadline = Date.now() + 10_000;
      let names = await caches.keys();
      while (names.some((name) => name.startsWith("kanso-shell-") && name !== "kanso-shell-next-build")) {
        if (Date.now() > deadline) break;
        await new Promise((resolve) => setTimeout(resolve, 100));
        names = await caches.keys();
      }
      return names;
    });

    expect(after, `caches after the update: ${after.join(", ")}`).toContain(
      "kanso-shell-next-build",
    );
    // The old build's cache is gone rather than accumulating: `activate` deletes every
    // `kanso-` cache that is not the running build's.
    expect(
      after.filter((name) => name.startsWith("kanso-shell-") && name !== "kanso-shell-next-build"),
      "an old build's cache must not survive its worker",
    ).toEqual([]);

    await page.context().close();
  });
});
