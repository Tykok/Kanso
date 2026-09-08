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
 * 29. A team's own words for its work — `KAN-28`, and its own list since `KAN-90`.
 *
 * What no unit test can say: that a word typed on the settings screen reaches the list,
 * the board and the ticket beside it, that a reorder restacks a page the *server* ordered,
 * and — since `KAN-90` — that a word the product has never shipped survives a round trip
 * through a real transaction. That last one is the case with a precedent behind it:
 * `KAN-28` shipped a catalogue read that every unit test passed and every browser request
 * answered 500, because a test class is `@Transactional` and a browser is not.
 *
 * The refusals and the key derivation are covered where they live — Kotlin for the service,
 * `statuses-section.test.tsx` for the sentences, `status-key.test.ts` for the table — so
 * this file asserts the round trip and only what a browser can see.
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

    const rows = page.getByTestId("status-row");
    await expect(rows.first()).toBeVisible();

    // This team's words and not whichever team the tab opened on — the select is drawn
    // whenever the instance has more than one, and by the time this suite reaches
    // scenario 29 it has twenty. Awaited rather than guarded on `isVisible`, which does
    // not wait: the first draft of this test read it before the panel had rendered, took
    // the `false` branch, and reordered another scenario's team.
    await page.getByRole("combobox").first().selectOption({ label: team.name });

    // The team's six, in Kanso's words to begin with — asserted *after* the team is
    // chosen, and it was not. It passed anyway while every team had exactly six; the
    // `KAN-90` case below files a team with five, and this line then read that one's
    // list. A count over whichever panel happened to open is not a count of anything.
    await expect(rows).toHaveCount(6);

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

  /**
   * The ticket's headline, end to end — `KAN-90`.
   *
   * A word Kanso has never shipped, filed into, stacked under its own header, then removed
   * with its ticket named a destination. Through a browser rather than a service test,
   * because every layer this crosses has a copy of the vocabulary in it: the status column
   * and its composite foreign key, the grouped page's `CASE`, the pill, the bucket header.
   */
  test("a seventh word is added, filed into, and removed with its ticket named a home", async ({
    browser,
  }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Seventh"), key: uniqueKey() });
    const ticket = await seedTicket(api, { teamId: team.id, title: unique("Devis row") });
    await mirrorQueueDrained();

    const page = await openAs(browser, ADMIN);
    await page.goto("/settings?section=statuses");
    await expect(page.getByTestId("status-row").first()).toBeVisible();
    await page.getByRole("combobox").first().selectOption({ label: team.name });

    // A word, and what it means. `Devis` is a quote a client has not accepted — work the
    // team might do, which is what `backlog` means.
    await page.getByLabel("Name of the status to add").fill("Devis");
    await page.getByLabel("What the status means").selectOption("backlog");
    await page.getByRole("button", { name: "Add" }).click();

    // Seven rows, the new one last, and the server has it — the half the screen cannot
    // prove on its own.
    await expect(page.getByTestId("status-row")).toHaveCount(7);
    await expect(page.getByTestId("status-row").last().locator("input")).toHaveValue("Devis");
    await expect
      .poll(async () => {
        const answer = await api.get(`/api/teams/${team.id}/statuses`);
        const catalogue = (await answer.json()) as { key: string; category: string }[];
        return catalogue.find((status) => status.key === "devis")?.category;
      })
      .toBe("backlog");

    // A ticket into it, through the API, because what is under test here is the column and
    // its foreign key rather than the composer.
    const moved = await api.patch(`/api/tickets/${ticket.id}`, { data: { status: "devis" } });
    expect(moved.status()).toBe(200);
    await mirrorQueueDrained();

    // And the list prints the team's word, in its own bucket. `Devis` is last in the
    // team's order, so its header is below the others — the page is stacked by
    // `team_statuses.position` and this is the only place that can be seen.
    await page.goto("/");
    await page.getByRole("button", { name: team.name, exact: true }).first().click();
    await expect(ticketRow(page, ticket.title).getByTestId("status-pill")).toHaveText("Devis");
    await expect(page.getByTestId("group-header").filter({ hasText: "Devis" })).toBeVisible();

    // Removed, naming where its ticket goes. The row's × asks the question; `Move and
    // remove` answers it — two controls, two names, because one name for both is a fork a
    // screen reader reads as a repeat.
    await page.goto("/settings?section=statuses");
    await expect(page.getByTestId("status-row").first()).toBeVisible();
    await page.getByRole("combobox").first().selectOption({ label: team.name });
    await page.getByRole("button", { name: "Remove Devis" }).click();
    await page.getByLabel("Where the tickets in Devis go").selectOption("todo");
    await page.getByRole("button", { name: "Move and remove" }).click();

    await expect(page.getByTestId("status-row")).toHaveCount(6);

    // The ticket moved rather than being orphaned, and it says so in the feed: a status
    // that vanished from under a ticket with no line behind it is history nobody trusts.
    await expect
      .poll(async () => {
        const answer = await api.get(`/api/tickets/${ticket.id}`);
        return ((await answer.json()) as { status: string }).status;
      })
      .toBe("todo");
    await expect
      .poll(async () => {
        const answer = await api.get(
          `/api/activity?entityType=ticket&entityId=${ticket.id}&limit=50`,
        );
        const lines = (await answer.json()) as { kind: string; payload?: Record<string, string> }[];
        return lines.some(
          (line) => line.kind === "status_changed" && line.payload?.from === "devis",
        );
      })
      .toBe(true);

    await api.dispose();
    await page.context().close();
  });

  /**
   * The other half of the vocabulary changing: a ticket crossing into a team that cannot
   * say where it is — `rebase`.
   *
   * Through a browser for the reason above and one more: `tickets_status_fk` is not
   * deferred, so the statement that writes `team_id` has to carry the rebased status with
   * it. A unit test proved the rule; this proves the transaction.
   */
  test("a ticket crossing into a team without its status takes that team's word", async ({
    browser,
  }) => {
    const api = await apiAs(ADMIN);
    const from = await seedTeam(api, { name: unique("Leaving"), key: uniqueKey() });
    const to = await seedTeam(api, { name: unique("Arriving"), key: uniqueKey() });

    // The destination stops having `in_review` at all, and gains a word of its own for
    // started work — so the ticket can only arrive by its meaning.
    const removed = await api.delete(`/api/teams/${to.id}/statuses/in_review`);
    expect(removed.status()).toBe(204);
    const dropped = await api.delete(`/api/teams/${to.id}/statuses/in_progress`);
    expect(dropped.status()).toBe(204);
    const added = await api.post(`/api/teams/${to.id}/statuses`, {
      data: { label: "En chantier", category: "started" },
    });
    expect(added.status()).toBe(200);

    const ticket = await seedTicket(api, { teamId: from.id, title: unique("Crossing") });
    await api.patch(`/api/tickets/${ticket.id}`, { data: { status: "in_review" } });
    await mirrorQueueDrained();

    // The move, and nothing else in the patch: the status is the server's to decide.
    const crossed = await api.patch(`/api/tickets/${ticket.id}`, { data: { teamId: to.id } });
    expect(crossed.status()).toBe(200);

    await expect
      .poll(async () => {
        const answer = await api.get(`/api/tickets/${ticket.id}`);
        return ((await answer.json()) as { status: string }).status;
      })
      .toBe("en_chantier");

    // And the destination's list draws it under the word that team chose.
    const page = await openAs(browser, ADMIN);
    await page.goto("/");
    await page.getByRole("button", { name: to.name, exact: true }).first().click();
    await expect(ticketRow(page, ticket.title).getByTestId("status-pill")).toHaveText(
      "En chantier",
    );

    await api.dispose();
    await page.context().close();
  });
});
