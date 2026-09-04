import { expect, test } from "@playwright/test";
import { mirrorQueueDrained } from "./settled";
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
  viewButton,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 5, and the point of the whole suite.
 *
 * The registry rewrote the keyboard path and this test is what said whether behaviour
 * moved with it. §6.4 is the first change to what a key *means* since it was written, and
 * it is exactly two keys wide: `j` and `k` are dropped and `n` and `p` answer "next" and
 * "previous" instead. The arrows are unchanged, and they are asserted right beside their
 * letters here — which is what says nothing became unreachable in the trade.
 *
 * Everything else below is the same key doing the same thing, on purpose. A reader's
 * muscle memory is what §6 protects; a rename that spent it would have failed even with
 * every test passing.
 */
test("scenario 5 — the keyboard does exactly what it did, less j and k", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Keys"), key: uniqueKey() });
  const first = unique("Alpha ticket");
  const second = unique("Beta ticket");
  const alpha = await seedTicket(api, { teamId: team.id, title: first });
  const beta = await seedTicket(api, { teamId: team.id, title: second });
  await api.dispose();
  // The seeded rows' sort key is still being rewritten behind us — see `settled.ts`.
  await mirrorQueueDrained();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();

  const rows = page.getByTestId("ticket-row");
  await expect(rows).toHaveCount(2);
  const selected = page.locator('[data-testid="ticket-row"][data-selected="true"]');

  // n / p and ↓ / ↑ move the cursor, down and up between the two rows in the order the
  // list drew them. Which one it put on top is not ours to assume: the sort key is
  // `updated_at`, and the mirror's bookkeeping write rewrites it on both seeded rows
  // within a couple of milliseconds, in whichever order the drain happened to take them
  // — 8 pairs out of 46 came back inverted on a populated instance. Read the wrong way
  // round, "down" starts on the bottom row and moves nowhere, and this test says the
  // keyboard is broken. Assuming "newest first" is what made it, and the one below,
  // green by luck rather than by behaviour. See `settled.ts`.
  const above = (await rows.nth(0).textContent())?.includes(second) ? beta : alpha;
  const under = above === beta ? alpha : beta;

  await ticketRow(page, above.title).click();
  await expect(selected).toContainText(above.title);
  await page.keyboard.press("n");
  await expect(selected).toContainText(under.title);
  await page.keyboard.press("p");
  await expect(selected).toContainText(above.title);
  await page.keyboard.press("ArrowDown");
  await expect(selected).toContainText(under.title);
  await page.keyboard.press("ArrowUp");
  await expect(selected).toContainText(above.title);

  // And the two that were dropped do nothing at all. Asserted as a *non*-event, because a
  // key that still worked would be indistinguishable from one nobody had got round to
  // removing — and would mean the help sheet, which no longer lists them, was lying.
  await page.keyboard.press("j");
  await page.keyboard.press("k");
  await expect(selected).toContainText(above.title);

  // 1..6 walk the status vocabulary in its natural order.
  await page.keyboard.press("2");
  await expect(selected.getByTestId("status-pill")).toHaveText("Todo");
  await page.keyboard.press("5");
  await expect(selected.getByTestId("status-pill")).toHaveText("Done");
  await page.keyboard.press("1");
  await expect(selected.getByTestId("status-pill")).toHaveText("Backlog");

  // Enter opens the selected ticket — the top row, where the moves above left it.
  // `.panel-header` moved with task 7's restyle: the header is now Tailwind
  // utilities with no class of its own, so the hook is `data-testid` instead.
  await page.keyboard.press("Enter");
  await expect(page.getByTestId("panel-header")).toContainText(above.identifier);
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

  // ⌘K / Ctrl+K opens the palette. It is `app.palette`'s binding now rather than a
  // modified key intercepted ahead of the registry in two files, which is why it is
  // pressed here in the middle of the bare keys instead of in a section of its own.
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

  // ⌘V cycles the drawing: list → board → timeline → list. `view.cycleDrawing` had no key
  // at all before §6.4, and it deliberately shadows paste — over a list of rows, where
  // paste did nothing. The typing guard is what keeps paste working inside every field,
  // which the filter box above has already exercised.
  //
  // Through `viewButton`, which scopes to the strip: asked of the page, `Board` matched a
  // team another file seeds under that name as well as the button, so this assertion was
  // green alone and red in the suite.
  await page.keyboard.press("ControlOrMeta+v");
  await expect(viewButton(page, "Board")).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press("ControlOrMeta+v");
  await expect(viewButton(page, "Timeline")).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press("ControlOrMeta+v");
  await expect(viewButton(page, "List")).toHaveAttribute("aria-pressed", "true");

  // x archives: the row leaves the list, which does not show archived tickets.
  // Last, because it is the only key that takes away something to work with.
  await ticketRow(page, first).click();
  await expect(selected).toContainText(first);
  await page.keyboard.press("x");
  await expect(ticketRow(page, first)).toHaveCount(0);
  await expect(ticketRow(page, second)).toBeVisible();
});

/**
 * One dispatcher, which is the thing the shell could most easily have broken.
 *
 * `app/(app)/layout.tsx` gives every route in the group a `keydown` listener, and the
 * ticket list used to keep its own alongside it — that handler also drove the inline
 * rename, the dependency picker and `⇧↵`, none of which the registry could express while a
 * shortcut was one bare `KeyboardEvent.key`. `PageShell.ownsKeyboard` stood the shell's
 * down here so that two dispatchers could not run every bare key *twice*.
 *
 * §6.2 removed the second dispatcher rather than the flag's need for it: chords express
 * `⇧↵`, and `usePageActions` supplies the bodies the registry cannot hold, so there is now
 * one listener in the application and nothing to keep in step. This test is *more*
 * load-bearing after that change, not less — the shell's handler is now the only one, and
 * a page that grew a second would double every key on it again.
 *
 * Three rows, not the two the scenario above uses: with two, a doubled `n` lands on the
 * last row either way, because the move clamps at the end of the list — so the bug would
 * be invisible. The middle row is the whole assertion.
 */
test("scenario 5 — a bare key is dispatched once, not once per shell", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Once"), key: uniqueKey() });
  // Named in the order they are made, and nothing here depends on where the list puts
  // them: the sort key is rewritten behind us in an order we do not choose, so the two
  // moves below are asserted against the rows by position instead. See the test above.
  const one = unique("Row one");
  const two = unique("Row two");
  const three = unique("Row three");
  await seedTicket(api, { teamId: team.id, title: one });
  await seedTicket(api, { teamId: team.id, title: two });
  await seedTicket(api, { teamId: team.id, title: three });
  await api.dispose();
  // The seeded rows' sort key is still being rewritten behind us — see `settled.ts`.
  await mirrorQueueDrained();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();
  const rows = page.getByTestId("ticket-row");
  await expect(rows).toHaveCount(3);

  // One press, one row — which is the whole claim. A second dispatcher would land the
  // cursor two rows down, and with three rows there is somewhere for it to land.
  const first = rows.nth(0);
  const next = rows.nth(1);
  await first.click();
  await expect(first).toHaveAttribute("data-selected", "true");

  await page.keyboard.press("n");
  await expect(next).toHaveAttribute("data-selected", "true");
  await page.keyboard.press("p");
  await expect(first).toHaveAttribute("data-selected", "true");

  // And the same for a key that writes: one press, one status. Asserted through the
  // cursor and not through position 0 — the list groups by status, so a row that has just
  // been moved to `In progress` is no longer where it was pressed.
  const selected = page.locator('[data-testid="ticket-row"][data-selected="true"]');
  await page.keyboard.press("3");
  await expect(selected.getByTestId("status-pill")).toHaveText("In progress");
});
