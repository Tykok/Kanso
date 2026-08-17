import { expect, test, type Page } from "@playwright/test";
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

/**
 * Scenario 17 — the four screens slice A draws: the ticket at page width (03), the board
 * (04), the project page (05) and the global search (06).
 *
 * Everything here reads endpoints that already existed before this branch. What is new is
 * the drawing, so this suite is mostly about *where things are*: which column a card sits
 * in, which order the tabs cycle in, what the counter says, and whether a link that names
 * a ticket by the identifier people read out loud resolves to it.
 */

/** Selects a team in the sidebar and switches to the board. */
async function openBoard(page: Page, team: string) {
  await page.getByRole("button", { name: team, exact: true }).first().click();
  await expect(page.getByRole("heading", { name: team, level: 1 })).toBeVisible();
  await page.getByRole("group", { name: "View" }).getByRole("button", { name: "Board" }).click();
  await expect(page.getByTestId("board")).toBeVisible();
}

test("03 — a ticket at page width, reached by the identifier people read out loud", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Page"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Mirror"), teamId: team.id });
  const title = unique("Echo suppression drops our own writes");
  const ticket = await seedTicket(api, { teamId: team.id, title, projectId: project.id });

  const page = await openAs(browser, ADMIN);

  // The URL is the identifier, not a UUID: that is the whole point of resolving through
  // `/api/tickets/by-key/{teamKey}/{number}` rather than by id.
  await page.goto(`/t/${ticket.identifier}`);
  await expect(page.getByRole("heading", { name: title, level: 1 })).toBeVisible();

  // The breadcrumb names the team and the project, and the identifier is the last crumb.
  await expect(page.getByRole("link", { name: team.name, exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: project.name, exact: true })).toBeVisible();

  // The chips are the panel's own controls at a larger measure — the same wire call, so a
  // status changed here has to reach the server exactly as it does from the panel.
  const [patched] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tickets/${ticket.id}`) &&
        response.request().method() === "PATCH",
    ),
    page.getByLabel("Status", { exact: true }).selectOption("in_progress"),
  ]);
  expect(patched.status(), "the status change was refused").toBe(200);

  // `⤡` is the inverse of the panel's `⤢`: the same ticket, the smaller measure, and the
  // list back behind it. It selects before it navigates, so the panel opens on *this*
  // ticket rather than on whatever the cursor was last left on.
  await page.getByRole("button", { name: "Collapse into the panel" }).click();
  await expect(page.getByTestId("panel-header")).toContainText(ticket.identifier);

  // And `esc` from the page goes back where it came from rather than to a fixed route.
  await page.goto(`/t/${ticket.identifier}`);
  await expect(page.getByRole("heading", { name: title, level: 1 })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("heading", { name: title, level: 1 })).toHaveCount(0);
});

test("03 — a key nobody allocated says so instead of hanging or 404ing", async ({ browser }) => {
  const page = await openAs(browser, ADMIN);

  // Parsed and refused without a round trip: `nonsense` is not `^[A-Z0-9]{2,8}-[1-9]\d*$`,
  // so `useTicketByKey` is disabled outright rather than asking the server about it.
  await page.goto("/t/nonsense");
  await expect(page.getByText(/No ticket answers to nonsense/)).toBeVisible();

  // A well-formed key for a ticket that does not exist is the other half: this one *is* a
  // request, it 404s, and `retry: false` means it says so once.
  await page.goto("/t/ZZZZ-9999");
  await expect(page.getByText(/No ticket answers to ZZZZ-9999/)).toBeVisible();
});

test("04 — six columns, colour in two places, and the keyboard across them", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Board");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  const backlog = await seedTicket(api, { teamId: team.id, title: unique("Webhooks") });
  const todo = await seedTicket(api, { teamId: team.id, title: unique("Reconcile") });
  await api.patch(`/api/tickets/${backlog.id}`, { data: { status: "backlog" } });

  const page = await openAs(browser, ADMIN);
  await openBoard(page, teamName);

  // All six, always, and in `TICKET_STATUSES` order — which is also the order `1`–`6`
  // moves a card into. A board that dropped its empty columns would stop lining up with
  // the six keys the moment one emptied.
  await expect(page.getByTestId("board-column")).toHaveCount(6);
  const statuses = await page
    .getByTestId("board-column")
    .evaluateAll((columns) => columns.map((element) => element.getAttribute("data-status")));
  expect(statuses).toEqual([
    "backlog",
    "todo",
    "in_progress",
    "in_review",
    "done",
    "canceled",
  ]);

  const card = (identifier: string) =>
    page.getByTestId("board-card").filter({ has: page.getByText(identifier, { exact: true }) });
  // By position rather than by `data-status`, so the assertion above is what proves the
  // order and nothing here silently depends on it a second time.
  const column = (status: string) => page.getByTestId("board-column").nth(statuses.indexOf(status));

  // A card lives in the column its status names, and nowhere else.
  await expect(column("backlog").getByTestId("board-card")).toHaveCount(1);
  await expect(column("todo").getByTestId("board-card")).toHaveCount(1);

  /**
   * The colour rule, which the drawing states as a constraint and not a style: the status
   * hue appears in the column header and as a 2px rule at the top of the card, and nowhere
   * on the card's fill. A card tinted with its own status would make six columns of six
   * colours and the title the least legible thing on screen.
   */
  const rule = await card(backlog.identifier).evaluate((element) => {
    const style = getComputedStyle(element);
    return {
      width: style.borderTopWidth,
      colour: style.borderTopColor,
      fill: style.backgroundColor,
      card: getComputedStyle(document.documentElement).getPropertyValue("--card").trim(),
    };
  });
  expect(rule.width).toBe("2px");
  expect(rule.colour, "the top rule carries no colour").not.toBe("rgba(0, 0, 0, 0)");
  // The fill is the ordinary card surface — the hue is on the rule, not behind the title.
  expect(rule.fill).not.toBe(rule.colour);

  // `h` and `l` walk columns and pass over the empty ones: the cursor cannot be drawn in a
  // column with no cards, so stopping in one would leave `l` looking broken.
  await card(backlog.identifier).click();
  await expect(card(backlog.identifier)).toHaveAttribute("data-selected", "true");
  await page.keyboard.press("l");
  await expect(card(todo.identifier)).toHaveAttribute("data-selected", "true");
  await page.keyboard.press("h");
  await expect(card(backlog.identifier)).toHaveAttribute("data-selected", "true");

  // Past the left-hand edge the key is inert rather than wrong.
  await page.keyboard.press("h");
  await expect(card(backlog.identifier)).toHaveAttribute("data-selected", "true");

  // `1`–`6` move a card, because moving a card between columns *is* a status change and
  // `ticket.status.*` already is one — nothing is registered for the board to do this.
  await page.keyboard.press("3");
  await expect(column("in_progress").getByTestId("board-card")).toHaveCount(1);
  await expect(column("backlog").getByTestId("board-card")).toHaveCount(0);

  // And so does the mouse. `dragTo` drives the HTML5 events the cards and columns use.
  await card(backlog.identifier).dragTo(column("done"));
  await expect(column("done").getByTestId("board-card")).toHaveCount(1);
  await expect(column("in_progress").getByTestId("board-card")).toHaveCount(0);
});

test("05 — a project reads as a summary rather than as a second list", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Project");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  const projectName = unique("Sync engine");
  const project = await seedProject(api, { name: projectName, teamId: team.id });
  await api.put(`/api/projects/${project.id}`, {
    data: {
      name: projectName,
      teamId: team.id,
      start: { at: "2026-08-04T00:00:00Z", hasTime: false },
      end: { at: "2026-09-30T00:00:00Z", hasTime: false },
    },
  });

  // Six, so "See all" has something to be about: the page shows five.
  const seeded = [];
  for (let index = 0; index < 6; index += 1) {
    seeded.push(
      await seedTicket(api, {
        teamId: team.id,
        title: unique(`Ticket ${index}`),
        projectId: project.id,
      }),
    );
  }
  await api.patch(`/api/tickets/${seeded[0].id}`, { data: { status: "done" } });

  const page = await openAs(browser, ADMIN);
  await page.goto(`/p/${project.id}`);

  await expect(page.getByRole("heading", { name: projectName, level: 1 })).toBeVisible();
  // The period is formatted out of the ISO string and never through `new Date`: a bound
  // with `hasTime: false` names a day, and a reader west of UTC must not be shown the day
  // before the one that was posted.
  await expect(page.getByText("4 Aug → 30 Sep")).toBeVisible();
  await expect(page.getByText(teamName, { exact: true })).toBeVisible();

  // One done out of six, canceled excluded from the denominator — see `donePercent`.
  await expect(page.getByText(/· 17%/)).toBeVisible();
  await expect(page.getByText("1 done", { exact: true })).toBeVisible();
  await expect(page.getByText("5 todo", { exact: true })).toBeVisible();

  // Five and no more; the sixth is behind "See all", which lands on the list scoped to
  // this project rather than on a second copy of it here.
  await expect(page.getByTestId("project-ticket")).toHaveCount(5);
  await page.getByRole("link", { name: "See all" }).click();
  await expect(page.getByRole("heading", { name: projectName, level: 1 })).toBeVisible();
  await expect(page.getByTestId("ticket-row")).toHaveCount(6);
});

test("06 — one field over tickets, documents and commands, with a preview", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Search"), key: uniqueKey() });
  const needle = unique("echofinder");
  const ticket = await seedTicket(api, { teamId: team.id, title: `${needle} in the poll` });
  await api.patch(`/api/tickets/${ticket.id}`, {
    data: { description: "A page we wrote comes back on the next poll as a modification." },
  });

  const page = await openAs(browser, ADMIN);

  await page.keyboard.press("ControlOrMeta+k");
  // Scoped to the overlay throughout: it is drawn in front of the whole application, and
  // the sidebar behind it has rows whose text an unscoped locator would also match.
  const palette = page.getByTestId("palette");
  const field = palette.getByPlaceholder("Type a command…");
  await expect(field).toBeVisible();

  // An empty field lists the commands and nothing else: ⌘K opens on nothing typed, and
  // every ticket in the instance dumped there would bury what the palette is also for.
  await expect(palette.getByRole("group", { name: "What to search" })).toBeVisible();
  await expect(palette.getByTestId("search-result").first()).toBeVisible();

  await field.fill(needle);

  // The ticket is found, and the group heading says what kind of answer it is.
  const result = palette.getByTestId("search-result").filter({ hasText: ticket.identifier });
  await expect(result).toHaveCount(1);
  await expect(palette.getByText("Tickets", { exact: true })).toBeVisible();

  // The preview pane exists to let a reader decide without opening, which is why it shows
  // the description and not only the metadata the row already carries.
  await expect(palette.getByTestId("search-preview")).toContainText(ticket.identifier);
  await expect(palette.getByTestId("search-preview")).toContainText(/next poll/);

  // The counter reads shown against matched, both of them.
  await expect(palette.getByTestId("search-count")).toHaveText(/^\d+ of \d+ results?$/);

  // `tab` cycles the strip, and narrowing to Documents leaves the ticket out.
  const tab = (name: string) =>
    palette.getByRole("group", { name: "What to search" }).getByRole("button", { name, exact: true });

  await field.press("Tab");
  await expect(tab("Tickets")).toHaveAttribute("aria-pressed", "true");
  await field.press("Tab");
  await expect(tab("Documents")).toHaveAttribute("aria-pressed", "true");
  await expect(palette.getByTestId("search-result").filter({ hasText: ticket.identifier })).toHaveCount(0);

  // Back to All — four presses wrap — then `⇧↵`, the drawing's "en page", which is one of
  // the three ways screen 03 is reachable from the running application.
  await field.press("Tab");
  await field.press("Tab");
  await expect(tab("All")).toHaveAttribute("aria-pressed", "true");
  await field.press("Shift+Enter");
  await expect(page).toHaveURL(new RegExp(`/t/${ticket.identifier}$`));
  await expect(page.getByRole("heading", { level: 1, name: new RegExp(needle) })).toBeVisible();
});

test("06 — the dependency picker still has the overlay to itself", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Picker");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  const first = await seedTicket(api, {
    teamId: team.id,
    title: unique("Groundwork"),
    start: "2026-08-03",
    due: "2026-08-05",
  });
  const second = await seedTicket(api, {
    teamId: team.id,
    title: unique("Follows"),
    start: "2026-08-10",
    due: "2026-08-12",
  });

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: teamName, exact: true }).first().click();
  await page.getByRole("group", { name: "View" }).getByRole("button", { name: "Timeline" }).click();

  // `d` routes the picker through this overlay. Two lists of tickets on one screen with
  // different consequences is the one thing the rewrite must not do, so the search is off
  // here — no tab strip, no preview pane, and the only rows are the candidates.
  await page.getByRole("button", { name: new RegExp(second.identifier) }).first().click();
  await page.keyboard.press("d");
  const palette = page.getByTestId("palette");
  await expect(
    palette.getByRole("button", { name: new RegExp(`^Wait for ${first.identifier}`) }),
  ).toBeVisible();
  await expect(palette.getByRole("group", { name: "What to search" })).toHaveCount(0);
  await expect(palette.getByTestId("search-preview")).toHaveCount(0);
});

/**
 * The `openTicket` preference end to end.
 *
 * `user_preferences.open_ticket` is slice 0's column and is not on slice A's branch, so
 * the write below is answered with a preferences row that carries no `openTicket` and the
 * control reverts — which is the honest signal that nothing was stored, and exactly why
 * this is `fixme` rather than deleted. The client half is done: the control is rendered,
 * `openTicketMode` reads it, and the palette and the board card both honour it. Turn this
 * on in the integration pass, once the column exists.
 */
test.fixme("02/03 — the openTicket preference decides what ↵ gives", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Pref"), key: uniqueKey() });
  const needle = unique("prefsearch");
  const ticket = await seedTicket(api, { teamId: team.id, title: `${needle} matters` });

  const page = await openAs(browser, ADMIN);

  await page.keyboard.press(",");
  await page.getByRole("group", { name: "Open a ticket" }).getByRole("button", { name: "Page" }).click();
  await expect(
    page.getByRole("group", { name: "Open a ticket" }).getByRole("button", { name: "Page" }),
  ).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press("Escape");

  // Plain `↵` now navigates, where before it opened the panel — and `⇧↵` is unchanged,
  // because expanding the ticket in front of you never depended on the preference.
  await page.keyboard.press("ControlOrMeta+k");
  await page.getByTestId("palette").getByPlaceholder("Type a command…").fill(needle);
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(new RegExp(`/t/${ticket.identifier}$`));
});
