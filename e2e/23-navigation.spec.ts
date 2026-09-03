import { expect, test, type Page } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  navLink,
  openAs,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  sidebarRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 23 — one column, one selection, one way out.
 *
 * The three complaints this slice exists for, each asserted where a unit test cannot
 * reach:
 *
 * 1. "Quand je clique sur Trash elle disparaît." Four routes rendered no shell at all, so
 *    the column was genuinely absent on `/trash`, `/settings`, `/docs` and `/docs/[id]`.
 * 2. "Quand j'entre dans certaines Views je ne peux pas en sortir." The four `Back` links
 *    were `<Link href="/">`, and `/docs/[id]` had not even that.
 * 3. "Je peux avoir le focus sur All Tickets MAIS AUSSI sur les autres onglets." Two
 *    selection axes that never consulted each other.
 *
 * The third is the one this file is really for. `lib/nav.test.ts` proves the *rule* over
 * every route crossed with every scope; what only a browser can prove is that the three
 * row components actually ask it — that no component kept a rule of its own. So the
 * assertion here is a **count over the rendered DOM**, taken on every destination in turn.
 */

/**
 * The rows whose selection is the one the rule decides: the Views group and the tree.
 *
 * Deliberately not the Favourites section. A pin is the same destination named a second
 * time, higher up, so a pinned view and the `Saved views` row above it are both current
 * when that view is open — which is what the section is *for*, not a second axis.
 */
const currentRows = (page: Page) =>
  page.locator('[data-testid="nav-item"][data-current="true"]:not([data-favourite="true"])');

test("scenario 23 — every destination keeps the column, lights one row, and has a way out", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Nav");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  const projectName = unique("Column");
  const project = await seedProject(api, { name: projectName, teamId: team.id });
  const ticket = await seedTicket(api, {
    teamId: team.id,
    title: unique("Reachable"),
    projectId: project.id,
  });

  const page = await openAs(browser, ADMIN);

  // --- 1 and 3: click every row in turn ------------------------------------

  /**
   * The route rows, by the label the column draws. `Inbox` is no longer among them — §4
   * moved it to the bell in the top bar, which the third test below covers — and `My
   * view` is first, because the personal home comes before the instance's list.
   */
  const routes = [
    "My view",
    "Triage",
    "Cycle",
    "Saved views",
    "Workload",
    "Documents",
    "Trash",
  ];

  for (const label of routes) {
    await page.getByRole("link", { name: label, exact: true }).click();

    // The column is still there. This is the first complaint, asserted on every row
    // rather than only on the one that was reported: four of these seven had no shell.
    await expect(sidebarRow(page, "All tickets")).toBeVisible();

    // And exactly one row is lit — never two, never none. `All tickets` in particular is
    // dark, which is the half of the bug that made the column unreadable: the scope is
    // still `all` on every one of these routes, and it used to light its row anyway.
    await expect(currentRows(page)).toHaveCount(1);
    await expect(currentRows(page)).toContainText(label);

    // Somewhere you went has a way out, and it is the same control everywhere. The four
    // routes that had a `Back` link and the three that had nothing now agree.
    await expect(page.getByTestId("shell-leave")).toBeVisible();
  }

  // --- the scope rows, which are the other axis ------------------------------

  await page.getByRole("button", { name: teamName, exact: true }).click();
  // Clicking a subject *navigates*. It used to only call `setScope`, so from a cycle
  // report the click set a value nothing on screen was drawing.
  await expect(page).toHaveURL(new RegExp(`/\\?team=${team.id}$`));
  await expect(page.getByRole("heading", { name: teamName, level: 1 })).toBeVisible();
  await expect(currentRows(page)).toHaveCount(1);
  await expect(currentRows(page)).toContainText(teamName);
  // Home is not somewhere you went, so there is no `×` to offer.
  await expect(page.getByTestId("shell-leave")).toHaveCount(0);

  await page.getByRole("button", { name: projectName, exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/\\?project=${project.id}$`));
  await expect(currentRows(page)).toHaveCount(1);
  await expect(currentRows(page)).toContainText(projectName);

  // The scope survives a reload now, because the address says what it shows. Without the
  // mirror this landed back on "All tickets" — the store is wiped by a page load, which
  // is the whole reason `?team=` had to be bolted onto `useOrganiseTeam`.
  await page.reload();
  await expect(currentRows(page)).toHaveCount(1);
  await expect(currentRows(page)).toContainText(projectName);

  // A view row visited *from* a project scope lights only itself. This is the exact pair
  // the complaint named: a route row and a scope row, both lit, neither aware of the other.
  await page.getByRole("link", { name: "Workload", exact: true }).click();
  await expect(currentRows(page)).toHaveCount(1);
  await expect(currentRows(page)).toContainText("Workload");

  // --- a record page lights nothing, and says why -----------------------------

  // `/t/[key]` and `/p/[id]` are not destinations the column offers, so no row is theirs.
  // The project page still *sets* the scope — the composer seeds from it — and that is no
  // longer enough to light the project's row, because the reader is not on the list.
  await page.goto(`/t/${ticket.identifier}`);
  await expect(page.getByRole("heading", { level: 1, name: ticket.title })).toBeVisible();
  await expect(currentRows(page)).toHaveCount(0);
  await expect(sidebarRow(page, "All tickets")).toBeVisible();

  // --- 2: the `×` and `esc` are one action ------------------------------------

  // Both leave, and both go *back* rather than home. Reached from the ticket page above,
  // so "back" is a real place and not the fallback.
  await page.getByTestId("shell-leave").click();
  await expect(page.getByRole("heading", { level: 1, name: ticket.title })).toHaveCount(0);
  await expect(currentRows(page)).toContainText("Workload");

  await page.goto(`/t/${ticket.identifier}`);
  await expect(page.getByRole("heading", { level: 1, name: ticket.title })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("heading", { level: 1, name: ticket.title })).toHaveCount(0);

  await api.dispose();
});

/**
 * The overlays, on a route that used to mount none of them.
 *
 * `OrganiseShell` drew the sidebar and nothing else, so on `/cycles`, `/triage`, `/views`,
 * `/views/[id]`, `/workload` and `/inbox` every control that opens an overlay was a dead
 * click: the brand menu's Settings and Keyboard shortcuts, `⌘K`, the column's `+ New
 * team`, and every `⋯` on a team row. Worse than inert — `useUi` is a module-level store,
 * so the click set a value nobody drew, and navigating to `/` afterwards made a dialog
 * appear out of nowhere about a team picked minutes earlier on another screen.
 */
test("scenario 23 — the overlays are mounted on every route, not only on the list", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  // A team, so the chart this lands on has something to be about rather than an empty
  // state — the overlays are what is under test, not the workload.
  await seedTeam(api, { name: unique("Overlay"), key: uniqueKey() });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("link", { name: "Workload", exact: true }).click();
  await expect(page.getByTestId("breadcrumb-crumb").last()).toHaveText("Workload");

  // `⌘K`, from a route whose shell had no palette at all.
  await page.keyboard.press("ControlOrMeta+k");
  await expect(page.getByPlaceholder("Type a command…")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByPlaceholder("Type a command…")).toHaveCount(0);

  // `?` and `,`, which the brand menu also offers here and which landed nowhere.
  await page.keyboard.press("?");
  await expect(page.getByRole("heading", { name: "Keyboard" })).toBeVisible();
  await page.keyboard.press("Escape");
  await page.keyboard.press(",");
  await expect(page.getByRole("link", { name: "All settings" })).toBeVisible();
  await page.keyboard.press("Escape");

  /**
   * A dialog opened from the column, on this route, drawn on this route. It is the one
   * that used to be set here and appear later on `/`.
   *
   * The header's `+ New team` rather than a row's `Rename team`: the rename dialog reads
   * `keys.teams(true)` and draws a `Loading…` placeholder on a cold cache, whose unmount
   * races the real dialog's mount (`14-menu-keyboard.spec.ts` documents it at length).
   * That race is not what is under test here, and creating at the root needs no query.
   */
  await page.getByRole("button", { name: "New team", exact: true }).click();
  await expect(page.getByRole("dialog", { name: "New team" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Escape closed the dialog and did *not* also leave the page: one gesture, in order —
  // what is open first, the page only when there is nothing left to close.
  await expect(page.getByTestId("breadcrumb-crumb").last()).toHaveText("Workload");
});

/**
 * The breadcrumb, which is derived rather than written by each page.
 *
 * `/docs` is the assertion that matters. Its own header read `Tickets / Documents`, with
 * `Tickets` a link home — a parent `/docs` does not have, and the one crumb in the app
 * that invited a click and then lost the reader's place.
 */
test("scenario 23 — the breadcrumb names the route, and claims no parent it does not have", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Crumb");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  const crumbs = page.getByTestId("breadcrumb-crumb");

  // Nothing at the root: home has no trail, and the list already names what it shows in
  // its own heading.
  await expect(crumbs).toHaveCount(0);

  await navLink(page, "Documents").click();
  await expect(crumbs).toHaveText(["Documents"]);
  // And no link out of it. The trail says where you are; the column is how you go
  // somewhere else, and the `×` is how you leave.
  await expect(page.getByRole("navigation", { name: "Breadcrumb" }).getByRole("link")).toHaveCount(0);

  await page.getByRole("link", { name: "Trash", exact: true }).click();
  await expect(crumbs).toHaveText(["Trash"]);

  // The team-scoped routes put the team first, and it is the team the row carried
  // forward in `?team=` rather than whichever one the fallback would have picked.
  await page.getByRole("button", { name: teamName, exact: true }).click();
  await page.getByRole("link", { name: "Workload", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/workload\\?team=${team.id}$`));
  await expect(crumbs).toHaveText([teamName, "Workload"]);

  // Still that team's chart after a reload, which is the property `?team=` buys: the
  // scope lives in a store a page load wipes, and the fallback would silently resolve a
  // different team with nothing on screen admitting the substitution.
  await page.reload();
  await expect(crumbs).toHaveText([teamName, "Workload"]);
});

/**
 * The sidebar's three modes, and the thing that makes a reveal feel broken.
 *
 * Only a browser can be asked these questions. `reveal.test.ts` proves the flicker rule
 * over coordinates — the pointer that leaves through the content against the one that
 * reaches past the top of the window — and what it cannot prove is that the panel is
 * wired to it: that a `mouseleave` happens at all, that the transform is what hides the
 * column, that focus reaching the panel reveals it, and that focus leaving takes it away
 * again rather than sitting behind something drawn over the page.
 *
 * The preference is server-side and shared with every other scenario in this suite, so it
 * is put back in a `finally`. `pinned` is the default and the state the other twenty-two
 * files assume.
 */
test("scenario 23 — pinned, hover and hidden, and a way back from each", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const setMode = async (mode: "pinned" | "hover" | "hidden") => {
    const saved = await api.put("/api/me/preferences", { data: { sidebarMode: mode } });
    expect(saved.ok(), `Could not set sidebarMode=${mode}`).toBeTruthy();
  };

  try {
    const page = await openAs(browser, ADMIN);
    const reveal = page.getByTestId("sidebar-reveal");
    const hotZone = page.getByTestId("sidebar-hot-zone");
    const anchor = page.getByTestId("sidebar-anchor");
    const trigger = page.getByTestId("sidebar-reveal-trigger");

    // --- pinned: the column, and the icon that collapses it -------------------

    await expect(sidebarRow(page, "All tickets")).toBeVisible();
    // Nothing temporary exists while the column is pinned, and the top bar offers no way
    // in: there is nothing to reveal, and a control whose only honest behaviour is to do
    // nothing is the same mistake as a `×` on the home screen.
    await expect(reveal).toHaveCount(0);
    await expect(trigger).toHaveCount(0);

    // `PanelLeft` beside the seal. It writes `hover` and never `hidden` — a third state
    // you discover by pressing a button twice is what this pass exists to remove.
    await anchor.click();
    await expect(reveal).toHaveCount(1);
    await expect(reveal).toHaveAttribute("data-open", "false");
    await expect(hotZone).toHaveCount(1);
    await expect(trigger).toBeVisible();

    // --- hover: the 12px edge, and the two ways out of the panel ---------------

    /**
     * A graze, from outside in — and the "outside" is the point of it.
     *
     * `hover()` moves the pointer, and a pointer already at the destination moves
     * nowhere: no `mouseenter`, so no reveal. Twice below the previous step leaves it
     * resting on the 12px edge — `Escape` retracts the panel without moving the mouse —
     * so a bare second `hotZone.hover()` was asserting that the panel opens on nothing
     * having happened, and read as a broken reveal. Leaving through the content first is
     * also the gesture a reader performs, and it is the same coordinates the retraction
     * below uses.
     */
    const graze = async () => {
      await page.mouse.move(900, 500);
      await hotZone.hover();
    };

    await graze();
    await expect(reveal).toHaveAttribute("data-open", "true");

    // Into the panel, which keeps it out: the hot zone is under it once it has slid in,
    // so without the panel holding it too the reveal would close the moment the pointer
    // moved a pixel off the edge it came in on.
    await reveal.hover();
    await expect(reveal).toHaveAttribute("data-open", "true");

    // And out through the content, which retracts it. This is the leave the geometry
    // answers `true` for, taken through the DOM rather than as coordinates.
    await page.mouse.move(900, 500);
    await expect(reveal).toHaveAttribute("data-open", "false");

    // Focus reaches it, which is what `focus-within` is for: the panel is in the tab
    // order while it is closed, deliberately, because an `inert` panel cannot be tabbed
    // into at all. It is invisible for zero frames — the focus that arrives reveals it.
    await page.getByTestId("brand-trigger").focus();
    await expect(reveal).toHaveAttribute("data-open", "true");

    /*
     * And focus can get out, which is the `Backdrop` defect this must not reproduce: a
     * visible overlay with focus behind it. There is no trap — a trap is the wrong shape
     * for something the reader never asked to enter — so instead the panel is gone in the
     * same commit as the focus that leaves it. Never a frame where the two disagree.
     */
    await page.getByTestId("inbox-bell").focus();
    await expect(reveal).toHaveAttribute("data-open", "false");

    // `Escape` retracts it, and does *not* also leave the page: the peek is what is open,
    // so closing it is the whole of the gesture, which is `use-shell-keys`' own order one
    // layer further out.
    await graze();
    await expect(reveal).toHaveAttribute("data-open", "true");
    await page.keyboard.press("Escape");
    await expect(reveal).toHaveAttribute("data-open", "false");
    await expect(page).toHaveURL(/\/$/);

    // The scroll of the page behind is untouched — no lock, no scrim — while the panel
    // keeps its own scroller, so a long team tree is reachable inside a peek.
    await graze();
    await expect(reveal).toHaveAttribute("data-open", "true");
    await expect(reveal).toHaveCSS("overflow-y", "auto");
    expect(await page.evaluate(() => document.body.style.overflow)).toBe("");

    // --- hidden: no edge at all, and still a way in ---------------------------

    await setMode("hidden");
    await page.reload();
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();

    // The whole difference between the two quiet modes: the left edge of the window is
    // not live. A mode that reveals on a graze is not hidden.
    await expect(hotZone).toHaveCount(0);
    await expect(reveal).toHaveAttribute("data-open", "false");

    // The button in the top bar is the only way in, and it is a real one — it reveals the
    // same temporary panel rather than changing the mode, because under `hidden` a way in
    // that permanently pins the column is not a way in.
    await trigger.click();
    await expect(reveal).toHaveAttribute("data-open", "true");
    await trigger.click();
    await expect(reveal).toHaveAttribute("data-open", "false");

    // And the way back out of `hidden` is the icon inside the panel, which pins it. One
    // icon, two places, one meaning each: the bar's says "show me the column", the
    // panel's says how the column is anchored.
    await trigger.click();
    await anchor.click();
    await expect(reveal).toHaveCount(0);
    await expect(sidebarRow(page, "All tickets")).toBeVisible();
    await expect(trigger).toHaveCount(0);
  } finally {
    await api.put("/api/me/preferences", { data: { sidebarMode: "pinned" } });
    await api.dispose();
  }
});

/**
 * The bell, which is where the inbox went.
 *
 * Nothing in this suite writes a notification — `20-inbox.spec.ts` says so at length and
 * for the same reason — so what is asserted here is the frame: the pip is *absent* over
 * an empty inbox, the peek says so in a sentence, `Escape` closes it without also leaving
 * the page, and `⤢` hands the reader to the four-tab screen unchanged.
 *
 * The absent pip is not a weaker assertion than a present one. A dot drawn over an inbox
 * with nothing in it is exactly the alarm about nothing `inbox/tabs.tsx` already refuses,
 * and it is the failure mode a count-shaped badge has and a dot does not.
 */
test("scenario 23 — the inbox is a bell in the top bar, and the column no longer offers it", async ({
  browser,
}) => {
  const page = await openAs(browser, ADMIN);

  // Gone from the column. Not `sidebarRow`, which filters on a `button`: every route row
  // is a `Link`, and this is the link that used to be there.
  await expect(page.getByRole("link", { name: "Inbox", exact: true })).toHaveCount(0);

  const bell = page.getByTestId("inbox-bell");
  await expect(bell).toBeVisible();
  await expect(page.getByTestId("inbox-pip")).toHaveCount(0);

  await bell.click();
  const peek = page.getByTestId("inbox-peek");
  await expect(peek).toBeVisible();
  await expect(peek.getByText("Nothing waiting for you")).toBeVisible();
  // The header's write, never disabled: the count it would be gated on is up to a minute
  // stale, and a control that is sometimes dead for reasons the reader cannot see is
  // worse than one whose click occasionally changes nothing.
  await expect(peek.getByRole("button", { name: "Mark all read" })).toBeEnabled();
  await page.keyboard.press("Escape");
  await expect(peek).toHaveCount(0);

  /*
   * `Escape` closes the peek and leaves the page alone. Radix dismisses on a `document`
   * capture listener and `use-shell-keys` answers the same key on `window` with "close
   * what is open, then *leave*" — and it cannot see this popover, since it is not one of
   * `useUi`'s overlays. Without the stop in `bell.tsx` one press would do both, which is
   * only visible somewhere there is a page to be sent back from.
   */
  await page.getByRole("link", { name: "Workload", exact: true }).click();
  await expect(page.getByTestId("breadcrumb-crumb").last()).toHaveText("Workload");
  // The bell is on every route, which is the point of moving it into the bar: an unread
  // count is true of the session rather than of a place.
  await expect(bell).toBeVisible();
  await bell.click();
  await expect(peek).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(peek).toHaveCount(0);
  await expect(page.getByTestId("breadcrumb-crumb").last()).toHaveText("Workload");

  // `⤢` lands on the full screen, whose four tabs are exactly as they were.
  await bell.click();
  await page.getByTestId("inbox-peek-expand").click();
  await expect(page).toHaveURL(/\/inbox$/);
  await expect(page.getByTestId("inbox-tab")).toHaveCount(4);

  // And no row in the column claims the route, because there is no row for it — the
  // selection rule is unchanged, there is simply nothing left for it to light.
  await expect(currentRows(page)).toHaveCount(0);
  await expect(sidebarRow(page, "All tickets")).toBeVisible();
});
