import { expect, test } from "@playwright/test";
import { ADMIN, apiAs, openAs, seedInstance, seedTeam, unique, uniqueKey } from "./support";
import { STUB_BASE_URL, startNotionWorkspace, type NotionWorkspaceStub } from "./notion-workspace";

/**
 * Scenario 30. The import, reached from the wizard rather than from the app.
 *
 * `import.spec.ts` walks the five screens from the settings screen, inside the shell, on an
 * instance that has been running for a while. This file is about the one place they are now
 * also offered — the wizard's Notion step, which is a different mount point in two ways that
 * can break without either suite noticing.
 *
 * The first is the route: `/setup` sits outside `(app)`, so nothing the shell provides is
 * there. A dialog that quietly depended on the shell would work in settings and fail here.
 *
 * The second is the form. `FormCard` is a `<form>`, and `import-step-two`'s Next is a shadcn
 * `Button` — a `<button>` with no `type`, which inside a form is a submit button. Mounted in
 * the card, pressing it would submit the wizard: the step would advance to Google and take
 * the half-finished import with it. `notion-step.test.tsx` pins that with a stub in place of
 * the dialog; this asserts it with the real one, which is the only way to be sure the
 * component that actually ships is the one whose buttons were counted.
 *
 * Gated on `KANSO_NOTION_STUB` for the reason `import.spec.ts` argues at length: a guard
 * derived from the API's own answers would let a broken import silence its own scenario.
 */

const OPTED_IN = process.env.KANSO_NOTION_STUB === "1";

const HOW = `Bring the stack up with NOTION_TOKEN=e2e-stub-token NOTION_BASE_URL=${STUB_BASE_URL}, then run the suite with KANSO_NOTION_STUB=1 — see e2e/README.md.`;

let stub: NotionWorkspaceStub;

test.beforeAll(async () => {
  if (!OPTED_IN) return;
  await seedInstance();
  stub = await startNotionWorkspace();
});

test.afterAll(async () => {
  await stub?.close();
});

test("scenario 30 — the wizard's Notion step opens the import", async ({ browser }) => {
  test.skip(!OPTED_IN, `KANSO_NOTION_STUB is not set. ${HOW}`);

  const { teams, projects, tickets } = stub.workspace;

  /*
   * A destination team, seeded so this scenario reads the same on a fresh database as on
   * one the rest of the suite has already run against. With none, the step draws its "no
   * team yet" half instead — which `notion-import-card.test.tsx` covers, and which would
   * otherwise make this spec pass or fail on how many specs ran before it.
   */
  const api = await apiAs(ADMIN);
  await seedTeam(api, { name: unique("Destination"), key: uniqueKey() });

  const page = await openAs(browser, ADMIN);
  await page.goto("/setup");

  // The owner's plan is Notion, Google, preferences — the account step is behind them.
  const rail = page.getByText(/^Step 1 of 3$/);
  await expect(rail).toBeVisible();
  await expect(page.getByRole("heading", { name: "Notion" })).toBeVisible();

  // Matching, before importing: step 4 of the dialog pre-fills itself from what is set here.
  await expect(page.getByText("Notion people")).toBeVisible();
  await expect(page.getByText(stub.workspace.person.name)).toBeVisible();

  await page.getByRole("button", { name: "Import from Notion" }).click();

  // The real dialog, reading the real workspace through the real client.
  await expect(page.getByText("What is in this workspace")).toBeVisible();
  for (const base of [teams, projects, tickets]) {
    await expect(page.getByText(base.name, { exact: true })).toBeVisible();
  }

  // And the whole point of mounting it outside the form: its own Next advances the dialog
  // and nothing else. The wizard is still on step 1 afterwards.
  await page.getByRole("button", { name: "Choose what becomes what" }).click();
  await expect(page.getByText("Each Notion database becomes")).toBeVisible();
  await expect(rail).toBeVisible();

  await page.close();
  await api.dispose();
});
