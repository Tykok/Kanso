import { expect, test } from "@playwright/test";
import { ADMIN, apiAs, openAs, seedInstance, seedTeam, sidebarRow, unique, uniqueKey } from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 16 — the drawer under 720px.
 *
 * The maintainer's ruling stands: below 720px, navigation is a hamburger opening a
 * 288px off-canvas drawer (`task-9-reference.md`'s RULING), not the three-tab bar
 * the newer mobile mockups draw. `Sidebar`'s own copy is still in the document at
 * this width (`page.tsx`'s `contents max-[720px]:hidden` wrapper only hides it) —
 * the drawer, reusing `<Sidebar>` verbatim rather than a second nav tree, is the
 * only way back to it. Nothing exercised any of this until now: 129 unit tests and
 * 16 prior e2e scenarios never opened it once. 390×844 is the mobile mockups' own
 * frame size, not a number invented for this test.
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
});
