import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

export default defineConfig({
  resolve: {
    // tsconfig's `@/*` mapping is a compile-time contract that Vite never reads,
    // so the same alias has to be restated here or the test run cannot resolve it.
    alias: { "@": fileURLToPath(new URL("./src", import.meta.url)) },
  },
  test: {
    // The registry is pure logic. A DOM would add a dependency and a second of
    // startup to tests that never touch one.
    environment: "node",
    include: ["src/**/*.{test,spec}.ts"],
  },
});
