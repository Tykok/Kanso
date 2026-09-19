import { expect, test, type APIRequestContext } from "@playwright/test";

import { ADMIN, apiAs, openAs, seedInstance, seedTeam, unique, uniqueKey } from "./support";

/**
 * `V43` on screen: the composer is one line until a template is chosen, and unfolds when
 * one is.
 *
 * The templates are made over HTTP rather than by driving the settings form, for the reason
 * `25-custom-fields.spec.ts` gives about its definitions: what this spec is for is the
 * *composer* half — that the fold works, that a template fills the form, and that a name the
 * team does not have is said out loud — and clicking through the editor to set that up would
 * make a failure in the editor look like a failure in the composer. `TicketTemplateTest` owns
 * the catalogue rules.
 */
test.describe("ticket templates", () => {
  test.beforeAll(async () => {
    await seedInstance();
  });

  async function defineTemplate(
    api: APIRequestContext,
    data: Record<string, unknown>,
  ): Promise<string> {
    const made = await api.post("/api/tickets/templates", { data });
    expect(made.ok(), `Could not create the template: ${made.status()}`).toBeTruthy();
    return ((await made.json()) as { id: string }).id;
  }

  test("a template fills the composer, and the ticket carries its label", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Templates"), key: uniqueKey() });

    const label = await api.post(`/api/teams/${team.id}/labels`, {
      data: { name: "bug", colour: "rose" },
    });
    expect(label.ok()).toBeTruthy();

    const name = unique("Bug report");
    await defineTemplate(api, {
      teamId: team.id,
      name,
      body: {
        title: "[Bug] ",
        description: "## What happens\n\n## What should happen\n",
        priority: "high",
        labels: ["bug"],
      },
      categories: ["Engineering"],
    });

    const page = await openAs(browser, ADMIN);
    await page.goto(`/?team=${team.id}`);
    await page.keyboard.press("c");

    const title = page.getByPlaceholder("New ticket…");
    await expect(title).toBeFocused();
    // Folded: the description does not exist until a template asks for it. This is the
    // property the whole design turns on — `c` then ↵ must stay one line for everybody who
    // never opens the picker.
    await expect(page.getByPlaceholder("Describe it…")).toHaveCount(0);

    await page.getByLabel("Ticket team").selectOption(team.id);
    await page.getByLabel("Ticket template").selectOption({ label: name });

    const description = page.getByPlaceholder("Describe it…");
    await expect(description).toBeVisible();
    await expect(description).toHaveValue("## What happens\n\n## What should happen\n");
    await expect(title).toHaveValue("[Bug] ");
    // The label the template named, resolved against this team and drawn as a pill.
    await expect(page.getByRole("button", { name: "bug ×" })).toBeVisible();

    // Everything the template placed is still editable — that is the difference between a
    // template and a form that submits itself.
    const wanted = unique("[Bug] Login throws on submit");
    await title.fill(wanted);
    await title.press("Enter");

    await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);

    const filed = await api.get(`/api/tickets?teamId=${team.id}`);
    const rows = (await filed.json()) as { id: string; title: string }[];
    const made = rows.find((row) => row.title === wanted);
    expect(made, "the ticket the composer filed").toBeTruthy();

    // Written after the row exists, by its own endpoint, because POST /api/tickets has never
    // carried labels. Polled rather than asserted once: the composer does not await it.
    await expect
      .poll(async () => {
        const worn = await api.get(`/api/tickets/${made!.id}/labels`);
        return ((await worn.json()) as { name: string }[]).map((one) => one.name);
      })
      .toContain("bug");
  });

  test("a name this team does not have is said out loud, and the ticket still files", async ({
    browser,
  }) => {
    const api = await apiAs(ADMIN);
    // No labels on this team at all, so nothing the template names can resolve.
    const team = await seedTeam(api, { name: unique("Bare"), key: uniqueKey() });

    const name = unique("Regression");
    await defineTemplate(api, {
      teamId: team.id,
      name,
      body: { description: "## What regressed\n", labels: ["regression"] },
      categories: [],
    });

    const page = await openAs(browser, ADMIN);
    await page.goto(`/?team=${team.id}`);
    await page.keyboard.press("c");
    await page.getByLabel("Ticket team").selectOption(team.id);
    await page.getByLabel("Ticket template").selectOption({ label: name });

    // The sentence is the feature: a template that degrades has to say so, and must not
    // refuse. Never a modal, never a blocked button.
    await expect(page.getByText(/regression.*which this team does not have/)).toBeVisible();

    const wanted = unique("Something regressed");
    const title = page.getByPlaceholder("New ticket…");
    await title.fill(wanted);
    await title.press("Enter");

    await expect(page.getByPlaceholder("New ticket…")).toHaveCount(0);
    const filed = await api.get(`/api/tickets?teamId=${team.id}`);
    const rows = (await filed.json()) as { title: string }[];
    expect(rows.some((row) => row.title === wanted), "it still files").toBeTruthy();
  });
});
