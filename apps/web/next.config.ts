import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  // Produces a self-contained server bundle, so the runtime image needs neither
  // node_modules nor a package manager.
  output: "standalone",

  /**
   * The service worker is the one file that must never be served from a cache (KAN-24).
   *
   * `public/` is otherwise sent with a long `Cache-Control`, and a worker held in the HTTP
   * cache is the classic way a PWA pins itself to an old build: the browser's update check
   * is a normal request, so a cached answer means it re-installs the worker it already has
   * and no deploy ever reaches the reader. `no-store` and the `?v=<commit>` on the
   * registration URL are belt and braces for the same failure — see `public/sw.js`.
   *
   * The explicit `Content-Type` is here because scope is decided by the path this is
   * served from: `/sw.js` claims `/`, which is what lets it answer for navigations.
   */
  async headers() {
    return [
      {
        source: "/sw.js",
        headers: [
          { key: "Content-Type", value: "application/javascript; charset=utf-8" },
          { key: "Cache-Control", value: "no-store, must-revalidate" },
        ],
      },
    ];
  },
};

export default nextConfig;
