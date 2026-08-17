import { expect, test, type APIRequestContext, type Page } from "@playwright/test";
import { ADMIN, apiAs, openAs, seedInstance, seedTeam, seedTicket, unique, uniqueKey } from "./support";

/**
 * Slice C — screens 19, 20, 21 and 23.
 *
 * Four scenarios, one per screen, each proving the thing its drawing argues for rather than
 * that the page renders. The cycle screen's claim is that its two progress numbers cannot
 * disagree; triage's is that a decision advances and loses nothing; the saved view's is that
 * one strip action edits every selected row; workload's is that the load is a count and an
 * age and never an estimate.
 *
 * Queried by role and accessible name wherever there is one, and by `data-testid` for the
 * rows — which is what `e2e/README.md` asks of a new file, and the reason none of these
 * reach for a CSS class.
 */
test.beforeAll(seedInstance);

/** A cycle, since nothing in the interface creates one yet. */
async function seedCycle(
  api: APIRequestContext,
  teamId: string,
  body: { number: number; startsOn: string; endsOn: string; state: "upcoming" | "active" | "closed" },
) {
  const response = await api.post(`/api/teams/${teamId}/cycles`, { data: body });
  expect(response.ok(), `Could not create cycle ${body.number}`).toBeTruthy();
  return (await response.json()) as { id: string; number: number };
}

async function place(api: APIRequestContext, cycleId: string, ticketIds: string[]) {
  const response = await api.put(`/api/cycles/${cycleId}/tickets`, { data: { ticketIds } });
  expect(response.ok(), "Could not put the tickets in the cycle").toBeTruthy();
}

/**
 * Points the sidebar's scope at this team, which is how the four screens learn which team
 * they are about — none of them has a URL that names one.
 */
async function scopeTo(page: Page, teamName: string) {
  await page.getByRole("button", { name: teamName, exact: true }).first().click();
}

/**
 * These routes take the team in the URL, which is why this is a `goto` and not a click.
 *
 * `scopeTo` sets the sidebar scope, and the scope lives in a zustand store that a page load
 * wipes — so a bare `goto("/cycles/current")` after it landed on whichever team the
 * fallback picked, silently, and the assertions below measured a stranger's cycle. Naming
 * the team is what makes each of these a link rather than a coincidence.
 */
const at = (route: string, teamId: string) => `${route}?team=${teamId}`;

const day = (offset: number) =>
  new Date(Date.now() + offset * 86_400_000).toISOString().slice(0, 10);

test("scenario 19 — a cycle's progress is one fact, and what will not fit is named", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Cycling"), key: uniqueKey() });
  const cycle = await seedCycle(api, team.id, {
    number: 24,
    startsOn: day(-8),
    endsOn: day(6),
    state: "active",
  });
  await seedCycle(api, team.id, { number: 25, startsOn: day(7), endsOn: day(21), state: "upcoming" });

  const closed = await seedTicket(api, { teamId: team.id, title: unique("Closed already") });
  const open = await seedTicket(api, { teamId: team.id, title: unique("Still open") });
  await api.patch(`/api/tickets/${closed.id}`, { data: { status: "done" } });
  await place(api, cycle.id, [closed.id, open.id]);

  const page = await openAs(browser, ADMIN);
  await scopeTo(page, team.name);
  await page.goto(at("/cycles/current", team.id));

  // One closed of two is 50 %, and the count beside it has to be the same fact. The
  // drawing's own header shows "58 % · 14 tickets sur 24", which its breakdown contradicts;
  // this is the assertion that keeps the shipped version honest.
  await expect(page.getByText("50 %", { exact: true })).toBeVisible();
  await expect(page.getByText("1 of 2 tickets")).toBeVisible();

  // Nothing has closed fast enough to clear the remaining ticket, so it is named — and the
  // button says where it would go rather than just that something can be done.
  await expect(page.getByTestId("slipping-row")).toHaveCount(1);
  await expect(page.getByTestId("slipping-row")).toContainText(open.identifier);

  await page.getByRole("button", { name: "Move to cycle 25" }).click();
  await expect(page.getByTestId("slipping-row")).toHaveCount(0);

  await api.dispose();
});

test("scenario 19b — a triage decision advances, and the queue keeps what it dropped", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Triaging"), key: uniqueKey() });
  const first = await seedTicket(api, { teamId: team.id, title: unique("Reported first") });
  const second = await seedTicket(api, { teamId: team.id, title: unique("Reported second") });

  const page = await openAs(browser, ADMIN);
  await scopeTo(page, team.name);
  await page.goto(at("/triage", team.id));

  await expect(page.getByTestId("triage-row")).toHaveCount(2);
  // Oldest first: the ticket that has waited longest is the one being asked about.
  await expect(page.getByRole("heading", { level: 2 })).toHaveText(first.title);

  await page.getByRole("button", { name: /Close without action/ }).click();

  // The queue is one shorter and the panel has moved on by itself. That is what "each
  // decision passes to the next ticket" means, and it is the only assertion that
  // distinguishes advancing from merely removing a row.
  await expect(page.getByTestId("triage-row")).toHaveCount(1);
  await expect(page.getByRole("heading", { level: 2 })).toHaveText(second.title);

  // Nothing is lost. The trace is read over HTTP because the screen deliberately does not
  // draw it — the drawing promises the record exists, not that it is on this page.
  const trace = await api.get(`/api/teams/${team.id}/triage/decisions`);
  expect(trace.ok()).toBeTruthy();
  const decisions = (await trace.json()) as { decision: string; ticket: { identifier: string } }[];
  expect(decisions.map((row) => [row.ticket.identifier, row.decision])).toEqual([
    [first.identifier, "closed"],
  ]);

  await api.dispose();
});

test("scenario 19c — six rows selected, one strip action, and a chip removed widens the list", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Viewing"), key: uniqueKey() });
  const urgent = await seedTicket(api, { teamId: team.id, title: unique("Urgent one") });
  const low = await seedTicket(api, { teamId: team.id, title: unique("Low one") });
  await api.patch(`/api/tickets/${urgent.id}`, { data: { priority: "urgent" } });
  await api.patch(`/api/tickets/${low.id}`, { data: { priority: "low" } });

  // Seeded before the page opens, because the strip only offers the button when the team
  // owns a label and the page reads that list once.
  const madeLabel = await api.post(`/api/teams/${team.id}/labels`, {
    data: { name: "sync", colour: "indigo" },
  });
  expect(madeLabel.ok(), "a label is a scoped write like any other").toBeTruthy();
  const label = (await madeLabel.json()) as { id: string };

  const created = await api.post(`/api/teams/${team.id}/views`, {
    data: { name: unique("Urgent debt"), shared: true, filters: { priority: ["urgent"] } },
  });
  expect(created.ok()).toBeTruthy();
  const view = (await created.json()) as { id: string };

  const page = await openAs(browser, ADMIN);
  await scopeTo(page, team.name);
  await page.goto(`/views/${view.id}`);

  await expect(page.getByTestId("view-row")).toHaveCount(1);
  await expect(page.getByTestId("filter-chip")).toContainText("Urgent");

  // Removing the chip widens the answer, which is only true because a view stores the
  // question rather than the rows: a cached list would still show one.
  await page.getByRole("button", { name: "Remove Priority filter" }).click();
  await expect(page.getByTestId("view-row")).toHaveCount(2);
  await expect(page.getByTestId("filter-chip")).toHaveCount(0);

  // Two rows selected with the mouse, then one strip action for both. The strip is a
  // toolbar with its count as its accessible name, so the count is asserted by name.
  await page.getByTestId("view-row").nth(0).click();
  await page.getByTestId("view-row").nth(1).click();
  await expect(page.getByRole("toolbar", { name: "2 selected" })).toBeVisible();

  await page.getByRole("button", { name: "Set status" }).click();
  await page.getByRole("menuitem", { name: "In review" }).click();

  // The selection is cleared by the write succeeding, and both rows moved — one request,
  // not two, which is what makes "one refused row changes nothing" possible at all.
  await expect(page.getByRole("toolbar")).toHaveCount(0);
  const after = await api.get(`/api/tickets?teamId=${team.id}`);
  const tickets = (await after.json()) as { id: string; status: string }[];
  expect(tickets.filter((ticket) => ticket.status === "in_review")).toHaveLength(2);

  // --- the drawing's sixth button, and its third chip ------------------------

  await page.getByTestId("view-row").nth(0).click();
  await page.getByTestId("view-row").nth(1).click();
  await page.getByRole("button", { name: "Label", exact: true }).click();
  await page.getByRole("menuitem", { name: "sync" }).click();
  await expect(page.getByRole("toolbar")).toHaveCount(0);

  const worn = await api.get(`/api/tickets/${urgent.id}/labels`);
  expect(
    ((await worn.json()) as { name: string }[]).map((each) => each.name),
    "the strip put the label on every selected row",
  ).toEqual(["sync"]);

  /**
   * The chip `Étiquette synchro`, which the server refused outright until `V8` landed.
   * The filter stores the label's *id* — two teams may both own the name `sync` — and the
   * chip prints the name, so this asserts both halves: the view answers with the rows
   * wearing it, and the `×` removes the key like every other chip's does.
   */
  const filtered = await api.patch(`/api/views/${view.id}`, {
    data: { filters: { label: [label.id] } },
  });
  expect(filtered.ok(), "a label filter is served, not refused").toBeTruthy();

  await page.reload();
  await expect(page.getByTestId("filter-chip")).toContainText("sync");
  await expect(page.getByTestId("view-row")).toHaveCount(2);

  await page.getByRole("button", { name: "Remove Label filter" }).click();
  await expect(page.getByTestId("filter-chip")).toHaveCount(0);
  await expect(page.getByTestId("view-row")).toHaveCount(2);

  await api.dispose();
});

test("scenario 19d — workload is a count and an age, never an estimate", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Loaded"), key: uniqueKey() });
  const mine = await seedTicket(api, { teamId: team.id, title: unique("Mine") });
  await seedTicket(api, { teamId: team.id, title: unique("Nobody's") });

  const me = await api.get("/api/me");
  const { user } = (await me.json()) as { user: { id: string; displayName: string } };
  await api.put(`/api/tickets/${mine.id}/assignees`, { data: [user.id] });

  const page = await openAs(browser, ADMIN);
  await scopeTo(page, team.name);
  await page.goto(at("/workload", team.id));

  // One row for the person, one for the pile nobody owns — the drawing's own fourth row,
  // and the reason it is a row rather than a footnote in the copy.
  await expect(page.getByTestId("workload-row")).toHaveCount(2);
  await expect(page.getByTestId("workload-row").last()).toContainText("Unassigned");

  /**
   * The bar's accessible name is the count and the age, in words. If a points column ever
   * arrives, this is where it would have to be described — and the drawing forbids it.
   *
   * Scoped to the person's row: both rows here carry one open ticket zero days old, so the
   * name is ambiguous by construction rather than by accident, and asserting it unscoped
   * matched two elements.
   */
  await expect(
    page.getByTestId("workload-row").first().getByRole("img", { name: /1 open, oldest 0 days/ }),
  ).toBeVisible();
  await expect(page.getByText(/No estimate in points/)).toBeVisible();

  await api.dispose();
});
