import { expect, test } from "@playwright/test";
import { ADMIN, openAs, seedInstance, sidebarRow } from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 20 — the inbox, and a write that survives a reload.
 *
 * Two halves, and the second is the one worth having. The first walks screen 14: the
 * sidebar row is live, the four tabs carry counts, and an inbox with nothing in it says
 * so rather than drawing an empty list.
 *
 * The second is screen 15's offline queue, which the branch's own README calls the
 * hardest thing in the slice with no server component. The unit tests prove the queue's
 * ordering and its survival across an instance being thrown away — but they prove it
 * over a `Map`, and the claim the interface actually makes is that the writes are on
 * *disk*. Only a browser can be asked whether that is true, and only by reloading it.
 * So: abort the request, watch the write land in the banner, reload the page, and expect
 * it to still be there. Nothing else in this suite can catch an IndexedDB store that
 * silently never opened.
 *
 * The inbox is empty in this scenario and stays empty, because nothing writes a
 * notification yet: the services that would — assignment, a status move, the poller's
 * conflict, a comment's mentions — belong to files this branch may not edit, and the
 * call sites are reported instead. `Mark all read` is used as the write to queue
 * precisely because it needs no seeded row.
 */
test("scenario 20 — the inbox answers, and an unsent write outlives a reload", async ({
  browser,
}) => {
  const page = await openAs(browser, ADMIN);

  /*
   * The bell in the top bar, which is how this screen is reached now. §4 took the row out
   * of the column on the grounds that an unread count is true of the session rather than
   * of a place; `23-navigation.spec.ts` is where the bell itself is under test, and this
   * is only the way in.
   */
  await page.getByTestId("inbox-bell").click();
  await page.getByTestId("inbox-peek-expand").click();
  await expect(page).toHaveURL(/\/inbox$/);

  // The column is still beside it, and it is the shell's one copy rather than a second
  // one this page drew for itself. No row is lit, because there is no longer a row for
  // this route — and `All tickets` in particular is dark, which used to be the other half
  // of the two-rows complaint on the route it was easiest to see on.
  await expect(sidebarRow(page, "All tickets")).toBeVisible();
  await expect(
    page.locator('[data-testid="nav-item"][data-current="true"]:not([data-favourite="true"])'),
  ).toHaveCount(0);

  /**
   * The four tabs, each with its own count. `All` is selected on arrival.
   *
   * `aria-pressed`, not `aria-selected`, and the difference is a decision `inbox/tabs.tsx`
   * argues in full: `role="tab"` owes an `aria-controls` pointing at a `tabpanel`, and the
   * panel is the row list in `app/inbox/page.tsx`, which has no id to give. So the strip is
   * four honest toggle buttons in a `group` rather than a `tablist` whose tabs control
   * nothing — and this assertion had not followed. `aria-selected` on a `button` is
   * ignored by assistive technology, so what it read before was true of nothing.
   */
  const tabs = page.getByTestId("inbox-tab");
  await expect(tabs).toHaveCount(4);
  await expect(tabs.first()).toHaveAttribute("aria-pressed", "true");
  await expect(tabs.nth(3)).toContainText("Failures");

  // Nothing waiting is a sentence, not a blank column.
  await expect(page.getByText("Nothing waiting for you")).toBeVisible();

  // Switching tabs is a client-side change of one query key, not a navigation.
  await tabs.nth(3).click();
  await expect(tabs.nth(3)).toHaveAttribute("aria-pressed", "true");
  await expect(page).toHaveURL(/\/inbox$/);
  await tabs.first().click();

  // --- the queue -----------------------------------------------------------

  // `abort`, not `fulfill` with a 500: the queue holds a write only when nothing
  // answered, and a 5xx is an answer. This is the distinction `isAnswered` draws and
  // the reason an outage does not fill the banner with refusals.
  await page.route("**/api/notifications/read-all", (route) => route.abort("connectionfailed"));

  await expect(page.getByTestId("offline-banner")).toHaveCount(0);
  // `⇧e` — the shift is spelled by the key itself, which is what the action registry
  // does everywhere so nothing has to carry modifier state.
  await page.keyboard.press("Shift+E");

  const banner = page.getByTestId("offline-banner");
  await expect(banner).toBeVisible();
  await expect(banner).toContainText("Offline — you can keep writing");
  await expect(banner).toContainText("1 change queued");

  const queued = banner.getByTestId("queued-write");
  await expect(queued).toHaveCount(1);
  await expect(queued).toContainText("mark everything read");
  // `queued`, not `refused`: nothing answered, so nothing was decided.
  await expect(queued).toContainText("queued");

  // The whole point. A reload throws away the store, the zustand state and the
  // `OfflineQueue` instance; if the write comes back, it came off disk.
  await page.reload();
  await expect(page.getByTestId("offline-banner")).toBeVisible();
  const afterReload = page.getByTestId("queued-write");
  await expect(afterReload).toHaveCount(1);
  await expect(afterReload).toContainText("mark everything read");
  // Still queued after the flush the banner runs on mount, which also failed.
  await expect(afterReload).toContainText("queued");

  // Let the request through and drain it. The banner disappears when the queue does —
  // it is not a permanent strip that says "online" on every screen.
  await page.unroute("**/api/notifications/read-all");
  await page.getByRole("button", { name: "Send now" }).click();
  await expect(page.getByTestId("offline-banner")).toHaveCount(0);

  // And it stays gone across a reload, which is the other half of "on disk": a queue
  // that forgot to delete a sent write would resurrect it here.
  await page.reload();
  await expect(page.getByTestId("offline-banner")).toHaveCount(0);
});
