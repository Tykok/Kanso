import { expect, test } from "@playwright/test";
import { mirrorQueueDrained } from "./settled";
import {
  ADMIN,
  apiAs,
  MEMBER,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

/**
 * 29. A team's own words for its work — `KAN-28`.
 *
 * What no unit test can say: that a word typed on the settings screen reaches the list,
 * the board and the ticket beside it, and that a reorder restacks a page the *server*
 * ordered. The rest of this ticket is covered where it lives — the key derivation and the
 * refusals in Kotlin, the four lookups in `lib/statuses.test.ts` — so this file asserts
 * the round trip and the two things only a browser can see.
 *
 * Adding and removing a status is `KAN-90`, and its absence is asserted here on purpose:
 * a screen with no `Add` button is a boundary, and somebody should have to change this
 * test to move it.
 */

test.describe("29. a team's words", () => {
  test.beforeAll(seedInstance);

  test("a rename reaches every screen that prints a status", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Support"), key: uniqueKey() });
    const ticket = await seedTicket(api, { teamId: team.id, title: unique("Ouvert") });
    await mirrorQueueDrained();

    const page = await openAs(browser, ADMIN);
    await page.goto("/settings?section=statuses");

    // The team's six, in Kanso's words to begin with.
    const rows = page.getByTestId("status-row");
    await expect(rows).toHaveCount(6);
    await expect(rows.first()).toBeVisible();
    await expect(page.getByRole("button", { name: /^Add/ })).toHaveCount(0);

    // This team's words and not whichever team the tab opened on — the select is drawn
    // whenever the instance has more than one, and by the time this suite reaches
    // scenario 29 it has twenty. Awaited rather than guarded on `isVisible`, which does
    // not wait: the first draft of this test read it before the panel had rendered, took
    // the `false` branch, and reordered another scenario's team.
    await page.getByRole("combobox").first().selectOption({ label: team.name });

    const done = page.getByLabel("Name of Done");
    await done.fill("Livré");
    await done.blur();

    // The server has it, which is the half the screen cannot prove.
    await expect
      .poll(async () => {
        const answer = await api.get(`/api/teams/${team.id}/statuses`);
        const catalogue = (await answer.json()) as { key: string; label: string }[];
        return catalogue.find((status) => status.key === "done")?.label;
      })
      .toBe("Livré");

    // And the screens that print a status print the team's word. The row's pill first,
    // then the bucket header — in that order, because an empty bucket draws no header at
    // all and nothing is `done` until the key below is pressed.
    await page.goto("/");
    await page.getByRole("button", { name: team.name, exact: true }).first().click();

    const row = ticketRow(page, ticket.title);
    await row.click();
    await page.keyboard.press("5");
    await expect(row.getByTestId("status-pill")).toHaveText("Livré");
    await expect(page.getByTestId("group-header").filter({ hasText: "Livré" })).toBeVisible();

    await api.dispose();
    await page.context().close();
  });

  test("a reorder restacks the list, because the server ordered it", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Ordered"), key: uniqueKey() });
    // `seedTicket` files into `todo`, which is the second of the six.
    await seedTicket(api, { teamId: team.id, title: unique("Todo row") });
    const started = await seedTicket(api, { teamId: team.id, title: unique("Started row") });
    await api.patch(`/api/tickets/${started.id}`, { data: { status: "in_progress" } });
    await mirrorQueueDrained();

    const page = await openAs(browser, ADMIN);
    await page.goto("/");
    await page.getByRole("button", { name: team.name, exact: true }).first().click();

    // Todo above In progress, which is `StatusOrder.WORKFLOW` — the order a team is seeded
    // in and, until this ticket, the only order there was. `backlog` draws no header
    // because nothing is in it.
    const headers = page.getByTestId("group-header");
    await expect(headers.first()).toContainText("Todo");

    await page.goto("/settings?section=statuses");
    await expect(page.getByTestId("status-row").first()).toBeVisible();
    await page.getByRole("combobox").first().selectOption({ label: team.name });

    // Twice, and awaited apart: `in_progress` starts third, each move sends the whole
    // order, and clicking again before the first has landed would send the same list
    // twice and move nothing.
    await page.getByRole("button", { name: "Move In progress up" }).click();
    await expect(page.getByTestId("status-row").nth(1).locator("input")).toHaveValue("In progress");
    await page.getByRole("button", { name: "Move In progress up" }).click();
    await expect(page.getByTestId("status-row").first().locator("input")).toHaveValue("In progress");

    await page.goto("/");
    await page.getByRole("button", { name: team.name, exact: true }).first().click();

    // The page boundary is cut against the server's order, so this is not a client-side
    // restack — the list is asking a differently ordered question.
    await expect(page.getByTestId("group-header").first()).toContainText("In progress");

    await api.dispose();
    await page.context().close();
  });

  test("a member reads the words and is not offered the tab", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Read"), key: uniqueKey() });
    await api.patch(`/api/teams/${team.id}/statuses/done`, {
      data: { label: "Expédié" },
    });
    await mirrorQueueDrained();

    const page = await openAs(browser, MEMBER);
    await page.goto("/settings?section=statuses");

    // Not offered, and the section does not render even when the URL asks for it: the
    // vocabulary is the team's shape, like its name.
    await expect(page.getByTestId("status-row")).toHaveCount(0);

    await api.dispose();
    await page.context().close();
  });
});
