import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedMember,
  seedTeam,
  seedTicket,
  ticketRow,
  unique,
  uniqueKey,
  userIdOf,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 14 — the dropdown's keyboard, written down before Radix replaces it.
 *
 * Same intent as scenario 5 and the same rule: every assertion describes what the
 * menu does *today*. Several of these behaviours are things Radix does not provide
 * for free, and each one was a deliberate decision in menu.tsx — the arrow keys not
 * leaking to the window listener, the focus target that survives its own popover
 * unmounting. (Not, as an earlier draft of this comment assumed, "the ⋯ that does not
 * exist at all without permissions" — the second test below found that this instance
 * of it is not actually reachable today; see its own docstring.)
 */
test("scenario 14 — the row menu's keyboard survives the move to Radix", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Menus"), key: uniqueKey() });
  const first = unique("Alpha ticket");
  const second = unique("Beta ticket");
  await seedTicket(api, { teamId: team.id, title: first });
  await seedTicket(api, { teamId: team.id, title: second });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();

  const selected = page.locator('[data-testid="ticket-row"][data-selected="true"]');
  // The list orders most-recently-updated first, so `second` is on top.
  await ticketRow(page, second).click();
  await expect(selected).toContainText(second);

  const trigger = ticketRow(page, second).getByRole("button", { name: /^Actions for/ });

  // Invariant 5 — clicking the trigger must not let the row underneath change the
  // scope. The selection stays where it was.
  await trigger.click();
  const menu = page.getByRole("menu");
  await expect(menu).toBeVisible();
  await expect(selected).toContainText(second);

  // Invariant 1 — arrows walk the menu and do NOT reach the shell's window handler.
  // Without the popover's stopPropagation, each press would also move the list cursor off
  // `second`. The handler moved out of page.tsx and into `use-shell-keys.ts` with §6.2,
  // which changes nothing here: it is still one `window` listener, and the arrows are
  // still `ticket.moveDown` / `ticket.moveUp` in the shared bucket.
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("ArrowDown");
  await expect(selected).toContainText(second);
  await expect(menu.getByRole("menuitem").nth(2)).toBeFocused();

  // Home and End reach the ends of the list.
  await page.keyboard.press("End");
  await expect(menu.getByRole("menuitem").last()).toBeFocused();
  await page.keyboard.press("Home");
  await expect(menu.getByRole("menuitem").first()).toBeFocused();

  // Invariant 7 — Escape closes the menu, once, and hands focus back to the trigger.
  // It must not also close anything behind it.
  await page.keyboard.press("Escape");
  await expect(menu).toHaveCount(0);
  await expect(trigger).toBeFocused();

  // Invariant 2 — Tab out of an open menu lands exactly where Tab from the trigger
  // would have landed with no menu open at all. That is the real invariant
  // (menu.tsx:136-155's own comment): remove the trigger-refocus-before-Tab trick and
  // the browser resets `document.activeElement` to `<body>` before resolving Tab's
  // default action, which then computes "next focusable from the top of the
  // document" — NOT the same as landing on `<body>` itself. A bare `not.toBe("BODY")`
  // would very likely still pass in that broken state, since "top of the document"
  // usually resolves to *some* focusable node, just the wrong one — a fake gate on
  // exactly the invariant Task 5 is most likely to break. Comparing against a
  // no-menu baseline catches that: it fails loudly whether the wrong landing is
  // `<body>` or any other node, and it is structure-agnostic, so it survives Radix
  // portalling the popover to `document.body`.
  const focusSignature = () =>
    page.evaluate(() => {
      const el = document.activeElement as HTMLElement | null;
      if (!el || el === document.body) return "BODY";
      return `${el.tagName}|${el.getAttribute("aria-label") ?? ""}|${(el.textContent ?? "").trim()}`;
    });

  // Baseline: Tab from the trigger with no menu open at all.
  await expect(trigger).toBeFocused();
  await page.keyboard.press("Tab");
  const baseline = await focusSignature();
  // Undo the Tab so the trigger is back in its starting state for a fair comparison.
  await page.keyboard.press("Shift+Tab");
  await expect(trigger).toBeFocused();

  await trigger.click();
  await expect(page.getByRole("menu")).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByRole("menu")).toHaveCount(0);
  const withMenuOpen = await focusSignature();
  expect(withMenuOpen).toBe(baseline);

  // The `⋯` menu's own hints are read off the effective bindings since §6.3
  // (`useMenuItems`), so this menu prints whatever `e`, `x` and delete are currently bound
  // to rather than what the registry shipped. Nothing here asserts the strings: the unit
  // suite owns that claim (`lib/shortcuts.test.ts`), and pinning them here would make
  // every remap a two-file edit.
  //
  // Invariant 3 — an entry that opens a dialog leaves a focus-return target that
  // still exists once the popover is gone. The ticket row's own "Rename" is NOT the
  // right exhibit for this, even though it looked like the obvious one: it opens the
  // *inline* title editor (tickets.tsx's TitleEditor), which has no focus-restore
  // wiring of its own, and Escape there drops focus straight to <body> — checked
  // below to record the contrast rather than silently drop it. The mechanism this
  // invariant is actually about — menu.tsx refocusing the trigger *before* running
  // the action (menu.tsx:174-182) — only pays off for a `DialogFrame` dialog
  // (dialogs/field.tsx): its lazy `useState` initialiser captures
  // `document.activeElement` before the new dialog's own `autoFocus` can steal it,
  // then hands focus back on unmount (field.tsx:105-111). None of the ticket row's
  // three actions open one; "Rename team", on the team's own row, does.
  //
  // This used to prime a cache entry first, by toggling `Show archived` and waiting for
  // the `/api/teams?includeArchived=true` it fired: `TeamDialog` queries `keys.teams(true)`
  // — a *different* key than the sidebar's `keys.teams(false)` — so on a cold cache it drew
  // a "Loading…" `DialogFrame`, whose unmount raced the real dialog's mount and left the
  // focus-return target pointing at the placeholder. `providers.tsx` now holds that exact
  // key from the first render, for the realtime subscription's team tree, so the entry is
  // warm before this test does anything and there is nothing left to prime — which also
  // means the toggle no longer fetches, and the wait that was the priming's proof hung for
  // the whole 45s timeout. Deleted rather than made to wait for nothing: if the placeholder
  // race comes back it belongs in a test of `team-dialog.tsx`'s loading branch, named as
  // such, and not in a scenario about the menu's keyboard.
  const teamTrigger = page.getByRole("button", { name: `Actions for ${team.name}`, exact: true });
  await teamTrigger.click();
  await page.getByRole("menuitem", { name: /Rename team/ }).click();
  await expect(page.getByRole("textbox", { name: "Name" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
  await expect(teamTrigger).toBeFocused();

  // The contrast promised above: the ticket row's "Rename" really does drop focus to
  // <body> once its inline editor closes. Recorded so Task 5 does not read the
  // invariant as "every menu entry that opens something must restore focus" and
  // change this — the inline editor's lack of a restore path is an existing,
  // unrelated fact about tickets.tsx, not something this menu rewrite owns.
  await trigger.click();
  await page.getByRole("menuitem", { name: /Rename/ }).click();
  await expect(page.getByTestId("row-title-input")).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByTestId("row-title-input")).toHaveCount(0);
  const afterInlineEdit = await page.evaluate(() => document.activeElement?.tagName ?? "NONE");
  expect(afterInlineEdit).toBe("BODY");

  // One press, one thing. Enter on a trigger opens that trigger's menu and nothing else:
  // the shell's window listener holds Enter as `ticket.open`, and a trigger that lets the
  // key through would open its menu *and* the selected ticket's panel behind it. Written
  // when the move to Radix made that reachable — the hand-written trigger opened on the
  // activation click, which `page.tsx` cancelled with its own `preventDefault()`, so the
  // menu simply never opened; Radix opens from its own keydown handler, and only the
  // trigger's `stopPropagation` keeps the count at one. The status pill is the exhibit
  // because it is the trigger that sits in the row Enter would act on.
  const statusTrigger = ticketRow(page, second).getByRole("button", { name: /^Status: / });
  await statusTrigger.focus();
  await page.keyboard.press("Enter");
  await expect(page.getByRole("menu", { name: /^Status: / })).toBeVisible();
  // `.panel-header` moved to `data-testid` with task 7's restyle.
  await expect(page.getByTestId("panel-header")).toHaveCount(0);
  await page.keyboard.press("Escape");
  await expect(page.getByRole("menu")).toHaveCount(0);

  // Invariant 6 is NOT asserted here, on purpose. Only brand-menu.tsx passes a
  // `header`; a row's ⋯ has none, so there would be nothing on this menu to check.
  // It is already covered where the header actually exists — mouse.spec.ts asserts
  // the `menu-header` and `menu-footer` testids are absent from inside the
  // role="menu" element, which is exactly the invariant.

  await page.close();
});

/**
 * Invariant 4, corrected: the brief's premise — "a plain member has no action on a
 * team row, so there is no ⋯ to find" — does not hold today, and there is prior,
 * explicit authority saying so in this repository: permissions.spec.ts:17-32 records
 * a "ruling from the human partner" against exactly that assumption, because
 * `project.create`'s `when` (actions.ts:446) is deliberately ungated on role — every
 * member can create a project, so a team row's `⋯` always has at least that one entry.
 * Checked across all six `Menu` call sites (row, sidebar team/project rows, the top
 * bar's `New`, the brand menu, the status/priority pills): every one of them includes
 * at least one `when: () => true` action or an action with no role check at all, so
 * `menu.tsx:79`'s empty-array branch, while real and still worth keeping, is not
 * reachable through any permission combination the live app can put someone in. This
 * test therefore asserts what IS true and load-bearing for Task 5 — the trigger shows,
 * and its item list is exactly the one entry a member is entitled to, matching
 * permissions.spec.ts's own assertion — rather than the empty case the brief expected.
 */
test("scenario 14 — a member's team menu shows, holding only the one action open to them", async ({
  browser,
}) => {
  const admin = await apiAs(ADMIN);
  const team = await seedTeam(admin, { name: unique("NoActions"), key: uniqueKey() });
  await seedMember(admin, team.id, await userIdOf(MEMBER));
  await admin.dispose();

  const page = await openAs(browser, MEMBER);
  const row = page.getByTestId("nav-item").filter({
    has: page.getByRole("button", { name: team.name, exact: true }),
  });
  await expect(row).toBeVisible();
  const trigger = row.getByRole("button", { name: /^Actions for/ });
  await expect(trigger).toHaveCount(1);
  await trigger.click();
  await expect(page.getByRole("menu")).toBeVisible();
  // Two entries now, not one: `favourite.toggle` is a write a member is entitled to — it
  // changes their own column and nothing about the organisation — so it joined the row
  // menu with the Favourites group. The keycap is part of the text on purpose;
  // `menu.tsx` puts a real space between label and hint so the accessible name and
  // `textContent` both read "Favourite s". The list is still asserted whole, which is
  // what would catch a team-management action leaking in beside it.
  await expect(page.getByRole("menuitem")).toHaveText(["Favourite s", "New project"]);

  await page.close();
});
