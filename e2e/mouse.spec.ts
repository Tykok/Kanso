import { expect, test, type APIRequestContext, type Browser, type Page } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  WEB_URL,
  apiAs,
  openAs,
  openRowMenu,
  seedInstance,
  seedProject,
  seedTeam,
  seedTicket,
  ticketRow,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Like `openAs`, but the identity is written once via `evaluate` after the first
 * navigation rather than through `context.addInitScript`. `openAs` uses the
 * script deliberately because it replays on every navigation — useful whenever a
 * test's own reload should keep acting as the same person. That is exactly wrong
 * for scenario 10's last step: sign-out ends in a real, client-triggered full
 * navigation, and a script re-asserting the old identity on every navigation
 * would make the sign-in screen unreachable no matter what the application does
 * — the client would clear `kanso.devUser`, and the harness would put it right
 * back before the page's own code ever ran. A genuinely signed-in browser has no
 * such script; this reproduces that, so the reload after "Sign out" is read
 * honestly.
 */
async function openOnceAs(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: WEB_URL });
  const page = await context.newPage();
  await page.goto("/");
  await page.evaluate((who) => window.localStorage.setItem("kanso.devUser", who), email);
  await page.reload();
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  return page;
}

/**
 * A ticket's server-side truth, read fresh rather than trusted from a prior
 * response. Every "did the other row move" assertion in scenario 11 goes through
 * this, not through what the DOM happens to show.
 */
async function fetchTicket(
  api: APIRequestContext,
  id: string,
): Promise<{
  title: string;
  status: string;
  priority: string;
  archived: boolean;
  teamId: string;
  projectId?: string;
} | null> {
  const response = await api.get(`/api/tickets/${id}`);
  if (!response.ok()) return null;
  return response.json();
}

/**
 * Scenario 9. The New menu, exercised from every scope it changes shape in.
 *
 * Covers task 4 end to end: the exact three-item list for someone who may configure
 * teams (the bug that shipped was two Project entries and two Team entries), the
 * absence of the Team entry for a member at every scope, the pre-fill `creationSeed`
 * promises from a team and from a project scope, and the one path with nothing to
 * inherit — "All tickets" — which must block rather than guess a team.
 */
test("scenario 9 — the New menu creates into the scope you are standing in", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Core"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Refonte"), teamId: team.id });
  await api.dispose();

  const page = await openAs(browser, ADMIN);
  const newTrigger = page.getByRole("button", { name: "New", exact: true });
  const newMenu = page.getByRole("menu", { name: "New" });

  // The trigger reads "New", not "⋯" — the shared menu's default overridden here.
  await expect(newTrigger).toHaveText("New");

  // On a team scope, an admin sees exactly one Project entry and one Team entry.
  // The shipped bug was two of each; this is the regression guard for it.
  await page.getByRole("button", { name: team.name, exact: true }).click();
  await newTrigger.click();
  await expect(newMenu.getByRole("menuitem")).toHaveText(["New ticket", "New project", "New team"]);

  // "New team" on the team's own scope pre-fills that team as the parent — the
  // dialog's title says so too, which is where the context reappears now that the
  // dedicated "New sub-team" action is gone.
  await newMenu.getByRole("menuitem", { name: "New team", exact: true }).click();
  const teamDialog = page.getByRole("dialog", { name: `New team under ${team.name}` });
  await expect(teamDialog).toBeVisible();
  await expect(teamDialog.getByLabel("Parent team")).toHaveValue(team.id);
  await teamDialog.getByRole("button", { name: "Cancel" }).click();
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // Standing on the project instead: "New project" pre-fills the project's own
  // team, and "New team" pre-fills that same team as the parent — not the project
  // itself, which has no place in a team hierarchy.
  await page.getByRole("button", { name: project.name, exact: true }).click();
  await newTrigger.click();
  await newMenu.getByRole("menuitem", { name: "New project", exact: true }).click();
  const projectDialog = page.getByRole("dialog", { name: "New project" });
  await expect(projectDialog).toBeVisible();
  await expect(projectDialog.getByLabel("Team")).toHaveValue(team.id);
  await projectDialog.getByRole("button", { name: "Cancel" }).click();

  await newTrigger.click();
  await newMenu.getByRole("menuitem", { name: "New team", exact: true }).click();
  const childDialog = page.getByRole("dialog", { name: `New team under ${team.name}` });
  await expect(childDialog).toBeVisible();
  await expect(childDialog.getByLabel("Parent team")).toHaveValue(team.id);
  await childDialog.getByRole("button", { name: "Cancel" }).click();

  // The actual creation: a ticket filed from the project scope carries both ids —
  // checked against the API, not merely that a row appeared in the list.
  await newTrigger.click();
  await newMenu.getByRole("menuitem", { name: "New ticket", exact: true }).click();
  const title = page.getByPlaceholder("New ticket…");
  await expect(title).toBeFocused();
  const ticketTitle = unique("Ticket from the scope");
  await title.fill(ticketTitle);
  await title.press("Enter");
  await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);
  await expect(ticketRow(page, ticketTitle)).toBeVisible();

  const verify = await apiAs(ADMIN);
  const inProject = (await (await verify.get(`/api/tickets?projectId=${project.id}`)).json()) as {
    title: string;
    teamId: string;
    projectId?: string;
  }[];
  const created = inProject.find((t) => t.title === ticketTitle);
  expect(created, "the created ticket was not returned by the project it was filed into").toBeTruthy();
  expect(created?.teamId).toBe(team.id);
  expect(created?.projectId).toBe(project.id);

  // From "All tickets" there is no scope to inherit from: the composer opens
  // blocked, points at the team selector, and files nothing.
  await page.getByRole("button", { name: "All tickets", exact: true }).click();
  await newTrigger.click();
  await newMenu.getByRole("menuitem", { name: "New ticket", exact: true }).click();
  const blockedTitleInput = page.getByPlaceholder("New ticket…");
  await expect(blockedTitleInput).toBeFocused();
  const teamSelect = page.getByLabel("Ticket team");
  await expect(teamSelect).toHaveValue("");
  const blockedTitle = unique("Should never be filed");
  await blockedTitleInput.fill(blockedTitle);
  await blockedTitleInput.press("Enter");
  // Still open, not filed: the attempt landed on the selector, not on the API.
  await expect(blockedTitleInput).toBeVisible();
  await expect(teamSelect).toBeFocused();
  await expect(page.getByText("Pick a team first.")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);

  const everything = (await (await verify.get(`/api/tickets?limit=500`)).json()) as { title: string }[];
  expect(everything.some((t) => t.title === blockedTitle)).toBe(false);
  await verify.dispose();

  // A member never sees the Team entry, at any scope — the shape of the
  // organisation is an admin decision, everywhere the New menu can be opened from.
  const member = await openAs(browser, MEMBER);
  const memberNewTrigger = member.getByRole("button", { name: "New", exact: true });
  const memberNewMenu = member.getByRole("menu", { name: "New" });

  for (const scopeButton of ["All tickets", team.name, project.name]) {
    await member.getByRole("button", { name: scopeButton, exact: true }).click();
    await memberNewTrigger.click();
    await expect(memberNewMenu.getByRole("menuitem")).toHaveText(["New ticket", "New project"]);
    await member.keyboard.press("Escape");
  }
});

/**
 * Scenario 10. The account menu behind the Kanso brand: task 5, never opened by
 * anything but a human eye and `tsc` until now.
 */
test("scenario 10 — the brand menu names who you are, and signs you out", async ({ browser }) => {
  const page = await openOnceAs(browser, ADMIN);

  // The brand text itself is the trigger — there is no ellipsis in the sidebar
  // header, unlike every row's own `⋯`.
  const trigger = page.locator(".brand .menu-trigger");
  await expect(trigger).not.toContainText("⋯");
  await page.getByText("Kanso", { exact: true }).click();

  const menu = page.getByRole("menu", { name: "Account and settings" });
  await expect(menu).toBeVisible();

  // Who you are, in the header — not a menu item, decoration inside role="menu".
  const header = menu.locator(".menu-header");
  await expect(header).toContainText("E2E owner");
  await expect(header).toContainText("owner@kanso.test");
  await expect(header).toContainText("owner");

  // The exact list: an item leaking in or out fails this rather than a "contains".
  await expect(menu.getByRole("menuitem")).toHaveText([
    "Settings",
    "Keyboard shortcuts",
    "Command palette",
    "Sign out",
  ]);

  // Neither the header nor the footer holds anything focusable, so neither is
  // reachable by Tab and neither can ever be the target of an arrow key.
  await expect(
    header.locator("button, a, input, select, textarea, [tabindex]"),
  ).toHaveCount(0);
  const footer = menu.locator(".menu-footer");
  await expect(footer).toBeVisible();
  await expect(
    footer.locator("button, a, input, select, textarea, [tabindex]"),
  ).toHaveCount(0);

  // The arrow-key rotation only ever touches the four real items: a full cycle of
  // four presses lands back on the first one, which could not happen if the header
  // or footer were silently counted as stops along the way.
  const settingsItem = menu.getByRole("menuitem", { name: "Settings" });
  await expect(settingsItem).toBeFocused();
  for (let i = 0; i < 4; i++) await page.keyboard.press("ArrowDown");
  await expect(settingsItem).toBeFocused();

  // Settings opens the panel.
  await settingsItem.click();
  await expect(page.locator(".panel-header").filter({ hasText: "Settings" })).toBeVisible();
  await page.getByRole("button", { name: "Close" }).click();
  await expect(page.locator(".panel-header").filter({ hasText: "Settings" })).toHaveCount(0);

  // Sign out drops the identity this browser was asserting. Under
  // `KANSO_AUTH_MODE=dev` the API never actually answers "unauthenticated" —
  // `DevAuthenticationFilter` authenticates every request as somebody, falling
  // back to a default identity when no header is attached — so the generic
  // sign-in screen is not what a fixed "Sign out" produces here; a session
  // cookie is what earns that screen under `oidc`, which this stack is not
  // running. What dev mode *can* prove, and what actually distinguishes the fix
  // from the defect, is that the old identity is gone: `kanso.devUser` is
  // cleared client-side, and the very next request lands as a different,
  // never-onboarded person, who the app sends to first-run setup rather than
  // straight back to the ticket list as "E2E owner".
  await page.getByText("Kanso", { exact: true }).click();
  await page.getByRole("menuitem", { name: "Sign out" }).click();
  await expect
    .poll(() => page.evaluate(() => window.localStorage.getItem("kanso.devUser")))
    .toBeNull();
  await expect(page.getByRole("heading", { level: 1, name: "Preferences" })).toBeVisible();
  await expect(page.getByText("E2E owner")).toHaveCount(0);
});

/**
 * Scenario 11. A ticket row, worked entirely by mouse: task 6's pills and task 7's
 * `⋯`, on two rows at once so a click on one is provably a click on that one alone.
 */
test("scenario 11 — a ticket row changes status, priority, name and existence by mouse", async ({
  browser,
}) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Rowing"), key: uniqueKey() });
  const ticketA = await seedTicket(api, { teamId: team.id, title: unique("Row A stays put") });
  const ticketB = await seedTicket(api, { teamId: team.id, title: unique("Row B takes the click") });
  const initialA = await fetchTicket(api, ticketA.id);

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();

  const rowA = ticketRow(page, ticketA.title);
  const rowB = ticketRow(page, ticketB.title);

  // A is the selected row for the entire test: everything below happens on B.
  await rowA.click();
  await expect(rowA).toHaveAttribute("data-selected", "true");
  await expect(rowB).toHaveAttribute("data-selected", "false");

  // The pill's trigger must have a real hit box: the shipped defect rendered it at
  // 0×0, passing every check that never opened a browser.
  const statusTrigger = rowB.getByRole("button", { name: /^Status: / });
  const statusBox = await statusTrigger.boundingBox();
  expect(statusBox?.width).toBeGreaterThan(0);
  expect(statusBox?.height).toBeGreaterThan(0);

  await statusTrigger.click();
  const statusMenu = page.getByRole("menu", { name: /^Status: / });
  // `patchTicket` is optimistic (see `use-action-ctx.ts`): the row updates before
  // the request resolves, so the API check below has to wait for the request
  // itself rather than race it.
  await Promise.all([
    page.waitForResponse(
      (res) => res.url().endsWith(`/api/tickets/${ticketB.id}`) && res.request().method() === "PATCH",
    ),
    statusMenu.getByRole("menuitem", { name: "Set status: In progress" }).click(),
  ]);

  const afterStatusB = await fetchTicket(api, ticketB.id);
  const afterStatusA = await fetchTicket(api, ticketA.id);
  expect(afterStatusB?.status).toBe("in_progress");
  expect(afterStatusA?.status).toBe(initialA?.status);
  // Row A never left the selection: B's pill did not touch it.
  await expect(rowA).toHaveAttribute("data-selected", "true");

  const priorityTrigger = rowB.getByRole("button", { name: /^Priority: / });
  const priorityBox = await priorityTrigger.boundingBox();
  expect(priorityBox?.width).toBeGreaterThan(0);
  expect(priorityBox?.height).toBeGreaterThan(0);

  await priorityTrigger.click();
  const priorityMenu = page.getByRole("menu", { name: /^Priority: / });
  await Promise.all([
    page.waitForResponse(
      (res) => res.url().endsWith(`/api/tickets/${ticketB.id}`) && res.request().method() === "PATCH",
    ),
    priorityMenu.getByRole("menuitem", { name: "Set priority: High" }).click(),
  ]);

  const afterPriorityB = await fetchTicket(api, ticketB.id);
  const afterPriorityA = await fetchTicket(api, ticketA.id);
  expect(afterPriorityB?.priority).toBe("high");
  expect(afterPriorityA?.priority).toBe(initialA?.priority);
  await expect(rowA).toHaveAttribute("data-selected", "true");

  // The `⋯` stays out of sight until the row earns it — by hover, or by a
  // keyboard user tabbing straight to it without ever touching the mouse.
  const rowActionsA = rowA.getByRole("button", { name: `Actions for ${ticketA.identifier}` });
  await expect(rowActionsA).toHaveCSS("opacity", "0");
  await rowA.hover();
  await expect(rowActionsA).toHaveCSS("opacity", "1");
  await rowA.getByRole("button", { name: /^Status: / }).focus();
  await page.keyboard.press("Tab");
  await expect(rowActionsA).toBeFocused();
  await expect(rowActionsA).toHaveCSS("opacity", "1");

  // Double-clicking the `⋯` or a pill must never fall through to opening the
  // ticket underneath — `dblclick` bubbles independently of the click the menu's
  // trigger already stops.
  const rowActionsB = rowB.getByRole("button", { name: `Actions for ${ticketB.identifier}` });
  const detailFor = (t: string) => page.locator(".panel-header").filter({ hasText: t });
  await rowB.hover();
  await rowActionsB.dblclick();
  await expect(detailFor(ticketB.title)).toHaveCount(0);
  await statusTrigger.dblclick();
  await expect(detailFor(ticketB.title)).toHaveCount(0);

  // Rename, archive and delete, all run from row B while it is only hovered — row
  // A stays selected throughout, proving the row menu acts on its own row and not
  // on whichever one the keyboard cursor happens to be sitting on.
  await rowB.hover();
  const renameMenu = await openRowMenu(page, ticketB.identifier);
  await renameMenu.getByRole("menuitem", { name: "Rename ticket" }).click();
  const editor = page.locator(".row-title-input");
  await expect(editor).toBeFocused();
  const renamed = unique("Row B, renamed by mouse");
  await editor.fill(renamed);
  await Promise.all([
    page.waitForResponse(
      (res) => res.url().endsWith(`/api/tickets/${ticketB.id}`) && res.request().method() === "PATCH",
    ),
    editor.press("Enter"),
  ]);
  const renamedRow = ticketRow(page, renamed);
  await expect(renamedRow).toBeVisible();

  const afterRenameB = await fetchTicket(api, ticketB.id);
  const afterRenameA = await fetchTicket(api, ticketA.id);
  expect(afterRenameB?.title).toBe(renamed);
  expect(afterRenameA?.title).toBe(initialA?.title);
  await expect(rowA).toHaveAttribute("data-selected", "true");

  await renamedRow.hover();
  const archiveMenu = await openRowMenu(page, ticketB.identifier);
  await Promise.all([
    page.waitForResponse(
      (res) => res.url().endsWith(`/api/tickets/${ticketB.id}`) && res.request().method() === "PATCH",
    ),
    archiveMenu.getByRole("menuitem", { name: "Archive / unarchive ticket" }).click(),
  ]);
  // Archived tickets are hidden by default: the row leaves the list on its own.
  await expect(renamedRow).toHaveCount(0);

  const afterArchiveB = await fetchTicket(api, ticketB.id);
  const afterArchiveA = await fetchTicket(api, ticketA.id);
  expect(afterArchiveB?.archived).toBe(true);
  expect(afterArchiveA?.archived).toBe(false);

  const showArchived = page.getByRole("button", { name: "Show archived" });
  await showArchived.click();
  await expect(renamedRow).toBeVisible();

  await renamedRow.hover();
  const deleteMenu = await openRowMenu(page, ticketB.identifier);
  await Promise.all([
    page.waitForResponse(
      (res) => res.url().endsWith(`/api/tickets/${ticketB.id}`) && res.request().method() === "DELETE",
    ),
    deleteMenu.getByRole("menuitem", { name: "Delete ticket" }).click(),
  ]);
  await expect(renamedRow).toHaveCount(0);

  const deletedResponse = await api.get(`/api/tickets/${ticketB.id}`);
  expect(deletedResponse.status()).toBe(404);
  const stillA = await fetchTicket(api, ticketA.id);
  expect(stillA).toBeTruthy();

  await api.dispose();
});
