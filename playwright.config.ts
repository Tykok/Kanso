import { defineConfig, devices } from "@playwright/test";

/**
 * Whether this run was asked for the captures by name.
 *
 * Read off the command line because `grep` and `grepInvert` are ANDed: written as a
 * plain `grepInvert: /@shots/`, the exclusion below would also swallow
 * `--grep @shots` — the one command that produces the files — and report a pass over
 * zero tests, which is the most expensive kind of green there is.
 */
const askedForShots = process.argv.some((argument) => argument.includes("@shots"));

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
  /**
   * `@shots` is left out of the default run.
   *
   * `e2e/shots.spec.ts` is not a scenario: it writes five PNGs into `site/media/` for the
   * public site's walk-through, and it needs a database no `Atlas` has been created in —
   * ticket identifiers come off a counter on the team row, and a picture captioned
   * `KAN-1` has to show `KAN-1`. Run with the rest it would leave files behind on every
   * pass and refuse on the second one, turning the suite red over something no part of
   * the application had done. It is asked for by name instead:
   * `pnpm exec playwright test --grep @shots`, against a fresh stack.
   */
  grepInvert: askedForShots ? undefined : /@shots/,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  /**
   * Where the artefacts land, and why it is overridable.
   *
   * Two runs in one checkout share `test-results/` and `playwright-report/`, and the
   * second one to start wipes the first one's traces from under it. What comes out is not
   * a timeout: it is `ENOENT` on a trace resource, reported against whichever spec was
   * unlucky — a real spec name, with a real-looking failure, over something that spec did
   * not do. That cost a whole measurement here, and the run it accused was green.
   *
   * So the pair moves with the stack, the way `POSTGRES_PORT` / `API_PORT` / `WEB_PORT`
   * already do: one variable, set beside the others, and two sessions can measure at the
   * same time. Unset, the paths are exactly what they were.
   */
  outputDir: process.env.KANSO_E2E_OUTPUT_DIR ?? "test-results",
  reporter: [
    ["list"],
    [
      "html",
      { open: "never", outputFolder: process.env.KANSO_E2E_REPORT_DIR ?? "playwright-report" },
    ],
  ],
  use: {
    baseURL: process.env.KANSO_WEB_URL ?? "http://localhost:3000",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
