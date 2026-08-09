import { defineConfig, devices } from "@playwright/test";

/**
 * No `webServer`.
 *
 * What these tests check is mostly server behaviour — the disposition plans, the
 * renumbering, the 403s — so the stack under test is the `docker compose` one,
 * Postgres included. Starting Next on its own would test a client against nothing.
 * `e2e/README.md` gives the single command that brings it up.
 *
 * One worker, no parallelism: the tests share a database. Each creates its own
 * entities under a unique name, which makes them independent of one another, but two
 * concurrent archives on the same tree would see each other.
 */
export default defineConfig({
  testDir: "./e2e",
  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  reporter: [["list"], ["html", { open: "never" }]],
  use: {
    baseURL: process.env.KANSO_WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
