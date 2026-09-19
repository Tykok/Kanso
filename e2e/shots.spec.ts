import { join } from "node:path";
import {
  expect,
  test,
  type APIRequestContext,
  type Browser,
  type Page,
} from "@playwright/test";
import {
  ADMIN,
  API_URL,
  WEB_URL,
  apiAs,
  openRowMenu,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  userIdOf,
  type SeededTicket,
} from "./support";

/**
 * The five captures the site's walk-through is laid out against — and why they are
 * a test rather than a script that navigates and saves files.
 *
 * Every shot waits on the locators the screen it photographs is made of and asserts
 * what the caption claims *before* the shutter opens, so a screen that moved makes this
 * go red instead of producing a tidy photograph of the wrong thing. These are no longer
 * placeholders: `pnpm shots:pack` packs what this writes into `site/media/v1/`, and that
 * is what the published page shows — which is exactly why a wrong one must not pass
 * quietly. Video clips still come later, from the author.
 *
 * Nothing here is named with `unique()`. A timestamped team would give every
 * regeneration a different picture, which is the one property these cannot afford — so
 * the names are literal, and a virgin database becomes a precondition instead of a
 * preference. It is checked before anything is written.
 *
 * Not in the default run: `grepInvert` in `playwright.config.ts` says why.
 */

test.beforeAll(seedInstance);

/**
 * Where `pnpm shots:pack` looks for them. Resolved off this file rather than off the
 * working directory, so the five land beside the site that shows them whichever
 * directory the run was started from. These PNGs stay `.gitignore`d — about 1.1 MB for
 * the set, and what the site publishes is the 233 KB of WebP the pack step writes one
 * level down, in `v1/`. `.gitignore` carries the arithmetic.
 */
const MEDIA = join(__dirname, "..", "site", "media");

const TEAM = { name: "Atlas", key: "KAN" };
const PROJECT = "Notion mirror";
const VIEW = "Assigned to me";

/**
 * Who the reader sees, and who the rest of the suite needs to keep seeing.
 *
 * `support.ts` claims the instance as `E2E owner`, which is the right name for a fixture
 * and the wrong one for a landing page: it is the assignee in shot 02's composer and the
 * person the filter chip resolves to in shot 05, so the walk-through would show a product
 * whose one user is the test harness. The owner of this instance is the owner of the site
 * — change `PICTURED` and nothing else if the name should be another.
 *
 * Put back afterwards because four other specs assert `E2E owner` by name
 * (`mouse`, `18-documents`, `26-doc-collaboration`), and this file is the only one that
 * renames anybody. A run that fails in the middle leaves the new name behind; that costs
 * nothing, since a second run is refused until the database is reset anyway.
 */
const PICTURED = "Elie Treport";
const SEEDED = "E2E owner";

async function rename(api: APIRequestContext, displayName: string): Promise<void> {
  const named = await api.put("/api/me", { data: { displayName } });
  expect(named.ok(), `Could not name the owner ${displayName}`).toBeTruthy();
}

/**
 * The plan, in absolute days.
 *
 * Hardcoded rather than counted from today: a relative plan puts its bars somewhere else
 * on every regeneration, and two captures of one timeline a month apart would not be the
 * same picture. The chart's window is these days padded either side, so the layout does
 * not depend on when the run happened; the today marker does, and moves.
 *
 * Read as a table, because that is what it is: the statuses spread across all six board
 * columns, the two middle rows are the pair the arrow joins, and the one with no dates is
 * what the timeline's tray is for.
 */
const PLAN: {
  title: string;
  status: string;
  priority: string;
  start?: string;
  due?: string;
  /** Assigned to the owner, which is what the saved view filters on. */
  mine?: boolean;
}[] = [
  {
    title: "Draft the mirror schema",
    status: "in_progress",
    priority: "high",
    start: "2026-09-01",
    due: "2026-09-04",
    mine: true,
  },
  {
    title: "Create the four Notion databases",
    status: "todo",
    priority: "medium",
    start: "2026-09-07",
    due: "2026-09-11",
  },
  {
    title: "Poll for edits made outside Kanso",
    status: "todo",
    priority: "urgent",
    start: "2026-09-14",
    due: "2026-09-18",
    mine: true,
  },
  {
    title: "Retry a page write the API refused",
    status: "in_review",
    priority: "none",
    start: "2026-09-03",
    due: "2026-09-08",
  },
  { title: "Explain the mirror in the docs", status: "backlog", priority: "low", mine: true },
  {
    title: "Adopt an existing workspace, once",
    status: "done",
    priority: "medium",
    start: "2026-09-02",
    due: "2026-09-09",
  },
];

/** Indices into `PLAN`: the second row waits for the first, and shot 03 is about that. */
const PREDECESSOR = 1;
const SUCCESSOR = 2;

/** What the counter has to produce for the captions to be true. */
const IDENTIFIERS = ["KAN-1", "KAN-2", "KAN-3", "KAN-4", "KAN-5", "KAN-6"];

/**
 * Stops the run before it writes anything if `Atlas` is already there.
 *
 * A ticket's identifier comes off a counter on its team row, so `KAN-1` is `KAN-1` only
 * the first time. Reusing the existing team would produce a set of pictures numbered
 * from wherever that counter happens to stand, under captions that say `KAN-1` — which
 * is the failure this whole file is written to make impossible, so it is a refusal and
 * not a fallback.
 */
async function refuseUnlessVirgin(api: APIRequestContext): Promise<void> {
  const response = await api.get("/api/teams", { params: { includeArchived: true } });
  expect(response.ok(), `No answer from the API at ${API_URL}. Is the stack up?`).toBeTruthy();
  const teams = (await response.json()) as { name: string }[];
  if (teams.some((team) => team.name === TEAM.name)) {
    throw new Error(
      `A team called ${TEAM.name} already exists, so this run would not number its tickets ` +
        `${IDENTIFIERS[0]} to ${IDENTIFIERS[IDENTIFIERS.length - 1]} — the counter lives on the ` +
        `team row. These captures need a virgin database. Reset the stack and run again:\n\n` +
        `  docker compose down -v && KANSO_AUTH_MODE=dev docker compose up -d --build --wait\n`,
    );
  }
}

/**
 * The one piece of the shell that has no business on a landing page.
 *
 * The shortcut bar ends with `DevUserSwitcher`, and the captures are necessarily taken
 * against `KANSO_AUTH_MODE=dev` — the identity in this suite is a header. So the corner
 * of every shot reads `dev as [owner@kanso.test]`: a text field asking the reader to type
 * an email nobody verifies, which is a development affordance photographed as a feature.
 * It is not what any of the five captions is about.
 *
 * Hidden rather than worked around, because there is no other way to reach these screens:
 * `oidc` mode wants a provider, and the shortcut bar is otherwise worth keeping — the
 * keys it prints are exactly what `step.ticket.caption` claims. Matched on the `title` the
 * component writes for the affordance itself, so a rename of the bar cannot silently stop
 * hiding it; what a moved selector produces is the switcher back in frame, which the
 * author sees when he looks at the five files before committing them.
 */
const HIDE_DEV_IDENTITY = () => {
  const hide = () => {
    const style = document.createElement("style");
    style.textContent = `form:has(> span[title^="Dev auth"]) { display: none !important; }`;
    document.head.append(style);
  };
  // An init script runs before the document has a `<head>` on a fresh navigation, and
  // after it has one on a client-side one. Both happen here: shot 05 is a `goto`.
  if (document.head) hide();
  else document.addEventListener("DOMContentLoaded", hide, { once: true });
};

/**
 * A page that photographs the same on anybody's machine: 1440×900 at 2×, light whatever
 * the operating system prefers.
 *
 * Not `openAs`. That helper cannot express `deviceScaleFactor`, which is fixed when a
 * context is created, and the theme has to be pinned in three places rather than one:
 * `colorScheme` for the media query a `system` preference defers to, `localStorage` for
 * the blocking script in `<head>` that decides the first paint, and the server copy —
 * set in the seed — for what the client applies once `/api/me` answers. Leave any of the
 * three out and the shots come back dark on somebody else's laptop.
 *
 * Both storage keys are spelled here rather than imported: this suite reaches no module
 * of the web client, which is why `support.ts` already writes `kanso.devUser` by hand.
 */
async function openLitPage(browser: Browser): Promise<Page> {
  const context = await browser.newContext({
    baseURL: WEB_URL,
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 2,
    colorScheme: "light",
  });
  await context.addInitScript((who: string) => {
    window.localStorage.setItem("kanso.devUser", who);
    window.localStorage.setItem("kanso.preferences", JSON.stringify({ theme: "light" }));
  }, ADMIN);
  await context.addInitScript(HIDE_DEV_IDENTITY);
  const page = await context.newPage();
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  return page;
}

/**
 * One capture: the viewport, not an element.
 *
 * The shell is `h-screen`, so the viewport *is* the application — there is no page below
 * the fold to include or exclude — and five files of one size is what a walk-through can
 * lay out inside a fixed `aspect-ratio` box. Animations are frozen so no shot can land
 * halfway through a transition.
 */
async function shoot(page: Page, file: string): Promise<void> {
  await page.screenshot({ path: join(MEDIA, file), animations: "disabled" });
}

test("the walk-through's five captures", { tag: "@shots" }, async ({ browser }) => {
  // A dozen writes, five screens and one browser: well past the 45 seconds a scenario
  // is given, and the whole set has to be produced in one run to be one set.
  test.setTimeout(180_000);

  const api = await apiAs(ADMIN);
  await refuseUnlessVirgin(api);

  // Light from the server too: the client applies the stored preference as soon as
  // `/api/me` answers, over whatever the bootstrap script had painted.
  const lit = await api.put("/api/me/preferences", { data: { theme: "light" } });
  expect(lit.ok(), "Could not pin the theme to light").toBeTruthy();
  await rename(api, PICTURED);

  const ownerId = await userIdOf(ADMIN);
  const team = await seedTeam(api, TEAM);
  const project = await seedProject(api, { name: PROJECT, teamId: team.id });

  const tickets: SeededTicket[] = [];
  for (const planned of PLAN) {
    const ticket = await seedTicket(api, {
      teamId: team.id,
      projectId: project.id,
      title: planned.title,
      start: planned.start,
      due: planned.due,
    });
    // `seedTicket` posts a title and its bounds; the status and the priority are what
    // give the board six occupied columns and the rows their marks.
    const dressed = await api.patch(`/api/tickets/${ticket.id}`, {
      data: { status: planned.status, priority: planned.priority },
    });
    expect(dressed.ok(), `Could not set the status of ${ticket.identifier}`).toBeTruthy();
    if (planned.mine) {
      const assigned = await api.put(`/api/tickets/${ticket.id}/assignees`, { data: [ownerId] });
      expect(assigned.ok(), `Could not assign ${ticket.identifier}`).toBeTruthy();
    }
    tickets.push(ticket);
  }

  /*
   * The numbering, asserted rather than assumed. The refusal above goes by name, and a
   * name is the one thing about a team that can change after it was created — so it
   * catches the ordinary case early, with something actionable to say, and this catches
   * the rest. Every caption the site writes around these five files names `KAN-1`.
   */
  expect(tickets.map((ticket) => ticket.identifier)).toEqual(IDENTIFIERS);

  const predecessor = tickets[PREDECESSOR];
  const successor = tickets[SUCCESSOR];
  const linked = await api.post(`/api/tickets/${successor.id}/dependencies`, {
    data: { predecessorId: predecessor.id },
  });
  expect(linked.status(), "the dependency was refused").toBe(201);

  const created = await api.post(`/api/teams/${team.id}/views`, {
    data: {
      name: VIEW,
      shared: true,
      filters: { assignee: [ownerId] },
      // Named rather than left to the server's defaults, which are these two: a picture
      // that quietly followed a default changing would be a picture nobody chose.
      groupBy: "status",
      sortBy: "priority",
    },
  });
  expect(created.status(), "the saved view was refused").toBe(201);
  const view = (await created.json()) as { id: string; count: number };
  const mine = tickets.filter((_, index) => PLAN[index].mine);
  // The count travels with the view, so a filter that matched nothing is caught here
  // rather than photographed as an empty list under the caption "assigned to me".
  expect(view.count, "the view does not answer with the assigned tickets").toBe(mine.length);

  const page = await openLitPage(browser);
  await page.getByRole("button", { name: TEAM.name, exact: true }).click();
  await expect(page.getByRole("heading", { name: TEAM.name, level: 1 })).toBeVisible();
  await expect(page.getByTestId("ticket-row")).toHaveCount(PLAN.length);

  await test.step("01 — creating a project", async () => {
    // From the team's own row menu, because that is what prefills the Team field: a
    // dialog photographed reading "— none, a transverse project —" would illustrate the
    // one case the caption is not about.
    const menu = await openRowMenu(page, TEAM.name);
    await menu.getByRole("menuitem", { name: "New project", exact: true }).click();

    const dialog = page.getByRole("dialog", { name: "New project" });
    await dialog.getByLabel("Name").fill("Search relevance");
    await expect(dialog.getByLabel("Name")).toHaveValue("Search relevance");
    await expect(dialog.getByLabel("Team")).toHaveValue(team.id);
    await expect(dialog.getByRole("button", { name: "Create" })).toBeEnabled();

    await shoot(page, "01-project.png");

    /*
     * Closed, not submitted. The seed above is the whole of what these five pictures
     * are of, and a seventh row in the sidebar would appear in the four that follow —
     * the form filled in and about to be sent is also the moment the caption is about.
     */
    await page.keyboard.press("Escape");
    await expect(dialog).toHaveCount(0);
  });

  await test.step("02 — creating a ticket", async () => {
    await page.keyboard.press("c");
    const title = page.getByPlaceholder("New ticket…");
    await expect(title).toBeFocused();
    await title.fill("Warn when two Notion writes collide");

    // The composer prefills from where the reader is standing and from who is asking.
    // Both are what the caption claims, so both are read back before the shutter.
    await expect(page.getByLabel("Ticket team")).toHaveValue(team.id);
    await expect(page.getByLabel("Assignee")).toHaveValue(ownerId);

    await shoot(page, "02-ticket.png");

    await page.keyboard.press("Escape");
    await expect(title).toHaveCount(0);
  });

  await test.step("03 — the timeline, and a dependency across it", async () => {
    await page
      .getByRole("group", { name: "View" })
      .getByRole("button", { name: "Timeline" })
      .click();

    // A bar is named `${identifier}: ${title} — ${status}` and gains a clause when it
    // has slack, so it is matched on the identifier and the colon. Nothing is escaped:
    // an identifier carries no character a RegExp reads as its own.
    const bar = (ticket: SeededTicket) =>
      page.getByRole("button", { name: new RegExp(`^${ticket.identifier}: `) });
    await expect(bar(predecessor)).toBeVisible();
    await expect(bar(successor)).toBeVisible();

    // The subject of the shot. Without the arrow the picture is six unrelated bars, and
    // the walk-through's claim is that the chart knows what waits for what.
    await expect(
      page.getByRole("button", { name: `${predecessor.identifier} → ${successor.identifier}` }),
    ).toBeVisible();

    /*
     * One end with slack, one end without — which is the pair of marks the shot is for,
     * and not what this used to assert.
     *
     * It asked for `normal` at both ends, on the grounds that neither is late and neither
     * is on a critical path, and said that it would expire once the clock passed those
     * absolute days and every bar started reading "overdue". Both halves were wrong, and
     * the first made the shot impossible to produce at all:
     *
     * `late` is negative slack, not a due date in the past — `TimelineService` reads
     * `slackMinutes`, and the clock is nowhere in it, so time passing changes no bar's
     * state. And `critical` is `slackMinutes == 0`, computed by a backward pass that
     * anchors whatever has no successor at the end of its own chain (`CriticalPath`,
     * `chainEnd`). The last ticket of a chain therefore has zero slack by construction:
     * the successor is *always* `critical`, and `normal` could never have passed.
     *
     * That costs the picture nothing. `BarState` says `critical` carries no colour of its
     * own — the bar is drawn exactly like an ordinary one of the same status — so what the
     * reader sees is the predecessor's two days of slack, hatched, running into an arrow.
     * That is `about`'s own sentence: the critical path is emphasised, slack is hatched.
     *
     * What can still expire is the plan's shape, not its states: `PLAN` is absolute days,
     * and once they are all behind the clock the today marker leaves the window and the
     * chart is a photograph of finished work. Move `PLAN` forward when it does.
     */
    await expect(bar(predecessor)).toHaveAttribute("data-state", "normal");
    await expect(bar(successor)).toHaveAttribute("data-state", "critical");

    // The dateless one is a chip in the tray rather than a bar, which is the other half
    // of what a timeline over real work looks like.
    await expect(page.getByRole("button", { name: /^Unscheduled · 1$/ })).toBeVisible();

    await shoot(page, "03-timeline.png");
  });

  await test.step("04 — the board", async () => {
    await page.getByRole("group", { name: "View" }).getByRole("button", { name: "Board" }).click();
    await expect(page.getByTestId("board")).toBeVisible();

    // Six columns whatever the rows are doing — the board is the six statuses, not the
    // statuses in use — and every ticket drawn as a card in one of them.
    await expect(page.getByTestId("board-column")).toHaveCount(6);
    await expect(page.getByTestId("board-card")).toHaveCount(PLAN.length);

    await shoot(page, "04-board.png");
  });

  await test.step("05 — the saved view, filtered to the assignee", async () => {
    await page.goto(`/views/${view.id}`);

    const chip = page.getByTestId("filter-chip");
    await expect(chip).toHaveCount(1);
    await expect(chip).toContainText("Assignee");
    /*
     * The chip resolves the id it stores into the name of a person, off the users query.
     * When that lookup misses it prints the uuid instead — so the fallback is what this
     * asserts against, rather than the name itself, which whoever owns the instance is
     * free to change. A uuid in a chip is the one thing this picture must not show.
     */
    await expect(chip).not.toContainText(ownerId);

    const rows = page.getByTestId("view-row");
    await expect(rows).toHaveCount(mine.length);
    for (const ticket of mine) {
      await expect(rows.filter({ hasText: ticket.identifier })).toHaveCount(1);
    }

    await shoot(page, "05-assigned.png");
  });

  await rename(api, SEEDED);
  await api.dispose();
});
