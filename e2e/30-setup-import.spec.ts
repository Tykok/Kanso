import { expect, test } from "@playwright/test";
import { ADMIN, apiAs, openAs, seedInstance, seedTeam, unique, uniqueKey } from "./support";
import { STUB_BASE_URL, startNotionWorkspace, type NotionWorkspaceStub } from "./notion-workspace";

/**
 * Scenario 30. The import, reached from settings rather than from the command palette.
 *
 * `import.spec.ts` opens the dialog through ⌘K, on an instance that has been running for a
 * while. This file is about the other place it is offered — the "Import from Notion…"
 * button on the Connections card — which is a different mount point from the palette in a
 * way that can break without the other suite noticing: this button is disabled until Notion
 * is configured, and it sits beside the Notion people list this scenario also checks,
 * rather than behind a keyboard shortcut with nothing else on screen.
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

test("scenario 30 — settings opens the import on the real workspace", async ({ browser }) => {
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
  // Connections is not the tab `/settings` opens on its own — see `29-team-statuses.spec.ts`
  // and `28-github-link.spec.ts` for the same `?section=` pattern against a different tab.
  await page.goto("/settings?section=connections");

  // Matching, before importing: the folded panel pre-fills itself from what is set here.
  await expect(page.getByText("Notion people")).toBeVisible();
  await expect(page.getByText(stub.workspace.person.name)).toBeVisible();

  await page.getByRole("button", { name: "Import from Notion…" }).click();

  // The real dialog, reading the real workspace through the real client.
  const dialog = page.getByRole("dialog", { name: "Import from Notion" });
  await expect(dialog.getByText("Choose what becomes what")).toBeVisible();
  for (const base of [teams, projects, tickets]) {
    await expect(dialog.getByText(base.name, { exact: true })).toBeVisible();
  }

  await page.close();
  await api.dispose();
});
