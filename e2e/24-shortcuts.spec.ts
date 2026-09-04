import { expect, test, type Locator, type Page } from "@playwright/test";
import { mirrorQueueDrained, preferencesStored } from "./settled";
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
 * Scenario 24 — §6.5, the page where a reader remaps every key.
 *
 * `shortcut-rows.test.ts` proves the rules — which actions get a row, what a capture
 * refuses and in which words, what each click stores — over the registry, with no DOM. Two
 * claims are left that only a browser can settle, and they are the whole reason this file
 * exists:
 *
 *  1. **A captured key actually dispatches.** The unit suite can show that a write
 *     produces the right override; it cannot show that the override reaches
 *     `use-shell-keys` and moves a row. That path runs through `useSavePreferences`,
 *     `/api/me`, `useBindings` and a `keydown` listener, and every one of the four is a
 *     place a remap could stop.
 *  2. **The capture field does not fight the dispatcher.** It is a `readOnly` input
 *     precisely so the shell's one listener stands down over it — press `c` inside a
 *     capture and the reader must get the chord, not a new ticket. Nothing but a real
 *     keypress in a real document can say whether that holds.
 *
 * `preferences.shortcuts` is server-side and shared with the other twenty-three scenarios,
 * so every test here puts it back in a `finally`. A leaked override would not fail this
 * file — it would fail `keyboard.spec.ts`, somewhere else, for reasons nothing in its
 * output would explain.
 */

/** The table row for one action, keyed on the id rather than on a label somebody may edit. */
const shortcutRow = (page: Page, id: string): Locator =>
  page.locator(`[data-testid="shortcut-row"][data-action="${id}"]`);

/** Opens `/settings` on the Shortcuts section. */
async function openShortcuts(page: Page): Promise<void> {
  await page.goto("/settings");
  await page.getByRole("button", { name: "Shortcuts", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Shortcuts", level: 2 })).toBeVisible();
}

/** Hands the keyboard back to the registry, whatever the test left behind. */
async function resetShortcuts(): Promise<void> {
  const api = await apiAs(ADMIN);
  try {
    const saved = await api.put("/api/me/preferences", { data: { shortcuts: {} } });
    expect(saved.ok(), "Could not put the keyboard back").toBeTruthy();
  } finally {
    await api.dispose();
  }
}

test("scenario 24 — a captured key moves a row, and the help sheet agrees with it", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Keys"), key: uniqueKey() });
  const first = unique("Alpha remap");
  const second = unique("Beta remap");
  await seedTicket(api, { teamId: team.id, title: first });
  await seedTicket(api, { teamId: team.id, title: second });
  await api.dispose();
  // The seeded rows' sort key is still being rewritten behind us — see `settled.ts`.
  await mirrorQueueDrained();

  try {
    const page = await openAs(browser, ADMIN);
    await openShortcuts(page);

    // The whole registry is here, palette-only actions included — decision one. `Delete
    // ticket` has no key by design and the cell says so rather than sitting empty.
    const moveDown = shortcutRow(page, "ticket.moveDown");
    await expect(moveDown).toContainText("Move down");
    await expect(shortcutRow(page, "ticket.delete")).toContainText("Not bound");

    // The search box, over labels and over keys. `esc` is the key search: no label in the
    // registry contains it, and the row it finds prints `Esc`.
    const search = page.getByLabel("Search shortcuts");
    await search.fill("Move down");
    await expect(page.getByTestId("shortcut-row")).toHaveCount(1);
    await expect(moveDown).toBeVisible();
    await search.fill("esc");
    await expect(shortcutRow(page, "app.back")).toBeVisible();
    await expect(moveDown).toHaveCount(0);
    await search.fill("");

    // `Escape` is the one key that cannot be taken away — the dispatcher holds it whatever
    // storage says — so it is the one chord in the table drawn with no way to remove it.
    await expect(shortcutRow(page, "app.back").getByRole("button", { name: /^Remove / })).toHaveCount(
      0,
    );

    // --- the capture --------------------------------------------------------

    await moveDown.getByRole("button", { name: "Add a key for Move down" }).click();
    const capture = page.getByTestId("shortcut-capture");
    await expect(capture).toBeFocused();
    await expect(capture).toHaveAttribute("placeholder", "press a combination");
    await page.keyboard.press("j");

    // Added, not substituted — decision two. `n` and `↓` are one intention spelled twice
    // and a capture that replaced the set would have silently taken the arrow.
    await expect(moveDown.getByText("n", { exact: true })).toBeVisible();
    await expect(moveDown.getByText("↓", { exact: true })).toBeVisible();
    await expect(moveDown.getByText("j", { exact: true })).toBeVisible();
    // No Save button anywhere: the write went in the background, like every other
    // preference. Reloading is what says it was stored rather than drawn — once the
    // server has it, because the assertion above is satisfied by the optimistic guess
    // and a reload can outrun the request that makes it true. See `settled.ts`.
    await preferencesStored(
      (shortcuts) => (shortcuts["ticket.moveDown"] ?? []).includes("j"),
      "the captured `j`",
    );
    await page.reload();
    await page.getByRole("button", { name: "Shortcuts", exact: true }).click();
    await expect(shortcutRow(page, "ticket.moveDown").getByText("j", { exact: true })).toBeVisible();

    // --- and the key does something -----------------------------------------

    await page.goto("/");
    await page.getByRole("button", { name: team.name, exact: true }).click();
    const selected = page.locator('[data-testid="ticket-row"][data-selected="true"]');
    // Newest first, so `second` is on top and "down" moves towards `first`.
    await ticketRow(page, second).click();
    await expect(selected).toContainText(second);
    await page.keyboard.press("j");
    await expect(selected).toContainText(first);
    // The default it was added to still answers, which is the half a replace would have lost.
    await page.keyboard.press("p");
    await expect(selected).toContainText(second);

    // The `?` sheet reads the effective bindings, so it now lists all three spellings.
    // This is the property the whole slice is for: the sheet is the authority on which
    // keys exist, and one that is out of date is worse than no sheet.
    await page.keyboard.press("?");
    await expect(page.getByRole("heading", { name: "Keyboard" })).toBeVisible();
    await expect(page.getByText("n / ↓ / j", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");

    // --- Reset everything ----------------------------------------------------

    await openShortcuts(page);
    await page.getByRole("button", { name: "Reset everything" }).click();
    await expect(shortcutRow(page, "ticket.moveDown").getByText("j", { exact: true })).toHaveCount(0);
    await expect(
      shortcutRow(page, "ticket.moveDown").getByText("n", { exact: true }),
    ).toBeVisible();
    // Inert once there is nothing to undo, rather than a button that reads as available
    // and changes nothing.
    await expect(page.getByRole("button", { name: "Reset everything" })).toBeDisabled();

    // And the key is gone from the keyboard too, not only from the table.
    await page.goto("/");
    await page.getByRole("button", { name: team.name, exact: true }).click();
    await ticketRow(page, second).click();
    await expect(selected).toContainText(second);
    await page.keyboard.press("j");
    await expect(selected).toContainText(second);
  } finally {
    await resetShortcuts();
  }
});

/**
 * The refusals, and the two keys that get a reader out of a capture.
 *
 * "Never a silent steal" is the one rule §6.5 states twice, and this is it seen from the
 * screen: the chord is not taken, the holder is named, and the field stays open because
 * the next press is almost always another attempt.
 */
test("scenario 24 — a held chord is refused by name, and Escape gets out of the capture", async ({
  browser,
}) => {
  try {
    const page = await openAs(browser, ADMIN);
    await openShortcuts(page);

    const remove = shortcutRow(page, "ticket.delete");
    await remove.getByRole("button", { name: "Add a key for Delete ticket" }).click();

    // `c` is `New ticket`'s, everywhere. Refused, named, and still nothing stored.
    await page.keyboard.press("c");
    await expect(page.getByTestId("shortcut-refusal")).toContainText('already held by "New ticket"');
    await expect(remove).toContainText("Not bound");
    // The capture is still open: a refusal is not a dismissal.
    await expect(page.getByTestId("shortcut-capture")).toBeFocused();

    // A bare printable key *is* allowed — §6.5's third rule — because bare keys are inert
    // inside text fields, which is exactly what this capture is.
    await page.keyboard.press("Shift+J");
    await expect(remove.getByText("⇧j", { exact: true })).toBeVisible();
    await expect(page.getByTestId("shortcut-capture")).toHaveCount(0);

    // Reset means the registry's answer, and for a palette-only action the registry's
    // answer is "no key" — decision one's other half.
    await remove.getByRole("button", { name: "Reset Delete ticket to its default" }).click();
    await expect(remove).toContainText("Not bound");

    // `Escape` cancels the capture and leaves the page alone. Both halves matter: the
    // shell answers `Escape` with "close what is open, then leave", and a capture that let
    // it through would send the reader off `/settings` for pressing cancel.
    await remove.getByRole("button", { name: "Add a key for Delete ticket" }).click();
    await page.keyboard.press("Escape");
    await expect(page.getByTestId("shortcut-capture")).toHaveCount(0);
    await expect(page).toHaveURL(/\/settings$/);
    await expect(remove).toContainText("Not bound");

    // `Tab` is the other way out, and it is allowed to walk the focus on — which is what
    // somebody pressing it is asking for and why it can never be assigned.
    await remove.getByRole("button", { name: "Add a key for Delete ticket" }).click();
    await page.keyboard.press("Tab");
    await expect(page.getByTestId("shortcut-capture")).toHaveCount(0);
    await expect(remove).toContainText("Not bound");

    // --- unbinding, which is the answer to §11's one stated gamble -------------

    // `Mod+v` shadows paste over the list. The spec says changing it is "one click in
    // Settings if it proves wrong in use", so here is the click.
    const drawing = shortcutRow(page, "view.cycleDrawing");
    await drawing.getByRole("button", { name: /^Remove / }).click();
    await expect(drawing).toContainText("Not bound");
    await drawing
      .getByRole("button", { name: "Reset Next drawing: list, board, timeline to its default" })
      .click();
    await expect(drawing).not.toContainText("Not bound");
  } finally {
    await resetShortcuts();
  }
});

/**
 * Decision three: a stored override the merge refused, said out loud.
 *
 * `mergeBindings` runs on every screen and cannot interrupt any of them, so it refuses in
 * silence and hands the reasons to this page. Without the strip the symptom is a key that
 * does nothing and a table that agrees it is not bound — two consistent lies about a
 * preference the reader still has stored. Seeded through the API rather than through the
 * interface on purpose: the capture cannot produce this state, which is exactly why the
 * page has to be able to explain it.
 */
test("scenario 24 — a stored override that collided is named, with a way to discard it", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  try {
    const stored = await api.put("/api/me/preferences", {
      data: { shortcuts: { "ticket.delete": ["c"] } },
    });
    expect(stored.ok(), "Could not store the colliding override").toBeTruthy();

    const page = await openAs(browser, ADMIN);
    await openShortcuts(page);

    const strip = page.getByTestId("shortcut-rejected");
    await expect(strip).toContainText("One stored key was not applied");
    await expect(strip).toContainText("Delete ticket");
    await expect(strip).toContainText('already held by "New ticket"');
    // The holder kept its key. That is the point of refusing rather than stealing.
    await expect(shortcutRow(page, "ticket.create").getByText("c", { exact: true })).toBeVisible();
    await expect(shortcutRow(page, "ticket.delete")).toContainText("Not bound");

    // Nothing is resolved automatically — a page that fixed this by itself would be
    // stealing on the reader's behalf. `Discard` is theirs to press.
    await strip.getByRole("button", { name: "Discard" }).click();
    await expect(strip).toHaveCount(0);
    // The strip leaving is the optimistic guess again, so the same wait: without it the
    // reload can read the override back and the discard reads as not having happened.
    await preferencesStored(
      (shortcuts) => !("ticket.delete" in shortcuts),
      "the discarded override",
    );
    await page.reload();
    await page.getByRole("button", { name: "Shortcuts", exact: true }).click();
    await expect(page.getByTestId("shortcut-rejected")).toHaveCount(0);
  } finally {
    await api.dispose();
    await resetShortcuts();
  }
});
