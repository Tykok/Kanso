import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  sidebarRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 16 — the drawer under 720px.
 *
 * The maintainer's ruling stands: below 720px, navigation is a hamburger opening a
 * 288px off-canvas drawer (`task-9-reference.md`'s RULING), not the three-tab bar
 * the newer mobile mockups draw. `Sidebar`'s own copy is still in the document at
 * this width (`shell/sidebar-frame.tsx`'s `contents max-[720px]:hidden` wrapper only
 * hides it) — the drawer, reusing `<Sidebar>` verbatim rather than a second nav tree,
 * is the only way back to it. Nothing exercised any of this until now: 129 unit tests
 * and 16 prior e2e scenarios never opened it once. 390×844 is the mobile mockups' own
 * frame size, not a number invented for this test.
 *
 * The drawer used to be mounted three times, once per shell, and not at all on the four
 * routes that had none — so under 720px `/trash` and `/docs` had no navigation whatever.
 * `shell/topbar.tsx` mounts it once, which the second half of this scenario is about.
 */
test("scenario 16 — the mobile drawer traps focus, reaches a team, and closes by navigation, scrim and Escape", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = unique("Drawer");
  await seedTeam(api, { name: team, key: uniqueKey() });

  const page = await openAs(browser, ADMIN, { viewport: { width: 390, height: 844 } });

  // The desktop copy is still in the document — the sidebar preference hasn't
  // changed, only the breakpoint hides it — so "not visible" is the claim that
  // actually matters: a screen this narrow has no use for it either way.
  await expect(sidebarRow(page, "All tickets")).not.toBeVisible();

  // Opens from the hamburger.
  const trigger = page.getByTestId("mobile-nav-trigger");
  await expect(trigger).toBeVisible();
  await trigger.click();
  const drawer = page.locator("#mobile-nav-drawer");
  await expect(drawer).toBeVisible();

  // `aria-modal="true"` is a promise: focus moves in on open, Tab cannot walk it
  // out into the page underneath, and it comes back to the trigger on close. Radix's
  // `Dialog` primitive keeps that promise (`mobile-nav.tsx`); this is what a
  // hand-rolled `<div role="dialog" aria-modal>` with no supporting behaviour would
  // have failed at three different ways.
  await expect(drawer.locator(":focus")).toHaveCount(1);
  for (let i = 0; i < 25; i++) await page.keyboard.press("Tab");
  await expect(drawer.locator(":focus")).toHaveCount(1);

  // A nav row is reachable inside it. Scoped to `drawer`, not the unscoped
  // `sidebarRow` helper: the desktop copy of the same row is still in the
  // document (hidden, not gone), so an unscoped locator would now match two and
  // trip Playwright's strict mode.
  await drawer.getByRole("button", { name: team, exact: true }).click();
  // Selecting a destination closes the drawer itself — a real `aria-modal` hides
  // everything behind it from assistive technology while it's open (Radix's
  // `hideOthers`), so it cannot stay open once a row has sent you somewhere on the
  // page it was hiding; the heading below would otherwise be unreachable by role,
  // not merely obscured. `sidebar.tsx`'s `onNavigate` is what closes it.
  await expect(page.locator("#mobile-nav-drawer")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: team, level: 1 })).toBeVisible();
  // Closing must not drop a keyboard user on <body> — focus comes back to the
  // control that opened the drawer, the same contract `menu.tsx` and
  // `DialogFrame` already hold for their own overlays.
  await expect(trigger).toBeFocused();

  // Reopens, to prove the scrim and Escape close it too, independently of navigation.
  await trigger.click();
  await expect(drawer).toBeVisible();

  // Closes on a scrim click. The scrim is `inset: 0` — the whole 390px-wide
  // viewport — but the 288px drawer panel paints on top of it for the width it
  // covers, so a click at the element's own centre (Playwright's default) would
  // land on the panel, not the scrim, and correctly get intercepted. Clicking
  // past the panel's right edge is what actually reaches it.
  await page.getByTestId("mobile-nav-scrim").click({ position: { x: 340, y: 100 } });
  await expect(page.locator("#mobile-nav-drawer")).toHaveCount(0);
  await expect(trigger).toBeFocused();

  // Reopens, and closes on Escape.
  await trigger.click();
  await expect(page.locator("#mobile-nav-drawer")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.locator("#mobile-nav-drawer")).toHaveCount(0);
  await expect(trigger).toBeFocused();

  /**
   * And it is there on a destination too, which it was not before.
   *
   * `/trash` rendered no shell at all, so under 720px it had neither the column nor the
   * `☰` that reaches it: the reader who tapped Trash on a phone had no navigation left on
   * the screen and no way back but the browser's own button. The drawer is the shell's
   * now, so every route in the group has it.
   */
  await page.goto("/trash");
  await expect(page.getByRole("heading", { name: "Trash and archives", level: 1 })).toBeVisible();
  const onTrash = page.getByTestId("mobile-nav-trigger");
  await expect(onTrash).toBeVisible();
  await onTrash.click();
  const reopened = page.locator("#mobile-nav-drawer");
  await expect(reopened).toBeVisible();
  await reopened.getByRole("button", { name: team, exact: true }).click();
  // Picking a subject from a destination navigates as well as scoping — a row that
  // changed state without moving is the click this pass exists to fix — so the drawer
  // closes and the list it named is what is behind it.
  await expect(page.locator("#mobile-nav-drawer")).toHaveCount(0);
  await expect(page.getByRole("heading", { name: team, level: 1 })).toBeVisible();
});

/**
 * Scenario 16b — the list on a phone, and the columns it earns back as the window grows.
 *
 * The complaint this pins: at 390px the row was a nine-column grid whose eight fixed
 * columns already came to 586px before the title had a pixel, so every screen in the
 * application scrolled sideways — and what the reader had to scroll *to* was the title,
 * the one column that mattered. `.ticket-grid` in `globals.css` answers with five
 * templates rather than one, and the `.tcol-*` rules beside it drop the cells each
 * template has no column for. The two halves have to agree, and nothing in TypeScript
 * can check that they do; this is what checks it.
 *
 * The widths are the breakpoints themselves, one either side where it matters: 390 (the
 * mockups' own frame), 900, 1100 and 1400. The assertion repeated at all four is the
 * one the maintainer actually asked for — that the page never scrolls left or right.
 */
test("scenario 16b — the ticket list fits a phone, and gains columns as the window widens", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  // Long enough that the old top bar truncated it, which is why the heading moved out of
  // the bar and onto the page.
  const team = unique("Plateforme et integrations partenaires");
  const seeded = await seedTeam(api, { name: team, key: uniqueKey() });
  // A title with no short words in it: two lines at 390px, and the thing that used to
  // push the row past the right edge.
  const title = unique("Reconciliation des ecritures differees entre deux editeurs");
  await seedTicket(api, { teamId: seeded.id, title, due: "2026-11-20" });

  const page = await openAs(browser, ADMIN, { viewport: { width: 390, height: 844 } });
  await page.goto(`/?team=${seeded.id}`);

  // The heading is on the page now, not in the bar, and it is the full name — no
  // ellipsis, no `Plateforme et…`.
  const heading = page.getByRole("heading", { name: team, level: 1 });
  await expect(heading).toBeVisible();
  await expect(heading).toHaveText(team);

  const row = page.getByTestId("ticket-row").filter({ hasText: title });
  await expect(row).toBeVisible();

  /** How far past its own right edge the page runs. Zero, at every width below. */
  const overflow = () =>
    page.evaluate(() => {
      const root = document.documentElement;
      const rows: number[] = [];
      // `Array.from` rather than a spread: the suite's `lib` does not give a `NodeList`
      // an iterator, and `forEach` is the one walk every target agrees it has.
      document
        .querySelectorAll("[data-testid='ticket-row']")
        .forEach((el) => rows.push(el.scrollWidth));
      const widest = Math.max(root.scrollWidth, ...rows);
      return widest - root.clientWidth;
    });

  await expect.poll(overflow, { message: "the list scrolls sideways at 390px" }).toBeLessThanOrEqual(0);

  // Title, points and due, and nothing else. The identifier is on the ticket you tap
  // into; the status is the group label the row is stacked under; the project and the
  // team are the heading above the list.
  await expect(row.getByTestId("row-id")).not.toBeVisible();
  await expect(row.getByTestId("status-pill")).not.toBeVisible();

  // The keyboard strip is not drawn for a device with no keyboard, and the column header
  // is not drawn over three self-evident columns.
  await expect(page.locator(".statusbar")).not.toBeVisible();
  await expect(page.getByText("Title", { exact: true })).not.toBeVisible();

  // One filter field, at the bottom, and it is the one that finds a row. The facet
  // language wants a keyboard and a completion list, so it is not offered here — the
  // chips it produces stay legible at every width.
  await expect(page.getByPlaceholder(/Filter/)).toBeVisible();
  await expect(page.getByTestId("filter-query")).not.toBeVisible();

  // The Filter button is gone on every width, and the view toggle is gone on this one:
  // two of its three positions lead to a board and a chart a phone does not draw.
  await expect(page.getByTestId("view-filter")).toHaveCount(0);
  await expect(page.getByRole("group", { name: "View" })).not.toBeVisible();

  /*
   * The search, reachable without a keyboard.
   *
   * The palette *is* the search, and until the button existed it had two doors — `⌘K`
   * and a row in the brand menu — neither of which a thumb can open. This is the whole
   * of `Kanso - Mobile.dc.html`'s screen 34 becoming reachable on the device it was
   * drawn for.
   */
  const search = page.getByTestId("shell-search");
  await expect(search).toBeVisible();
  await search.click();
  const palette = page.getByTestId("palette");
  await expect(palette).toBeVisible();
  // The keys are not drawn here either — three keycaps nobody can press, on the surface
  // with the least room to spare. The count stays: it is a fact about the search.
  //
  // A testid rather than the words: the strip reads `↑ ↓ move`, with the caps as child
  // elements, so `getByText("move", { exact: true })` matches nothing at any width — it
  // passed here by being absent and would have gone on passing with the strip on screen.
  await expect(page.getByTestId("palette-keys")).not.toBeVisible();
  await expect(page.getByTestId("search-count")).toBeVisible();

  // And it searches: the ticket seeded above is found by a word from its title.
  await page.getByPlaceholder("Type a command…").fill(title.slice(0, 18));
  await expect(palette.getByText(title, { exact: false }).first()).toBeVisible();
  // The `tab` keycap goes with the rest of them once there is something to filter; the
  // segmented strip it duplicates stays, because a thumb can press that.
  await expect(page.getByTestId("palette-tab-key")).not.toBeVisible();
  await expect(page.getByRole("group", { name: "What to search" })).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("palette")).toHaveCount(0);

  // The title wraps rather than being cut off, which is what the 64px row buys. A 36px
  // row would mean it did not.
  const height = await row.evaluate((el) => el.getBoundingClientRect().height);
  expect(height).toBeGreaterThanOrEqual(60);

  // The trail is the same words as the heading below it, so it goes on a narrow window.
  await page.goto("/trash");
  await expect(page.getByRole("heading", { name: "Trash and archives", level: 1 })).toBeVisible();
  await expect(page.getByTestId("breadcrumb-crumb").first()).not.toBeVisible();

  /*
   * And back up the ladder. The column header is what is asserted rather than the cells,
   * because a cell can be legitimately empty — an unmirrored instance draws no sync badge
   * at any width — while the header always has its word to show.
   */
  await page.goto(`/?team=${seeded.id}`);
  await expect(page.getByTestId("ticket-row").filter({ hasText: title })).toBeVisible();

  const header = (label: string) => page.getByText(label, { exact: true });

  await page.setViewportSize({ width: 900, height: 900 });
  await expect(header("ID")).toBeVisible();
  await expect(header("Status")).toBeVisible();
  await expect(header("Project")).not.toBeVisible();
  await expect(header("Sync")).not.toBeVisible();
  await expect(page.getByRole("group", { name: "View" })).toBeVisible();
  await expect.poll(overflow, { message: "the list scrolls sideways at 900px" }).toBeLessThanOrEqual(0);

  await page.setViewportSize({ width: 1100, height: 900 });
  await expect(header("Project")).toBeVisible();
  await expect(header("Sync")).not.toBeVisible();
  await expect.poll(overflow, { message: "the list scrolls sideways at 1100px" }).toBeLessThanOrEqual(0);

  await page.setViewportSize({ width: 1400, height: 900 });
  await expect(header("Sync")).toBeVisible();
  await expect.poll(overflow, { message: "the list scrolls sideways at 1400px" }).toBeLessThanOrEqual(0);

  // The same one button on a desktop window — not a mobile affordance with a twin — and
  // here the keys under the results are worth drawing, because there is a keyboard to
  // press them with.
  await page.getByTestId("shell-search").click();
  await expect(page.getByTestId("palette")).toBeVisible();
  await expect(page.getByTestId("palette-keys")).toBeVisible();
  await page.getByPlaceholder("Type a command…").fill("a");
  await expect(page.getByTestId("palette-tab-key")).toBeVisible();
  await page.keyboard.press("Escape");

  // The full nine columns, and the row still ends where the window does.
  await expect(page.locator(".statusbar")).toBeVisible();
});
