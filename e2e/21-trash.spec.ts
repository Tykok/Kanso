import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  sidebarRow,
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

  await page.getByRole("link", { name: "Trash", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Trash and archives", level: 1 })).toBeVisible();
  await expect(page.getByText(/Emptied after 30 days/)).toBeVisible();

  // The complaint this whole slice started from: "quand je clique sur Trash elle
  // disparaît". It did not disappear — this route simply never had one, because the
  // application's frame lived in `app/page.tsx` and `/trash` was not inside it.
  await expect(sidebarRow(page, "All tickets")).toBeVisible();

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

  // The way out works — and it is a `×` now, not the `Back` link that used to stand here.
  // That link was a `<Link href="/">`: it did not go back, it went home, which is why
  // arriving at the trash from a team's cycle used to lose the reader's place. The `×`
  // runs the same thing `esc` does, and `23-navigation.spec.ts` is where both are proved
  // against every route; this asserts only that this route still has an exit.
  await page.getByTestId("shell-leave").click();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

  await api.dispose();
});

/** `POST` and unwrap, for the two doc shapes this scenario needs and `support.ts` has not. */
async function seedJson<T>(
  api: APIRequestContext,
  path: string,
  data: Record<string, unknown>,
): Promise<T> {
  const response = await api.post(path, { data });
  expect(response.ok(), `Could not POST ${path}: ${response.status()}`).toBeTruthy();
  return (await response.json()) as T;
}

/**
 * Scenario 21, continued — the three kinds `V11` named before they had tables.
 *
 * The document is the one worth an end-to-end assertion of its own, because the sentence the
 * pane prints about it is the drawing's own load-bearing detail and it is assembled from two
 * facts crossing three layers: a `cascades` flag per holding on the server, a rider built
 * from it in `copy.ts`, and a ticket that has to still be there afterwards. A unit test can
 * hold two of those three.
 *
 * The folder carries the decision: its delete reaches the sub-folders and not the pages, so
 * the branch leaves the tree and the writing surfaces at the root — and comes back exactly
 * when the folder is restored, because nothing was ever written to put it anywhere.
 */
test("scenario 21 — a deleted document keeps its tickets, a deleted folder keeps its pages", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Writing"), key: uniqueKey() });
  const mentioned = await seedTicket(api, { teamId: team.id, title: unique("Mentioned once") });

  const folder = await seedJson<{ id: string; name: string }>(api, "/api/docs/folders", {
    teamId: team.id,
    name: unique("Product"),
  });
  const nested = await seedJson<{ id: string; name: string }>(api, "/api/docs/folders", {
    teamId: team.id,
    parentId: folder.id,
    name: unique("Cycle notes"),
  });
  const written = await seedJson<{ page: { id: string; title: string } }>(api, "/api/docs/pages", {
    teamId: team.id,
    folderId: nested.id,
    title: unique("Cycle 22"),
  });
  const doc = written.page;
  await seedJson(api, `/api/docs/pages/${doc.id}/tickets`, { ticketId: mentioned.id });

  // --- the document ---------------------------------------------------------

  expect((await api.delete(`/api/docs/pages/${doc.id}`)).status()).toBe(204);
  expect((await api.get(`/api/docs/pages/${doc.id}`)).status()).toBe(404);

  const page = await openAs(browser, ADMIN);
  await page.getByRole("link", { name: "Trash", exact: true }).click();
  await trashRow(page, doc.title).click();
  const pane = page.getByTestId("trash-detail");

  // The whole sentence, in one assertion, because it is one sentence: what goes with the
  // page, and what stays behind it.
  await expect(pane).toContainText(
    "Held 1 block and 1 mentioned ticket — the ticket was not deleted, only the reference goes.",
  );
  // And no middle exit: `doc_pages` has no `archived` column, so the pane draws two buttons
  // rather than three. A control that refuses everything it is offered is worse than none.
  await expect(pane.getByRole("button", { name: "Archive instead" })).toHaveCount(0);

  await pane.getByRole("button", { name: `Restore into ${nested.name}` }).click();
  await expect(trashRow(page, doc.title)).toHaveCount(0);
  expect((await api.get(`/api/docs/pages/${doc.id}`)).status()).toBe(200);

  // --- the folder, and the decision -----------------------------------------

  expect((await api.delete(`/api/docs/folders/${folder.id}`)).status()).toBe(204);

  const tree = (await (await api.get(`/api/docs/folders?teamId=${team.id}`)).json()) as {
    id: string;
  }[];
  expect(tree.map((row) => row.id)).toEqual([]);
  const pages = (await (await api.get(`/api/docs/pages?teamId=${team.id}`)).json()) as {
    id: string;
    folderId?: string | null;
  }[];
  /**
   * Readable, and at the root: the page is the one thing a tree operation must never take.
   *
   * `?? null` because the claim is "no folder", not "the key is spelled null" — the API's
   * mapper omits a null rather than sending one, everywhere, so the field arrives absent.
   * Asserting the encoding rather than the meaning is what made this fail.
   */
  expect(pages.map((row) => [row.id, row.folderId ?? null])).toEqual([[doc.id, null]]);

  await page.reload();
  await trashRow(page, folder.name).click();
  await expect(pane).toContainText(
    "Held 1 sub-folder and 1 page — the page was not deleted, only the reference goes.",
  );

  // Restored exactly, not approximately — `folder_id` and `parent_id` were never written.
  await pane.getByRole("button", { name: "Restore into" }).click();
  await expect(trashRow(page, folder.name)).toHaveCount(0);
  const back = (await (await api.get(`/api/docs/pages/${doc.id}`)).json()) as {
    page: { folderId: string };
  };
  expect(back.page.folderId).toBe(nested.id);

  await api.dispose();
});
