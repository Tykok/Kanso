import { expect, test } from "@playwright/test";

/**
 * Scenario 15 — the workbench itself.
 *
 * Deliberately thin: this page has almost no behaviour to test. What it mostly
 * catches is a token layer that failed to load, which renders as unstyled markup
 * rather than an error, and which every later migration task would then be
 * building against. The one thing added since — the Contrast section's Light/Dark
 * toggle — gets its own real assertion below: not that the toggle exists, but that
 * flipping it changes what a status hue actually computes to, which is the only
 * way to tell "reads live tokens" apart from "restates a hex pair by hand".
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

  // The dialog opens and closes.
  await page.getByRole("button", { name: "Open dialog" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).toHaveCount(0);

  // The Contrast section's Light/Dark toggle. "Backlog" is the status hue's own
  // label (`ContrastMatrix`, `sections.tsx`) and appears nowhere else on this page,
  // so it needs no further scoping. The claim worth making isn't that the toggle
  // exists — it's that pressing it changes what the label's colour actually
  // computes to, which a hardcoded pair of hex values would not do: this is what
  // tells "reads the live token" apart from "restates it".
  const scheme = page.getByRole("group", { name: "Preview scheme" });
  const backlogLabel = page.getByText("Backlog", { exact: true });
  const lightColor = await backlogLabel.evaluate((el) => getComputedStyle(el).color);

  await scheme.getByRole("button", { name: "Dark", exact: true }).click();
  // The toggle drives the real `document.documentElement` class the app's own
  // theme switch uses (`lib/theme.ts`), not a scoped class of its own — this is
  // what lets a nested "light" reading be trusted at all (see `ContrastMatrix`'s
  // own comment on why a static two-column version would have lied).
  await expect
    .poll(() => page.evaluate(() => document.documentElement.classList.contains("dark")))
    .toBe(true);
  const darkColor = await backlogLabel.evaluate((el) => getComputedStyle(el).color);
  expect(darkColor).not.toBe(lightColor);

  // And back, restoring the tab's real theme to what it was before this test
  // touched it — the component's own unmount cleanup does this when the page
  // navigates away, but nothing here navigates away.
  await scheme.getByRole("button", { name: "Light", exact: true }).click();
  await expect
    .poll(() => page.evaluate(() => document.documentElement.classList.contains("dark")))
    .toBe(false);
  const backToLightColor = await backlogLabel.evaluate((el) => getComputedStyle(el).color);
  expect(backToLightColor).toBe(lightColor);
});
