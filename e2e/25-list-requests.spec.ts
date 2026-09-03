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
 * 25. The main screen asks for its tickets once per view.
 *
 * The only scenario here that counts *requests* rather than what they answered, because
 * the defect it pins is invisible to every other kind of assertion: the screen rendered
 * correctly the whole time it was fetching the same tickets twice. KAN-65 — the sidebar's
 * checklist called `useTickets()` with no view to depend on, so the flat door fired
 * alongside every `/grouped` and the list paid for two answers to one question.
 *
 * Counted from the browser and not from a hook, because a hook test would have to model
 * react-query's deduplication to say anything — and deduplication *is* the mechanism
 * under test. Three call sites key `keys.tickets` on the board; one request is the
 * correct answer there, and only a real client can show that.
 *
 * The counts are asserted per view, on one page, because switching a view is not a
 * navigation: it re-renders against the same cache, and what a screen costs on arrival
 * is a different number from what it costs on a click.
 */

/** A GET the list screen makes to ask "which tickets". Writes and single rows are not it. */
type Door = "flat" | "grouped";

const doorOf = (url: string): Door | undefined => {
  const path = new URL(url).pathname;
  if (path === "/api/tickets") return "flat";
  if (path === "/api/tickets/grouped") return "grouped";
  return undefined;
};

test.describe("25. one question, one request", () => {
  test.beforeAll(seedInstance);

  test("the list, the board and the chart each ask once", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Requests"), key: uniqueKey() });
    // Two rows are enough: this counts requests, not tickets.
    await seedTicket(api, { teamId: team.id, title: unique("Row") });
    await seedTicket(api, { teamId: team.id, title: unique("Row") });

    const page = await openAs(browser, ADMIN);
    const calls: { door: Door; url: string }[] = [];
    page.on("request", (request) => {
      if (request.method() !== "GET") return;
      const door = doorOf(request.url());
      if (door) calls.push({ door, url: request.url() });
    });

    /** Everything asked for since the last call, once the screen has stopped asking. */
    const settle = async () => {
      await page.waitForLoadState("networkidle");
      const seen = calls.splice(0, calls.length);
      return {
        flat: seen.filter((call) => call.door === "flat").length,
        grouped: seen.filter((call) => call.door === "grouped").length,
        urls: seen.map((call) => call.url),
      };
    };

    // Scoped to the seeded team, so the answer is small and the screen is the one the
    // ticket describes: `GET /api/tickets?teamId=…` beside `/grouped`. A team is a
    // `button` and not a link since the nav rework: picking a scope is not a navigation,
    // it is a `replaceState` on the address the shell already owns.
    await page.getByRole("button", { name: team.name, exact: true }).click();
    const list = await settle();

    /**
     * The list draws the stacked answer, so the grouped door is the only one it needs.
     * A flat call here is KAN-65 exactly: something outside the view asking for rows the
     * view does not render.
     */
    expect.soft(list, `list view asked: ${list.urls.join(", ")}`).toMatchObject({
      grouped: 1,
      flat: 0,
    });

    // `viewButton`, not the page: this file was delivered green and read red in the suite,
    // because `getByRole("button", { name: "Board" })` also matches the team `17-views`
    // seeds as `Board-<suffix>` and the `⋯` beside it. Nothing about the request count was
    // ever wrong — only which element the click landed on, once another file had run.
    await viewButton(page, "Board").click();
    const board = await settle();

    /**
     * The board keeps the flat door — `page.tsx` and `BoardView` both call `useTickets`
     * and key the same entry, which is one request and not two. The number to watch is
     * that it stayed one when the checklist stopped calling it: this is the view the
     * tempting fix (point the checklist at `/grouped`) would have broken.
     */
    expect.soft(board, `board view asked: ${board.urls.join(", ")}`).toMatchObject({
      flat: 1,
      grouped: 0,
    });

    await viewButton(page, "Timeline").click();
    const timeline = await settle();

    /**
     * Nothing at all: the chart reads `/api/timeline`, and the flat entry the board just
     * filled is still fresh under the same key. Asserted as zero rather than skipped,
     * because a checklist wired to the grouped door would show up here as a `/grouped`
     * on a screen that draws no groups.
     */
    expect.soft(timeline, `timeline view asked: ${timeline.urls.join(", ")}`).toMatchObject({
      flat: 0,
      grouped: 0,
    });

    await page.context().close();
    await api.dispose();
  });
});
