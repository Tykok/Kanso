import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  sidebarRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 18 — a document, written here.
 *
 * Two claims, and the second is the one the whole slice exists for.
 *
 * The first is that screen 22 leads somewhere: a template card creates a page, that page
 * appears in the tree and in "recently changed", and its blocks are the template's.
 *
 * The second is the live status pill. A ticket mentioned in a page is *the same ticket*,
 * so moving it on the board has to change what the document says without anybody
 * touching the document. That is the difference between this and a wiki, and it is the
 * only assertion here that could not be made against a screenshot.
 */
test("scenario 18 — a template starts a page, and a mentioned ticket stays live in it", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Docs");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  const ticket = await seedTicket(api, { teamId: team.id, title: unique("Echo suppression") });

  const page = await openAs(browser, ADMIN);

  // Scope to the seeded team, so the page this test creates lands somewhere it can
  // predict and the templates have a team to write in.
  await page.getByRole("button", { name: teamName, exact: true }).click();
  await page.getByTestId("nav-item").filter({ hasText: "Documents" }).click();

  // --- screen 22: three templates, and one of them starts a page ---------------
  await expect(page.getByTestId("doc-template")).toHaveCount(3);
  const decision = page.getByTestId("doc-template").filter({ hasText: "Decision" });
  await expect(decision).toContainText("Context, options, settled");
  await decision.click();

  // Straight into screen 07, on the blocks V9 seeded for that template.
  await expect(page).toHaveURL(/\/docs\/[0-9a-f-]{36}$/);
  await expect(page.getByRole("heading", { level: 1, name: "Decision" })).toBeVisible();
  await expect(page.getByTestId("doc-block")).toHaveCount(5);

  /**
   * The column, and a way out. This route was the worst of the four with no shell: no
   * sidebar, and — unlike `/trash`, `/settings` and `/docs` — not even a `Back` link, so
   * following a mention into a document left the reader with nothing but the browser's
   * own button. The crumb says `Documents / Decision`: the index above it is a page they
   * can actually climb to, which is why it is a crumb here and the team is elsewhere.
   */
  await expect(sidebarRow(page, "All tickets")).toBeVisible();
  await expect(page.getByTestId("shell-leave")).toBeVisible();
  await expect(page.getByTestId("breadcrumb-crumb")).toHaveText(["Documents", "Decision"]);
  // Two headings in the template, so two entries in the table of contents — derived
  // from the blocks, never stored, which is why renaming one cannot leave a stale row.
  await expect(page.getByTestId("doc-toc-entry")).toHaveText(["Context", "Options"]);
  await expect(page.getByTestId("doc-edited")).toContainText("Edited by E2E owner");
  await expect(page.getByTestId("doc-edited")).toContainText("just now");

  const documentUrl = page.url();

  // --- `#`: mention the ticket, and get its status back with it -----------------
  await page.getByTestId("doc-add-block").click();
  await expect(page.getByTestId("insert-filter")).toBeVisible();
  await page.keyboard.press("Escape");

  // The picker is reached from the block being written in, so focus one first: `#` at
  // the start of a block is the gesture, and inside a sentence it is a hash.
  const firstBlock = page.getByTestId("doc-block").first().locator("textarea").first();
  await firstBlock.click();
  // Explicitly at offset 0. `#` opens the picker only at the very start of a block —
  // inside a sentence it has to stay a typeable character — and a click lands the caret
  // where the pointer was, which on a template's prefilled block is not the start.
  await firstBlock.evaluate((node: HTMLTextAreaElement) => node.setSelectionRange(0, 0));
  await firstBlock.press("#");
  await page.getByTestId("mention-filter").fill(ticket.identifier);
  await page.getByTestId("mention-choice").first().click();

  const chip = page.getByTestId("doc-ticket-chip").filter({ hasText: ticket.identifier });
  await expect(chip).toBeVisible();
  await expect(page.getByTestId("doc-rail-ticket")).toHaveText([ticket.identifier]);
  // `todo` is where a seeded ticket starts; the chip's title is what the reader hovers.
  // STATUS_LABELS.todo is "Todo", one word — the chip carries the app's own label.
  await expect(chip).toHaveAttribute("title", /Todo$/);

  // --- the claim: the document is true without being re-read -------------------
  const moved = await api.patch(`/api/tickets/${ticket.id}`, { data: { status: "in_progress" } });
  expect(moved.ok(), "Could not move the ticket").toBeTruthy();

  await page.reload();
  await expect(
    page.getByTestId("doc-ticket-chip").filter({ hasText: ticket.identifier }),
  ).toHaveAttribute("title", /In progress$/);

  // --- `c`: a new ticket, already attached ------------------------------------
  const raised = unique("Persist the cursor");
  /**
   * Index 2, not 1. The decision template's second block is its empty paragraph — which is
   * what `c` needs, since the gesture fires only on an empty block at offset 0 — but the
   * `#` above inserted a ticket-link block after the anchor, so everything below it moved
   * down one. A link block has no textarea, which is what made `nth(1)` unclickable.
   */
  const secondBlock = page.getByTestId("doc-block").nth(2).locator("textarea").first();
  await secondBlock.click();
  await secondBlock.evaluate((node: HTMLTextAreaElement) => node.setSelectionRange(0, 0));
  await secondBlock.press("c");
  await page.getByTestId("linked-ticket-title").fill(raised);
  await page.getByTestId("linked-ticket-title").press("Enter");

  // Two tickets on the rail now, and the new one exists as a real ticket in its team —
  // `c` in a document is not a note, it is the composer's narrower sibling.
  await expect(page.getByTestId("doc-rail-ticket")).toHaveCount(2);
  const listed = await api.get(`/api/tickets?teamId=${team.id}`);
  const titles = ((await listed.json()) as { title: string }[]).map((row) => row.title);
  expect(titles).toContain(raised);

  // --- back on screen 22, the page is in the tree and in the recent list -------
  await page.getByRole("link", { name: "Documents", exact: true }).click();

  /**
   * The row for *this* page, found by its href rather than by its title.
   *
   * Every page started from the same template is called "Decision", and the README says
   * the stack is long-lived and the suite replayed against it — so a count of one held
   * only on the first run against a fresh volume. The href carries the id, which is the
   * one thing that distinguishes this page from every earlier run's.
   */
  const row = page.locator(
    `[data-testid="doc-tree-page"][href="${new URL(documentUrl).pathname}"]`,
  );
  await expect(row).toHaveCount(1);
  await expect(page.getByTestId("doc-recent-row").first()).toContainText("Decision");
  await expect(page.getByTestId("doc-recent-row").first()).toContainText("E2E owner");

  // The tree row leads back to the same document, not to a second copy of it.
  await row.click();
  await expect(page).toHaveURL(documentUrl);

  await api.dispose();
});

/**
 * The other half of "reads are open, writes are scoped".
 *
 * A member of no team may read every document — `architecture.md` is explicit that no
 * `GET` in Kanso is team-scoped — and may write in none of them. The screen has to say so
 * by offering nothing, not by letting somebody type into a page and collecting a 403 on
 * blur.
 */
test("scenario 18b — an outsider reads a document and is offered no way to change it", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Closed");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });

  // A team with a member is a claimed team: the open-chain clause no longer applies, so
  // `MEMBER` — who is in nothing — is refused. Without this the board would be open to
  // everybody and the assertion below would be about nothing.
  const ownerId = ((await (await api.get("/api/me")).json()) as { user: { id: string } }).user.id;
  await api.post(`/api/teams/${team.id}/members`, { data: { userId: ownerId, role: "admin" } });

  const created = await api.post("/api/docs/pages", {
    data: { teamId: team.id, title: unique("Runbook"), templateSlug: "incident-report" },
  });
  expect(created.ok(), "Could not create the page").toBeTruthy();
  const { page: seeded } = (await created.json()) as { page: { id: string } };

  const page = await openAs(browser, MEMBER);
  await page.goto(`/docs/${seeded.id}`);

  await expect(page.getByTestId("doc-block")).toHaveCount(5);
  // No `/` affordance, and no handles: the one honest way to show a read-only document.
  await expect(page.getByTestId("doc-add-block")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Delete block" })).toHaveCount(0);

  await api.dispose();
});
