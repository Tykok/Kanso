import { expect, test } from "@playwright/test";

/**
 * Scenario 15 — the workbench itself.
 *
 * Deliberately thin: this page has no behaviour to test. What it does catch is a
 * token layer that failed to load, which renders as unstyled markup rather than an
 * error, and which every later migration task would then be building against.
 */
test("scenario 15 — the design system page renders its tokens and components", async ({
  page,
}) => {
  await page.goto("/design-system");

  await expect(page.getByRole("heading", { name: "Design system", level: 1 })).toBeVisible();

  // Every button variant is drawn, including the two badge variants Kanso adds.
  //
  // "warning" is scoped to the badge on purpose: this page prints each token's name as
  // its own swatch label, so the bare string appears twice — once as a surface label,
  // once as a badge — and an unscoped getByText would trip Playwright's strict mode.
  // `data-slot` is shadcn's own hook, present on every generated component.
  await expect(page.getByRole("button", { name: "default", exact: true })).toBeVisible();
  await expect(
    page.locator('[data-slot="badge"]').getByText("warning", { exact: true }),
  ).toBeVisible();

  // The tokens resolved. Deliberately not asserted on the token's literal text:
  // Lightning CSS downlevels oklch() to lab() when no browserslist is configured, so
  // the format is not ours to predict. What an unloaded layer actually produces is an
  // empty variable and a button whose background falls back to transparent, and that
  // is what these two assertions catch between them.
  const primary = await page.evaluate(() =>
    getComputedStyle(document.documentElement).getPropertyValue("--primary").trim(),
  );
  expect(primary).not.toBe("");

  const buttonBackground = await page
    .getByRole("button", { name: "default", exact: true })
    .evaluate((element) => getComputedStyle(element).backgroundColor);
  expect(buttonBackground).not.toBe("rgba(0, 0, 0, 0)");
  expect(buttonBackground).not.toBe("transparent");

  // The dialog opens and closes, which is the one interaction on this page.
  await page.getByRole("button", { name: "Open dialog" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);
});
