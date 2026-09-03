import { expect, test, type APIRequestContext } from "@playwright/test";

import { ADMIN, apiAs, openAs, seedInstance, seedTeam, seedTicket, unique, uniqueKey } from "./support";

/**
 * `V32` on screen: a team defines a field, a ticket carries a value, and the ticket draws a
 * control per type.
 *
 * The definitions are made over HTTP rather than by driving the team dialog, for the reason
 * `seedTicket` gives about start dates: what this spec is for is the *reading* half — that
 * the four controls render, that a value round-trips, and that `customFields` is on the wire
 * — and clicking through a four-input form to set that up would make a failure in the form
 * look like a failure in the panel. `CustomFieldTest` owns the definition rules.
 */
test.describe("custom fields", () => {
  test.beforeAll(async () => {
    await seedInstance();
  });

  /** One of every type in the vocabulary, so a type without a renderer fails here. */
  const SPECS = [
    { name: "Severity", type: "select", required: false, options: ["low", "high", "urgent"] },
    { name: "Customer", type: "text", required: false, options: [] },
    { name: "Escaped", type: "boolean", required: false, options: [] },
    { name: "Impact", type: "number", required: false, options: [] },
  ];

  async function define(
    api: APIRequestContext,
    teamId: string,
    spec: (typeof SPECS)[number],
  ): Promise<string> {
    const made = await api.post(`/api/teams/${teamId}/fields`, { data: spec });
    expect(made.ok(), `Could not define ${spec.name}: ${made.status()}`).toBeTruthy();
    return ((await made.json()) as { id: string }).id;
  }

  test("a field is defined, valued, and drawn with a control per type", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Fields"), key: uniqueKey() });

    const ids: Record<string, string> = {};
    for (const spec of SPECS) ids[spec.name] = await define(api, team.id, spec);

    const ticket = await seedTicket(api, { teamId: team.id, title: unique("A customer hit the bug") });

    // The slot is in the shape before anything is in it — the whole reason this shipped
    // ahead of demand, asserted where an integration would meet it.
    const fresh = await api.get(`/api/tickets/${ticket.id}`);
    expect(
      ((await fresh.json()) as { customFields: Record<string, unknown> }).customFields,
      "a fresh ticket must carry the key, empty",
    ).toEqual({});

    const wrote = await api.put(`/api/tickets/${ticket.id}/fields`, {
      data: {
        [ids.Severity]: "high",
        [ids.Customer]: "Pictarine",
        [ids.Escaped]: true,
        [ids.Impact]: 3.5,
      },
    });
    expect(wrote.ok(), `Could not set the values: ${wrote.status()}`).toBeTruthy();

    // Typed on the way back out: a number stays a number and a boolean a boolean, which a
    // TEXT column holding stringified values would have flattened into two strings nothing
    // could tell apart.
    const read = await api.get(`/api/tickets/${ticket.id}`);
    const shape = (await read.json()) as { customFields: Record<string, unknown> };
    expect(shape.customFields[ids.Impact]).toBe(3.5);
    expect(shape.customFields[ids.Escaped]).toBe(true);
    expect(shape.customFields[ids.Severity]).toBe("high");

    // --- and now the screen ------------------------------------------------
    const page = await openAs(browser, ADMIN);
    await page.goto(`/t/${ticket.identifier}`);

    for (const spec of SPECS) {
      await expect(
        page.getByText(spec.name, { exact: true }).first(),
        `${spec.name} was not drawn`,
      ).toBeVisible();
    }

    // A control per type, addressed by the id `TicketFields` gives each one.
    await expect(page.locator(`#field-${ids.Severity}`)).toHaveValue("high");
    await expect(page.locator(`#field-${ids.Customer}`)).toHaveValue("Pictarine");
    await expect(page.locator(`#field-${ids.Escaped}`)).toBeChecked();
    await expect(page.locator(`#field-${ids.Impact}`)).toHaveValue("3.5");

    await page.close();
    await api.dispose();
  });

  /**
   * The trap `lib/field-values.ts` exists for, checked through the real control: every HTML
   * input hands back a string, number boxes included, and a `"7"` in a number field is
   * refused by the server. Without the coercion this draws an error instead of saving.
   */
  test("a number typed into the panel is sent as a number, not a string", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Numbers"), key: uniqueKey() });
    const impact = await define(api, team.id, {
      name: "Impact",
      type: "number",
      required: false,
      options: [],
    });
    const ticket = await seedTicket(api, { teamId: team.id, title: unique("Sized from the panel") });

    const page = await openAs(browser, ADMIN);
    await page.goto(`/t/${ticket.identifier}`);

    const box = page.locator(`#field-${impact}`);
    await expect(box).toBeVisible();
    await box.fill("7");
    // Written on blur, not per keystroke: a number box fires on every digit, and each one
    // would land in the activity feed as a decision somebody made.
    await box.blur();

    await expect(async () => {
      const read = await api.get(`/api/tickets/${ticket.id}`);
      const shape = (await read.json()) as { customFields: Record<string, unknown> };
      expect(shape.customFields[impact]).toBe(7);
    }).toPass({ timeout: 10_000 });

    // And no *field* refusal drawn, which is what a missing coercion would have produced —
    // matched on the codec's own wording rather than on `role=alert` alone, because the
    // ticket page already carries one: the duration note says "not sized yet" in an alert,
    // and a bare count here would have been red on every ticket without an estimate.
    await expect(page.getByRole("alert").filter({ hasText: /expects/ })).toHaveCount(0);

    await page.close();
    await api.dispose();
  });

  /**
   * A team that has defined nothing draws nothing — not an empty "Custom fields" heading,
   * which would teach a reader to ignore that part of every ticket in the instance.
   */
  test("a team with no fields draws no section at all", async ({ browser }) => {
    const api = await apiAs(ADMIN);
    const team = await seedTeam(api, { name: unique("Bare"), key: uniqueKey() });
    const ticket = await seedTicket(api, { teamId: team.id, title: unique("Nothing custom") });

    const page = await openAs(browser, ADMIN);
    await page.goto(`/t/${ticket.identifier}`);
    await expect(page.getByText(ticket.title)).toBeVisible();

    await expect(page.locator("[id^=field-]")).toHaveCount(0);

    await page.close();
    await api.dispose();
  });
});
