import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

const alias = {
  // tsconfig's `@/*` mapping is a compile-time contract that Vite never reads,
  // so the same alias has to be restated here or the test run cannot resolve it.
  "@": fileURLToPath(new URL("./src", import.meta.url)),
};

/**
 * Two projects, and the file extension is what chooses between them.
 *
 * The registry, the pills, the composer's rules are pure logic, and a DOM would add a
 * second of startup to tests that never touch one. That was the whole of this config for
 * 988 tests, and it is also why not one of them rendered a component: there was nowhere to
 * put a test that needed a document, so the rendering layer's only guard was the e2e suite
 * — a docker build plus two minutes, not run on every batch.
 *
 * So `.ts` keeps node and `.tsx` gets a document. No per-file pragma anybody can forget,
 * and it reads the right way round: a test written as `.tsx` is a test that renders.
 *
 * happy-dom and not jsdom, decided by trying jsdom first and not by taste. jsdom 30 pulls
 * `html-encoding-sniffer@6`, which `require()`s an ES module — unsupported before Node
 * 22.12, and this repo builds on `node:20-alpine`. Every worker died before importing a
 * test. Pinning jsdom a major behind would work today and break silently on the next
 * upgrade, which is the worse trade for one maintainer. happy-dom is the more partial of
 * the two; if a test ever needs something it does not have, that is the moment to revisit
 * this, not now.
 *
 * `alias` is restated inside each project because a project inherits nothing from the
 * `resolve` above it — a project with no `resolve` of its own cannot see `@/`.
 */
export default defineConfig({
  resolve: { alias },
  test: {
    projects: [
      {
        resolve: { alias },
        test: { name: "logic", environment: "node", include: ["src/**/*.{test,spec}.ts"] },
      },
      {
        resolve: { alias },
        test: {
          name: "dom",
          environment: "happy-dom",
          include: ["src/**/*.{test,spec}.tsx"],
          setupFiles: ["./vitest.setup.dom.mts"],
        },
      },
    ],
  },
});
