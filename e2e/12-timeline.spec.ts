import { expect, test } from "@playwright/test";
import {
  ADMIN,
  apiAs,
  openAs,
  seedInstance,
  seedTeam,
  seedTicket,
  unique,
  uniqueKey,
  viewButton,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Pixels per calendar day at the `day` zoom, copied from
 * `apps/web/src/lib/timeline-geometry.ts` rather than imported.
 *
 * The suite cannot reach that module: `tsconfig.json` here includes `e2e/**` only, and
 * the file imports `./api` at runtime for `dayValue`, which would drag the whole web
 * client — `fetch` wrapper, `NEXT_PUBLIC_*` and all — into a Playwright worker. Copying
 * one number is the smaller lie. It is asserted below rather than merely used: the drag
 * moves the bar by exactly one column, so a zoom that changed would fail this test
 * rather than quietly turn its arithmetic into a coincidence.
 */
const PX_PER_DAY_AT_DAY_ZOOM = 28;

/** The last six pixels inside a bar's right edge. Deliberately unnamed: see `bar.tsx`. */
const RESIZE_GRIP_INSET = 3;

/**
 * Where the link handle is. It begins one pixel past the bar's right edge and is eleven
 * across, so its middle is five or six beyond the edge. Also unnamed on purpose — it
 * does nothing when pressed, only when dragged, and a named button would promise an
 * activation that does not exist.
 */
const LINK_HANDLE_OFFSET = 5;

/** So an identifier can be dropped into a `RegExp` without its characters read as one. */
const escapeRe = (value: string) => value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * Scenario 12. The scheduling engine and the chart, in the same sentence.
 *
 * Everything before this ran against a stub: the geometry under Vitest, the components
 * under a headless harness answering with fixtures. Nothing had yet shown that a gesture
 * in the browser reaches Kotlin and that what Kotlin decides comes back as pixels. So
 * the two writes are made with the mouse — the arrow is drawn out of a bar's link
 * handle, the predecessor is lengthened by its resize grip — and the answers are read
 * both off the screen and out of the API, which is the only way to tell a chart that
 * moved from a chart that merely repainted.
 *
 * The stage is seeded over HTTP, which is the suite's convention for everything that is
 * not itself under test, and here it is also the only option: no screen in this
 * application gives a ticket a start date except the timeline.
 */
test("scenario 12 — lengthening a ticket pushes the one that depends on it", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Planning"), key: uniqueKey() });

  // Three days, then a chain starting the day after. One day of slack between them:
  // enough that the arrow alone moves nothing, little enough that two columns of
  // lengthening overruns it.
  const groundwork = await seedTicket(api, {
    teamId: team.id,
    title: unique("Groundwork"),
    start: "2026-09-01",
    due: "2026-09-03",
  });
  const follows = await seedTicket(api, {
    teamId: team.id,
    title: unique("Follows on"),
    start: "2026-09-04",
    due: "2026-09-08",
  });
  // Dateless, so the tray has something to hold. On a real board it is most of them.
  const unplanned = await seedTicket(api, { teamId: team.id, title: unique("Not planned yet") });

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: team.name, exact: true }).click();
  await viewButton(page, "Timeline").click();

  // Bars are named `${identifier}: ${title} — ${statusLabel}` and reached by role — no
  // class selector anywhere in this file, which is the debt `follow-ups.md` records
  // against the rest of the suite. Matched by prefix: this scenario never changes either
  // ticket's status, so pinning the suffix would only make the lookup fragile.
  const first = page.getByRole("button", { name: new RegExp(`^${escapeRe(groundwork.identifier)}: `) });
  const second = page.getByRole("button", { name: new RegExp(`^${escapeRe(follows.identifier)}: `) });
  await expect(first).toBeVisible();
  await expect(second).toBeVisible();

  // The tray is the other half of the screen: a ticket with no dates has no column to
  // stand in, so it is a chip rather than a bar. A `<button>`, not an inert `<li>` —
  // pressing one puts the cursor on its ticket.
  const tray = page.getByRole("button", { name: `${unplanned.identifier}: ${unplanned.title}` });
  await expect(tray).toBeVisible();
  await expect(page.getByRole("button", { name: /^Unscheduled · 1$/ })).toBeVisible();

  // Neither depends on anything, so neither has slack and neither is on a critical path.
  await expect(first).toHaveAttribute("data-state", "normal");
  await expect(second).toHaveAttribute("data-state", "normal");

  const before = await second.boundingBox();
  const source = await first.boundingBox();
  const sink = await second.boundingBox();
  expect(before, "the successor's bar has no box").toBeTruthy();

  // --- draw the arrow ---------------------------------------------------------
  //
  // By coordinate, because the handle carries no accessible name. `d` on the keyboard
  // opens the palette on the candidate predecessors; this is the other half of the same
  // gesture, and only a drag performs it.
  await page.mouse.move(
    source!.x + source!.width + LINK_HANDLE_OFFSET,
    source!.y + source!.height / 2,
  );
  await page.mouse.down();
  await page.mouse.move(sink!.x + sink!.width / 2, sink!.y + sink!.height / 2, { steps: 10 });
  const [linked] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tickets/${follows.id}/dependencies`) &&
        response.request().method() === "POST",
    ),
    page.mouse.up(),
  ]);
  expect(linked.status(), "the dependency was refused").toBe(201);

  // The line, named after the two ends it joins — an SVG `<title>` on a focusable path,
  // which is what makes an arrow selectable and therefore erasable without a mouse.
  await expect(
    page.getByRole("button", { name: `${groundwork.identifier} → ${follows.identifier}` }),
  ).toBeVisible();

  /*
   * Nothing moved. The successor still starts after the predecessor ends, so the cascade
   * stopped at it — "slack is respected" is the rule that makes a critical path mean
   * anything, and an arrow that shunted every successor forward on sight would quietly
   * repeal it. This assertion is why `before` is taken before the link rather than after.
   */
  await expect.poll(async () => (await second.boundingBox())!.x).toBe(before!.x);

  // --- lengthen the predecessor -----------------------------------------------
  //
  // Two columns out by the right grip. Sixty pixels rather than fifty-six: `snapDays`
  // truncates, so landing exactly on the boundary is one rounding error away from one
  // column, and four pixels of margin costs nothing.
  const grip = await first.boundingBox();
  const gripY = grip!.y + grip!.height / 2;
  const gripX = grip!.x + grip!.width - RESIZE_GRIP_INSET;
  await page.mouse.move(gripX, gripY);
  await page.mouse.down();
  // Past the 4px threshold that separates a drag from a click, in steps, because the
  // gesture is decided by the moves and not by where the pointer ends up.
  await page.mouse.move(gripX + 2 * PX_PER_DAY_AT_DAY_ZOOM + 4, gripY, { steps: 10 });
  const [patched] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tickets/${groundwork.id}`) &&
        response.request().method() === "PATCH",
    ),
    page.mouse.up(),
  ]);
  expect(patched.ok(), "the resize was refused").toBeTruthy();

  /*
   * The successor slid by exactly one column: it now starts where the predecessor ends,
   * which is where the cascade puts it and no further. `toBe` rather than "greater than"
   * — a browser that pushed it two days would be a browser scheduling on its own, which
   * is the one thing this plan decided it must never do.
   */
  await expect
    .poll(async () => (await second.boundingBox())!.x)
    .toBe(before!.x + PX_PER_DAY_AT_DAY_ZOOM);

  // And the chain went red at both ends. Slack is zero for the whole of it now: the
  // successor is anchored on the end of the chain, and the predecessor ends on the day
  // the successor starts.
  await expect(first).toHaveAttribute("data-state", "critical");
  await expect(second).toHaveAttribute("data-state", "critical");

  /*
   * The server agrees, which is the half of this that a stubbed harness could not have
   * said. The bar moved because Kotlin moved the ticket, not because TypeScript decided
   * where a successor ought to go.
   */
  const moved = (await (await api.get(`/api/tickets/${follows.id}`)).json()) as {
    start: { at: string; hasTime: boolean };
    due: { at: string; hasTime: boolean };
  };
  expect(moved.start.at.slice(0, 10)).toBe("2026-09-05");
  expect(moved.due.at.slice(0, 10)).toBe("2026-09-09");
  // Still floating on the way back out. A cascade that stamped a time on a day would be
  // the exact bug the whole feature is built around avoiding.
  expect(moved.start.hasTime).toBe(false);
  expect(moved.due.hasTime).toBe(false);

  const lengthened = (await (await api.get(`/api/tickets/${groundwork.id}`)).json()) as {
    start: { at: string };
    due: { at: string };
  };
  expect(lengthened.start.at.slice(0, 10), "the resize moved the start it should not have").toBe(
    "2026-09-01",
  );
  expect(lengthened.due.at.slice(0, 10)).toBe("2026-09-05");

  // --- erase the arrow, without a mouse ---------------------------------------
  //
  // `d` draws one and, until now, only a pointer could erase one: click the line or tab
  // onto it, then Backspace. `D` is the inverse of `d` through the same palette, which is
  // what makes the gesture reachable from the keyboard at all.
  const arrow = page.getByRole("button", {
    name: `${groundwork.identifier} → ${follows.identifier}`,
  });

  // Clicking the bar puts the cursor on its ticket, which is what `D` acts on.
  await second.click();
  await page.keyboard.press("Shift+D");

  // Named after the predecessor, so a graph with several offers a choice between names
  // rather than between identical rows.
  const option = page.getByRole("button", {
    name: `Stop waiting for ${groundwork.identifier}: ${groundwork.title}`,
  });
  await expect(option).toBeVisible();

  const [erased] = await Promise.all([
    page.waitForResponse(
      (response) =>
        response.url().endsWith(`/api/tickets/${follows.id}/dependencies/${groundwork.id}`) &&
        response.request().method() === "DELETE",
    ),
    page.keyboard.press("Enter"),
  ]);
  expect(erased.ok(), "the dependency was not erased").toBeTruthy();

  await expect(arrow).toHaveCount(0);

  /*
   * Nothing moves back. Freeing slack does not pull work earlier — the successor keeps the
   * dates the cascade gave it — and with no edge left neither end has slack to report, so
   * the red goes away while the bars stay where they are.
   */
  await expect(second).toHaveAttribute("data-state", "normal");
  await expect(first).toHaveAttribute("data-state", "normal");
  await expect
    .poll(async () => (await second.boundingBox())!.x)
    .toBe(before!.x + PX_PER_DAY_AT_DAY_ZOOM);

  await api.dispose();
});
