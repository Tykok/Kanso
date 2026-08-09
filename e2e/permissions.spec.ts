import { expect, test } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedProject,
  seedTeam,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * Scenario 3. Two identities in a single test, which `dev` mode makes possible with
 * no OAuth to simulate: the same page, two headers.
 */
test("scenario 3 — a member sees no team writes, an admin sees them all", async ({ browser }) => {
  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Shape"), key: uniqueKey() });
  const project = await seedProject(api, { name: unique("Shared"), teamId: team.id });
  await api.dispose();

  const admin = await openAs(browser, ADMIN);
  const member = await openAs(browser, MEMBER);

  // The admin: the teams + and the team row's ⋯.
  await expect(admin.getByRole("button", { name: "New team", exact: true })).toBeVisible();
  await expect(
    admin.getByRole("button", { name: `Actions for ${team.name}`, exact: true }),
  ).toHaveCount(1);

  // The member: neither.
  await expect(member.getByRole("button", { name: team.name, exact: true })).toBeVisible();
  await expect(member.getByRole("button", { name: "New team", exact: true })).toHaveCount(0);
  await expect(
    member.getByRole("button", { name: `Actions for ${team.name}`, exact: true }),
  ).toHaveCount(0);

  // The shape of the organisation is an admin decision; the daily work is not.
  // Projects stay open to the member.
  await expect(member.getByRole("button", { name: "New project", exact: true })).toBeVisible();
  await expect(
    member.getByRole("button", { name: `Actions for ${project.name}`, exact: true }),
  ).toHaveCount(1);
});
