import { expect, test, type Page } from "@playwright/test";
import {
  ADMIN,
  MEMBER,
  apiAs,
  openAs,
  seedInstance,
  seedMember,
  seedTeam,
  unique,
  uniqueKey,
  userIdOf,
} from "./support";
import { startNotionWorkspace, type NotionWorkspaceStub } from "./notion-workspace";

/**
 * Scenario 27. Wiring a Notion « Demandes » base to a queue, with the mouse.
 *
 * `KAN-21` shipped the siphon and `GET/POST/DELETE /api/admin/notion/requests` and said out
 * loud that it had left the screen out. `RequestBaseServiceTest` already proves the guards
 * in process. What no in-process test can say is the thing this scenario is for: that the
 * two halves of the screen have the two *different* audiences the codebase spent a comment
 * apiece arguing for — discovery open to any member, registration the configurator's — and
 * that a refusal from the server arrives on screen as a sentence rather than as a status
 * code.
 *
 * ## Why it needs the suite's own workspace
 *
 * The list this screen picks from is `GET /api/notion/import/sources`, so with no
 * `NOTION_TOKEN` there is nothing to pick and the screen correctly prints a sentence
 * instead. `notion-workspace.ts` is therefore the precondition, and it is `import.spec.ts`'s
 * precondition too — same server, same `NOTION_BASE_URL` seam, same opt-in variable, so a
 * stack configured for scenario 23 runs this one as well and neither had to invent a second
 * mechanism:
 *
 * ```
 * KANSO_AUTH_MODE=dev NOTION_TOKEN=e2e-stub-token \
 *   NOTION_BASE_URL=http://host.docker.internal:8099/v1 \
 *   docker compose up -d --build --wait
 *
 * KANSO_NOTION_STUB=1 pnpm exec playwright test e2e/27-request-bases.spec.ts
 * ```
 *
 * The flag is the whole run-versus-skip decision, for the reason `import.spec.ts` writes
 * down at length: a guard that asked the API whether the bases were there would make
 * discovery — the feature under test — into the precondition, and would then skip rather
 * than fail the day it broke.
 */
const STUB = process.env.KANSO_NOTION_STUB === "1";

const SKIP = [
  "Needs the suite's own Notion workspace. Bring the stack up with",
  "NOTION_TOKEN=e2e-stub-token NOTION_BASE_URL=http://host.docker.internal:8099/v1,",
  "then run with KANSO_NOTION_STUB=1.",
].join(" ");

let stub: NotionWorkspaceStub | undefined;

test.beforeAll(async () => {
  await seedInstance();
  if (STUB) stub = await startNotionWorkspace();
});

test.afterAll(async () => {
  await stub?.close();
});

/** The panel, opened from the triage screen — the second of its two ways in. */
const openPanel = async (page: Page, teamId: string) => {
  await page.goto(`/triage?team=${teamId}`);
  await page.getByRole("button", { name: "Where these come from…" }).click();
  await expect(page.getByTestId("request-bases")).toBeVisible();
};

test("scenario 27 — a member sees the workspace, the configurator wires it to a queue", async ({
  browser,
}) => {
  test.skip(!STUB, SKIP);
  const workspace = stub!.workspace;

  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Requests"), key: uniqueKey() });
  await seedMember(api, team.id, await userIdOf(MEMBER));

  // --- the member's half: the list, and no way to change it ------------------
  //
  // `KAN-55` arbitrated that Notion discovery stays open to any member — "la dérogation est
  // la bonne règle" — and `RequestBaseService` argues the other half: choosing a base to
  // browse is not choosing one to wire the instance to permanently. So a member has to see
  // the databases *and* have no control that would come back 403. Both are asserted, because
  // either one alone is a screen that passes while contradicting the arbitration.
  const asMember = await openAs(browser, MEMBER);
  await openPanel(asMember, team.id);

  await expect(asMember.getByTestId("request-base-option")).toHaveCount(3);
  await expect(asMember.getByText(workspace.tickets.name, { exact: true })).toBeVisible();
  await expect(asMember.getByRole("combobox", { name: "Triage queue" })).toHaveCount(0);
  await expect(asMember.getByRole("button", { name: "Siphon into this queue" })).toHaveCount(0);
  await expect(
    asMember.getByText("Wiring one to a queue is a setting", { exact: false }),
  ).toBeVisible();
  // Not the ids of a wired base either: `RequestBaseController.list` is the configurator's,
  // and the panel does not ask for it on a member's behalf.
  await expect(asMember.getByTestId("wired-request-base")).toHaveCount(0);
  await asMember.close();

  // --- the configurator's half ----------------------------------------------
  const asAdmin = await openAs(browser, ADMIN);
  await openPanel(asAdmin, team.id);

  await expect(asAdmin.getByTestId("request-base-option")).toHaveCount(3);

  // Both pickers, and the button refusing to act until both are answered: a request with no
  // team is what `V37`'s header calls a dead letter box with a trigram index on it, so the
  // screen cannot let one be configured.
  const siphon = asAdmin.getByRole("button", { name: "Siphon into this queue" });
  await expect(siphon).toBeDisabled();

  await asAdmin
    .getByTestId("request-base-option")
    .filter({ hasText: workspace.tickets.name })
    .click();
  // Still refused: a base is chosen and a queue is not. This instance has more than one team
  // — every other file in the suite seeds its own — so the single-team shortcut cannot have
  // answered it.
  await expect(siphon).toBeDisabled();

  await asAdmin.getByRole("combobox", { name: "Triage queue" }).selectOption({ label: team.name });
  await expect(siphon).toBeEnabled();
  await siphon.click();

  // The arrangement, as the screen reports it back: the base's own name off discovery, and
  // the team named rather than its uuid printed.
  const wired = asAdmin.getByTestId("wired-request-base");
  await expect(wired).toHaveCount(1);
  await expect(wired).toContainText(workspace.tickets.name);
  await expect(wired).toContainText(`→ ${team.name}`);

  // And as the server holds it, which is the only proof the click reached Postgres rather
  // than a piece of local state: both ids, and the team.
  const listed = await api.get("/api/admin/notion/requests");
  expect(listed.ok()).toBeTruthy();
  const bases = (await listed.json()) as {
    dataSourceId: string;
    databaseId: string;
    teamId: string;
  }[];
  expect(bases).toEqual(
    expect.arrayContaining([
      {
        dataSourceId: workspace.tickets.dataSourceId,
        databaseId: workspace.tickets.databaseId,
        teamId: team.id,
      },
    ]),
  );

  // Stopping it. The row goes, and the server agrees.
  await wired.getByRole("button", { name: "Stop" }).click();
  await expect(asAdmin.getByTestId("wired-request-base")).toHaveCount(0);
  const after = await api.get("/api/admin/notion/requests");
  const remaining = (await after.json()) as { dataSourceId: string }[];
  expect(remaining.map((base) => base.dataSourceId)).not.toContain(
    workspace.tickets.dataSourceId,
  );

  // The workspace recorded every write it was asked for, and it was asked for none. The same
  // assertion scenario 23 ends on, and it means more here: a requests base is the one Notion
  // relationship whose whole promise is that Kanso never writes to it.
  expect(stub!.mutations).toEqual([]);

  await asAdmin.close();
  await api.dispose();
});

/**
 * The refusal, and a database name longer than the row it is drawn in.
 *
 * Both are answered by intercepting the two calls rather than by arranging the state that
 * would produce them, and the reason is different for each.
 *
 * **The mirror refusal cannot be staged through the interface at all.** `NotionDiscovery`
 * filters Kanso's own four databases out of the list, so the only way to click a mirror base
 * is with a list that went stale — a panel left open across a `bootstrapNotion`, or a second
 * tab — and the only way to *make* a `notion_databases` row is `NotionBootstrap`, which
 * creates pages in a workspace this suite's own server refuses to write. Staging it would
 * mean reaching into Postgres from a spec. What this screen is responsible for is not the
 * guard — `RequestBaseService` owns that, and its own test proves it — it is that the
 * server's sentence reaches a person's eyes, so that is what is pinned here, against the
 * exact `problem+json` the API answers.
 *
 * **The long name is a shape the fake workspace does not have** and this spec may not give
 * it one: `notion-workspace.ts` is scenario 23's, and a base renamed to two hundred
 * characters to suit this file would change what that scenario reads.
 *
 * The defect being watched for is specific and this repo has shipped it twice: two `<span>`s
 * in a container that forgot `display: flex` are inline boxes, so they run together into one
 * line — "That base cannot be siphonedThat database is one Kanso's own mirror writes to." —
 * and the assertion is therefore geometric. Two boxes, and the second one starts below the
 * first.
 */
test("scenario 27b — the refusal is a sentence, on its own line, and a long name truncates", async ({
  browser,
}) => {
  test.skip(!STUB, SKIP);

  const api = await apiAs(ADMIN);
  const team = await seedTeam(api, { name: unique("Refused"), key: uniqueKey() });

  /** What `RequestBaseService.register` answers for one of the mirror's own databases. */
  const MIRROR_REFUSAL =
    "That database is one Kanso's own mirror writes to. Siphoning it would adopt the " +
    "instance's own tickets back into itself, on every poll.";

  const longName = `Demandes ${"commerciales et support ".repeat(8)}`.trim();

  const page = await openAs(browser, ADMIN);

  // One base, named far too long for its row. Everything else about the answer is the shape
  // `ImportSources` really has, `databaseId` included — the field this screen needed and
  // that the client type had never named.
  await page.route("**/api/notion/import/sources", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        available: true,
        reason: null,
        sources: [
          {
            id: "ds-mirror-tickets",
            databaseId: "db-mirror-tickets",
            name: longName,
            pages: 12,
            pagesExact: true,
          },
        ],
      }),
    }),
  );

  await page.route("**/api/admin/notion/requests", async (route) => {
    if (route.request().method() !== "POST") return route.continue();
    await route.fulfill({
      status: 400,
      contentType: "application/problem+json",
      body: JSON.stringify({
        type: "about:blank",
        title: "Bad Request",
        status: 400,
        detail: MIRROR_REFUSAL,
      }),
    });
  });

  await openPanel(page, team.id);

  // The name is drawn, and the row is not made wider by it: the cell truncates into whatever
  // the page count leaves. Compared against the panel rather than against a number, so this
  // survives a change of width.
  const option = page.getByTestId("request-base-option");
  await expect(option).toHaveCount(1);
  await expect(option).toContainText("Demandes commerciales");
  const row = (await option.boundingBox())!;
  const panel = (await page.getByTestId("request-bases").boundingBox())!;
  expect(row.width).toBeLessThanOrEqual(panel.width);

  await option.click();
  await page.getByRole("combobox", { name: "Triage queue" }).selectOption({ label: team.name });
  await page.getByRole("button", { name: "Siphon into this queue" }).click();

  // The server's own sentence, not a status code — the whole point of the block.
  const refused = page.getByTestId("request-base-refused");
  await expect(refused).toBeVisible();
  await expect(refused).toContainText("That base cannot be siphoned");
  await expect(refused).toContainText(MIRROR_REFUSAL);

  // And on two lines. `display: flex` on the container is what puts them there; without it
  // both spans are inline boxes on one line and every text assertion above still passes.
  const heading = (await refused.locator("span").first().boundingBox())!;
  const reason = (await refused.locator("span").nth(1).boundingBox())!;
  expect(reason.y).toBeGreaterThanOrEqual(heading.y + heading.height);

  await page.close();
  await api.dispose();
});
