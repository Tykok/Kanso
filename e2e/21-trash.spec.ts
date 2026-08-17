import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/** The trash row carrying this label. Filtered by name, because the stack is long-lived. */
const trashRow = (page: Page, label: string) =>
  page.getByTestId("trash-row").filter({ hasText: label });

async function throwAway(api: APIRequestContext, id: string): Promise<void> {
  const response = await api.delete(`/api/tickets/${id}`);
  expect(response.ok(), `Could not delete the ticket ${id}`).toBeTruthy();
}

/**
 * Scenario 21 — screen 26, the trash and the archives.
 *
 * The distinction the whole slice turns on is asserted twice over, because it is the one
 * thing a reader of the code could get wrong and still ship something that looks right:
 * a deleted ticket is **gone from the list and answered 404**, exactly as a destroyed one
 * used to be, while still being restorable; and archiving it *instead* moves it to the
 * other tab, where it has no countdown at all.
 *
 * Seeded through the API rather than composed with the mouse. The paths that create a
 * ticket already have four scenarios of their own, and what is under test here is what
 * happens to one afterwards.
 */
test("scenario 21 — a deleted ticket is a countdown, an archived one is a decision", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Bin"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Product"), teamId: team.id });

  const restored = await seedTicket(api, {
    teamId: team.id,
    title: unique("Comes back"),
    projectId: project.id,
  });
  const archived = await seedTicket(api, { teamId: team.id, title: unique("Put away instead") });
  const destroyed = await seedTicket(api, { teamId: team.id, title: unique("Gone for good") });

  // Soft, and invisible to every ordinary caller: the row is still there, and the endpoint
  // that used to 404 on a destroyed ticket 404s on this one too. That is what makes the
  // whole change cost no other screen anything.
  for (const ticket of [restored, archived, destroyed]) await throwAway(api, ticket.id);
  for (const ticket of [restored, archived, destroyed]) {
    expect((await api.get(`/api/tickets/${ticket.id}`)).status()).toBe(404);
  }
  const listed = await api.get(`/api/tickets?teamId=${team.id}&includeArchived=true`);
  expect(await listed.json()).toEqual([]);

  const page = await openAs(browser, ADMIN);

  // The sidebar row is live now — that boolean in `nav-items.ts` is the one shared edit
  // this slice makes, and this is what it buys.
  await page.getByRole("link", { name: "Trash", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Trash and archives", level: 1 })).toBeVisible();
  await expect(page.getByText(/Emptied after 30 days/)).toBeVisible();

  // --- the pane, and the parent it names ------------------------------------

  await trashRow(page, restored.title).click();
  const pane = page.getByTestId("trash-detail");
  // "Deleted today", not "Deleted 0 days ago": the countdown is read off the number the
  // server computed, and today is the one value a person would notice being wrong.
  await expect(pane).toContainText("Deleted today.");
  // The parent is named. This is the assertion the drawing's own copy is about — a button
  // reading only "Restore" would ask somebody to remember where they threw it from.
  await expect(pane.getByRole("button", { name: `Restore into ${project.name}` })).toBeVisible();

  // --- restore -------------------------------------------------------------

  await pane.getByRole("button", { name: `Restore into ${project.name}` }).click();
  await expect(trashRow(page, restored.title)).toHaveCount(0);
  expect((await api.get(`/api/tickets/${restored.id}`)).status()).toBe(200);

  // --- archive instead -----------------------------------------------------

  await trashRow(page, archived.title).click();
  await expect(pane).toContainText(archived.title);
  await pane.getByRole("button", { name: "Archive instead" }).click();
  await expect(trashRow(page, archived.title)).toHaveCount(0);

  await page.getByRole("tab", { name: /archives/i }).click();
  await expect(trashRow(page, archived.title)).toBeVisible();
  // No countdown, and no exits: archived is a decision, and this tab is where decisions
  // live. The em dash is the row's own "no time left", not a missing value.
  await expect(page.getByTestId("trash-detail")).toContainText("There is no countdown on it");

  // --- delete for good -----------------------------------------------------

  await page.getByRole("tab", { name: /trash/i }).click();
  await trashRow(page, destroyed.title).click();
  await pane.getByRole("button", { name: "Delete for good" }).click();
  await expect(trashRow(page, destroyed.title)).toHaveCount(0);
  expect((await api.get(`/api/tickets/${destroyed.id}`)).status()).toBe(404);

  const trash = (await (await api.get("/api/trash")).json()) as {
    trash: { id: string }[];
    archives: { id: string }[];
  };
  const remaining = [...trash.trash, ...trash.archives].map((row) => row.id);
  expect(remaining).not.toContain(destroyed.id);
  expect(remaining).toContain(archived.id);

  // --- and the restored one is back in its team's list -----------------------

  // Asserted against the scoped endpoint rather than by reading the board: `/api/tickets`
  // caps at 200 rows ordered by `updated_at`, and a restore does not touch that column —
  // on a long-lived stack the row is genuinely back and could still be off the first page.
  const back = await api.get(`/api/tickets?teamId=${team.id}`);
  expect(((await back.json()) as { id: string }[]).map((row) => row.id)).toEqual([restored.id]);

  // The way out works, which is the only path off this route.
  await page.getByRole("link", { name: "Back", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  await api.dispose();
});
