import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 5, and the point of the whole suite.
 *
 * The registry rewrites the keyboard path: this test is what says whether behaviour
 * moved with it. Every assertion describes what the key does *today*, before the
 * switch — not what one would like it to do.
 */
test("scenario 5 — the keyboard does exactly what it did before the registry", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Keys"), key: uniqueKey() });
  const first = unique("Alpha ticket");
  const second = unique("Beta ticket");
  await seedTicket(api, { teamId: team.id, title: first });
  const beta = await seedTicket(api, { teamId: team.id, title: second });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();

  const rows = page.getByTestId("ticket-row");
  await expect(rows).toHaveCount(2);
  const selected = page.locator('[data-testid="ticket-row"][data-selected="true"]');

  // j / k and ↓ / ↑ move the cursor. The list orders by most-recently-updated
  // first, so `second` — created after `first` — is the row on top; "down" moves
  // towards `first`, "up" moves back towards `second`.
  await ticketRow(page, second).click();
  await expect(selected).toContainText(second);
  await page.keyboard.press("j");
  await expect(selected).toContainText(first);
  await page.keyboard.press("k");
  await expect(selected).toContainText(second);
  await page.keyboard.press("ArrowDown");
  await expect(selected).toContainText(first);
  await page.keyboard.press("ArrowUp");
  await expect(selected).toContainText(second);

  // 1..6 walk the status vocabulary in its natural order.
  await page.keyboard.press("2");
  await expect(selected.getByTestId("status-pill")).toHaveText("Todo");
  await page.keyboard.press("5");
  await expect(selected.getByTestId("status-pill")).toHaveText("Done");
  await page.keyboard.press("1");
  await expect(selected.getByTestId("status-pill")).toHaveText("Backlog");

  // Enter opens the selected ticket — `second`/`beta`, where the moves above left it.
  // `.panel-header` moved with task 7's restyle: the header is now Tailwind
  // utilities with no class of its own, so the hook is `data-testid` instead.
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("panel-header")).toContainText(beta.identifier);
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("panel-header")).toHaveCount(0);

  // e renames in place.
  await page.keyboard.press("e");
  const editor = page.getByTestId("row-title-input");
  await expect(editor).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(editor).toHaveCount(0);

  // c opens the composer.
  await page.keyboard.press("c");
  await expect(page.getByPlaceholder("New ticket…")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);

  // / puts the focus in the filter, and the filter filters.
  await page.keyboard.press("/");
  const filter = page.getByPlaceholder(/Filter/);
  await expect(filter).toBeFocused();
  await filter.fill(first);
  await expect(rows).toHaveCount(1);
  // Escape in the filter clears it and gives the focus back.
  await page.keyboard.press("Escape");
  await expect(rows).toHaveCount(2);

  // ⌘K / Ctrl+K opens the palette.
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.getByPlaceholder("Type a command…")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByPlaceholder("Type a command…")).toHaveCount(0);

  // , opens the settings.
  await page.keyboard.press(",");
  await expect(page.getByRole("link", { name: "All settings" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("link", { name: "All settings" })).toHaveCount(0);

  // ? opens the shortcut list. Asserted on the panel's heading rather than on
  // `.shortcuts`, which stopped being one element when the list grew a section per
  // view — and which was a private class this suite should not have been keyed on.
  await page.keyboard.press("?");
  await expect(page.getByRole("heading", { name: "Keyboard" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("heading", { name: "Keyboard" })).toHaveCount(0);

  // x archives: the row leaves the list, which does not show archived tickets.
  // Last, because it is the only key that takes away something to work with.
  await ticketRow(page, first).click();
  await expect(selected).toContainText(first);
  await page.keyboard.press("x");
  await expect(ticketRow(page, first)).toHaveCount(0);
  await expect(ticketRow(page, second)).toBeVisible();
});
