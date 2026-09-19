import { expect, test } from "@playwright/test";

import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  unique,
  uniqueKey,
  viewButton,
} from "./support";

/**
 * The column that replaced the tray, and the way back to today.
 *
 * Scheduling is done over HTTP rather than by simulating the drag, for the reason
 * `25-custom-fields.spec.ts` gives about its definitions: what this spec is for is that an
 * undated ticket is a *row* and gains a bar in place, and driving a pointer-capture drag
 * across a virtualised list would make a failure in the gesture look like a failure in the
 * column. The handle's own wiring is covered where it lives.
 */
test.describe("the timeline column", () => {
  test.beforeAll(async () => {
    await seedInstance();
  });

  test("an undated ticket is a row with no bar, then gains one in place", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Column"), key: uniqueKey() });
    const undated = await seedTicket(api, { teamId: team.id, title: unique("Nobody planned this") });

    const page = await openAs(browser, ADMIN);
    await page.getByRole("button", { name: team.name, exact: true }).click();
    await viewButton(page, "Timeline").click();

    // Its name is in the column…
    await expect(page.getByText(undated.identifier, { exact: true })).toBeVisible();
    // …and nothing is drawn beside it.
    const bar = page.getByRole("button", {
      name: new RegExp(`^${undated.identifier}: `),
    });
    await expect(bar).toHaveCount(0);

    const today = new Date().toISOString().slice(0, 10);
    const dated = await api.patch(`/api/tickets/${undated.id}`, {
      data: {
        start: { at: `${today}T00:00:00Z`, hasTime: false },
        due: { at: `${today}T00:00:00Z`, hasTime: false },
      },
    });
    expect(dated.ok(), `Could not schedule it: ${dated.status()}`).toBeTruthy();

    // The same row, now with a bar. It did not move to a different list, because there is
    // only one list — which is the whole of this ticket.
    await expect(bar).toHaveCount(1, { timeout: 15_000 });
    await expect(page.getByText(undated.identifier, { exact: true })).toBeVisible();
  });

  test("Today is drawn before it is needed, and disabled while the rule is on screen", async ({
    browser,
  }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Today"), key: uniqueKey() });
    await seedTicket(api, { teamId: team.id, title: unique("Something to draw") });

    const page = await openAs(browser, ADMIN);
    await page.getByRole("button", { name: team.name, exact: true }).click();
    await viewButton(page, "Timeline").click();

    const today = page.getByRole("button", { name: "Today", exact: true });
    await expect(today).toBeVisible();

    // Disabled and never hidden: the control is there before it is needed, which is the
    // point of it being drawn at all.
    await expect(today).toBeDisabled();
  });
});
