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
 * Scenario 27 — the sentence a refused arrow puts on screen.
 *
 * `LoopRefusalTest` already holds the words the API produces, and holding them twice
 * would be the cheaper half of this: what a unit test cannot see is that the sentence
 * *arrives*, in a strip that is on screen, with its two ends side by side. The refusal
 * this suite is watching was unreadable for weeks while a green test asserted the
 * message contained both ticket ids — which it did, and that was the defect.
 *
 * So this asserts the message the reader gets, whole and character for character, and
 * then asserts what a string comparison structurally cannot: no UUID in it, and the
 * dismiss button beside the words rather than on top of them.
 */
test("27 — a refused loop names the chain in identifiers, on screen", async ({
  browser,
}, testInfo) => {
  const api = await apiAs(ADMIN);
  const teamName = unique("Loop");
  const team = await seedTeam(api, { name: teamName, key: uniqueKey() });
  const groundwork = await seedTicket(api, {
    teamId: team.id,
    title: unique("Groundwork"),
    start: "2026-08-03",
    due: "2026-08-05",
  });
  const follows = await seedTicket(api, {
    teamId: team.id,
    title: unique("Follows"),
    start: "2026-08-10",
    due: "2026-08-12",
  });

  // The arrow that already exists, drawn through the API on purpose: what is under test
  // is the refusal of the *second* arrow, and building the stage with the gesture would
  // make this test's stage depend on the thing it is about to break.
  const drawn = await api.post(`/api/tickets/${follows.id}/dependencies`, {
    data: { predecessorId: groundwork.id },
  });
  expect(drawn.ok(), `Could not draw the first arrow: ${await drawn.text()}`).toBeTruthy();

  const page = await openAs(browser, ADMIN);
  await page.getByRole("button", { name: teamName, exact: true }).first().click();
  await viewButton(page, "Timeline").click();

  // `d` on Groundwork, asked to wait for Follows — which already waits for Groundwork.
  // The palette is the picker screen 06 covers; here it is only the way in.
  await page.getByRole("button", { name: new RegExp(groundwork.identifier) }).first().click();
  await page.keyboard.press("d");
  await page
    .getByTestId("palette")
    .getByRole("button", { name: new RegExp(`^Wait for ${follows.identifier}`) })
    .click();

  const strip = page.locator(".topbar-error");
  await expect(strip).toBeVisible();

  // The whole sentence, not a fragment of it: `toContainText("close a loop")` passes
  // just as happily on the version that reads `509a2367-… -> bddbebdc-…`.
  const message = strip.locator("span").first();
  await expect(message).toHaveText(
    `That dependency would close a loop: ${groundwork.identifier} -> ${follows.identifier}`,
  );

  // And said another way, because the point is not the wording but the vocabulary. This
  // is the assertion that fails the day somebody makes the walk name its own nodes and
  // the fallback stops being reached only by drafts.
  expect(await message.innerText()).not.toMatch(
    /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/,
  );

  // The strip is one row, and `×` sits after the words rather than over them. `.topbar-error`
  // is a flex row whose message takes `flex: 1`; the two boxes have collided here before,
  // and a sentence nobody can finish reading is the same defect as a sentence nobody can
  // parse. Asserted geometrically because CSS is the only place this can go wrong.
  const words = await message.boundingBox();
  const dismiss = await strip.getByRole("button", { name: "Dismiss this message" }).boundingBox();
  expect(words).not.toBeNull();
  expect(dismiss).not.toBeNull();
  expect(dismiss!.x).toBeGreaterThanOrEqual(words!.x + words!.width - 1);
  expect(dismiss!.y).toBeLessThan(words!.y + words!.height);

  // Kept for the eye, since the whole ticket was about what a person reads.
  await testInfo.attach("the-refusal-on-screen", {
    body: await strip.screenshot(),
    contentType: "image/png",
  });

  // It refused, so it wrote nothing: the arrow the palette offered is still on offer.
  await page.keyboard.press("d");
  await expect(
    page.getByTestId("palette").getByRole("button", { name: new RegExp(`^Wait for ${follows.identifier}`) }),
  ).toBeVisible();
});
