import { expect, test, type Locator } from "@playwright/test";
import { ADMIN, apiAs, openAs, seedInstance, seedTeam, sidebarRow, ticketRow, unique, uniqueKey } from "./support";
import { STUB_BASE_URL, startNotionWorkspace, type NotionWorkspaceStub } from "./notion-workspace";

/**
 * Scenario 23. Screen 24's five steps, in a browser, against a workspace somebody else
 * built.
 *
 * Every other proof of the import is in process: `NotionImportTest` and its siblings drive
 * the writers against `FakeNotionWorkspace`, and `import-map.test.ts` and
 * `import-columns.test.ts` pin the arithmetic the screens show. What none of them can say
 * is that the five screens, the wire between them and the four writers agree — that a
 * reader who maps `Etat → status` and `En cours → In progress` in the browser ends up with
 * a ticket in `In progress`. That is this file.
 *
 * ## What makes it possible
 *
 * The API reads Notion's base URL from `NOTION_BASE_URL`, so `e2e/notion-workspace.ts`
 * stands up a workspace of its own and the container is pointed at it. The client under
 * test is therefore the real `HttpNotionClient`, and nothing test-shaped is bound inside
 * the application: the alternative — a `NotionClient` bean chosen by a profile — would put
 * a fake in the production source set to make an e2e possible, which is the one thing this
 * was not allowed to cost.
 *
 * It needs a stack told where that workspace is, which the default one is not. So the
 * runner opts in explicitly, with `KANSO_NOTION_STUB=1`, and that variable is the **only**
 * thing that decides whether this scenario runs.
 *
 * That distinction matters more than it looks. The obvious guard — ask
 * `GET /api/notion/import/sources` whether the three bases are there, and skip if they are
 * not — makes the precondition *the feature under test*: `NotionDiscovery` over
 * `HttpNotionClient.searchDatabases`, filter fallback and all. Break discovery and the
 * scenario would skip on a correctly configured stack, leaving a green suite and one skip
 * indistinguishable from "nobody configured a workspace". A net that disarms itself when the
 * thing regresses is not a net. So configuration decides run-versus-skip, and the source
 * list is an `expect` inside the test that fails loudly.
 *
 * ## What it asserts, beyond clicking through
 *
 * - the three bases and their page counts, off the workspace as the server searched it;
 * - a relation the reader has not answered yet surfacing as the plan screen's suggestion,
 *   and accepting it mapping the base it points at;
 * - the folded columns panel offering only the columns whose *type* can carry a field, and
 *   the server's own pre-fill having arrived;
 * - the option table: `En cours` mapped by hand, and the two words Kanso's vocabulary does
 *   not hold named as falling back — in words, not as a count;
 * - no fallback question asked anywhere, because every link in this workspace resolves;
 * - the person the mapped column names, matched to a Kanso account;
 * - and then the rows themselves: a ticket in `In progress` inside the project its relation
 *   named, inside the team *that* project's relation named, assigned to the account the
 *   reader chose — and the other ticket in `Todo`, because `Terminé` is not a word Kanso
 *   knows.
 *
 * Last, the promise the first screen makes in as many words: the workspace records every
 * write it was asked for, and the assertion is that it was asked for none.
 */

/**
 * The runner's own opt-in, and the whole of the run-versus-skip decision.
 *
 * A configuration flag rather than a probe, for the reason the file comment gives: anything
 * derived from the API's answers would let a broken import silence its own scenario. Read
 * once, here, so there is one definition of "this stack was set up for the import".
 */
const OPTED_IN = process.env.KANSO_NOTION_STUB === "1";

/** The command that turns this scenario on, quoted wherever it is refused or fails. */
const HOW = `Bring the stack up with NOTION_TOKEN=e2e-stub-token NOTION_BASE_URL=${STUB_BASE_URL}, then run the suite with KANSO_NOTION_STUB=1 — see e2e/README.md.`;

let stub: NotionWorkspaceStub;

test.beforeAll(async () => {
  // Nothing at all when the runner did not opt in — not even a listening socket. A default
  // `pnpm test:e2e` should not bind a port for a scenario it is about to skip.
  if (!OPTED_IN) return;
  await seedInstance();
  stub = await startNotionWorkspace();
});

test.afterAll(async () => {
  await stub?.close();
});

test("scenario 23 — the five screens of the Notion import, end to end", async ({ browser }) => {
  test.skip(!OPTED_IN, `KANSO_NOTION_STUB is not set. ${HOW}`);

  const { teams, projects, tickets, person, teamTitle, projectTitle } = stub.workspace;

  // A destination team has to exist before the dialog opens: step 2 will not let anybody
  // past it while the plan holds something other than teams and no team is chosen, and a
  // fresh instance has none. Nothing in this workspace actually lands in it — every link
  // resolves — which is the point of asserting where the rows end up instead.
  const api = await apiAs(ADMIN);
  const me = (await (await api.get("/api/me")).json()) as { user: { id: string; displayName: string } };
  const destination = await seedTeam(api, { name: unique("Destination"), key: uniqueKey() });

  /*
   * The two halves are talking — asserted, never used to decide whether to run.
   *
   * This is the first thing the dialog will ask for, and asking it here rather than through
   * the browser is what makes a wiring failure read as "the workspace search answered
   * without these three bases" instead of as a locator timing out on step 1. It is also the
   * assertion that goes red if discovery, the `data_source` → `database` filter fallback, or
   * the base-URL wiring breaks — which is why it is an `expect` and not the skip condition:
   * see the file comment.
   */
  const discovered = await api.get("/api/notion/import/sources");
  expect(discovered.ok(), `GET /api/notion/import/sources answered ${discovered.status()}. ${HOW}`).toBeTruthy();
  const sources = (await discovered.json()) as {
    available: boolean;
    reason: string | null;
    sources: { id: string }[];
  };
  expect(sources.available, `The workspace search refused: ${sources.reason ?? "no reason given"}. ${HOW}`)
    .toBeTruthy();
  const found = sources.sources.map((source) => source.id);
  for (const base of [teams, projects, tickets]) {
    expect(found, `${base.name} (${base.dataSourceId}) is not among the bases discovery found. ${HOW}`)
      .toContain(base.dataSourceId);
  }

  await api.dispose();

  const page = await openAs(browser, ADMIN);

  // ⌘K, because that is the path the plan names and the one a reader who has already
  // dismissed the empty inbox is left with.
  await page.keyboard.press("ControlOrMeta+k");
  const palette = page.getByTestId("palette");
  await palette.getByPlaceholder("Type a command…").fill("Import from Notion");
  await palette.getByTestId("search-result").filter({ hasText: "Import from Notion" }).first().click();

  const dialog = page.getByRole("dialog", { name: "Import from Notion" });
  await expect(dialog).toBeVisible();

  // --- step 1: what the workspace holds ------------------------------------

  for (const base of [teams, projects, tickets]) {
    await expect(dialog.getByText(base.name, { exact: true })).toBeVisible();
  }
  // Counted by walking the pages, so this is the discovery walk's own answer and not the
  // stub's: one team page, one project page, two task pages.
  await expect(dialog.getByText("3 databases, 4 pages in all.")).toBeVisible();

  // --- step 2: what becomes what -------------------------------------------

  await expect(dialog.getByText("Choose what becomes what")).toBeVisible();
  await dialog.getByRole("combobox", { name: "Into team" }).selectOption(destination.id);

  await cycleTo(dialog, teams.name, "Teams");
  await cycleTo(dialog, tickets.name, "Tickets");

  // `Projets` is still ignored, and the tasks base's `Project` relation points at it — so
  // the suggestion is on screen with the count of what ignoring it costs. Accepting it is
  // what maps the base, which is the decision the second step was built around: the reader
  // answers "what is this relation" once, on the base they were already looking at.
  const hint = dialog.getByText(`${tickets.name} is related to ${projects.name}`);
  await expect(hint).toBeVisible();
  await expect(hint).toContainText("which nothing is importing. Those relations will be dropped.");
  await dialog.getByRole("button", { name: "Import it as projects" }).click();

  await expect(becomesButton(dialog, projects.name)).toHaveAccessibleName(`${projects.name} becomes: Projects`);
  // Answered, so it stops asking. The hint is the warning and the suggestion at once.
  await expect(hint).toHaveCount(0);
  await expect(dialog.getByText("4 of 4 pages kept")).toBeVisible();

  await dialog.getByRole("button", { name: /Columns and people/ }).click();

  // --- step 3: the columns, and the words inside them -----------------------

  await expect(dialog.getByText("Say which column is which")).toBeVisible();

  const teamSection = section(dialog, teams.name);
  const ticketSection = section(dialog, tickets.name);

  // The server's pre-fill arrived: `Parent team` is named exactly what the mirror would
  // call it, so one of the teams target's three fields is answered before anybody clicks.
  await expect(teamSection.getByText("1 of 3 fields mapped")).toBeVisible();

  // Only the columns whose type can carry a status are offered, which on this base is the
  // one select — a workspace's own words are the reason the *list* is filtered by type and
  // the *choice* is left to the reader.
  const status = ticketSection.getByRole("combobox", { name: "Status" });
  await expect(status.locator("option")).toHaveText(["— none —", "Etat"]);
  await expect(ticketSection.getByRole("combobox", { name: "Description" }).locator("option")).toHaveText([
    "— none —",
    "Notes",
  ]);

  await status.selectOption("Etat");

  // The options unfold under the column that was just mapped. `En cours` is said by hand;
  // the two Kanso has no word for are named as falling back, rather than counted.
  await ticketSection.getByRole("combobox", { name: "En cours" }).selectOption("in_progress");
  await expect(ticketSection.getByText("À faire, Terminé become the default — Todo.")).toBeVisible();

  // Nothing is asked about where an unlinked row lands, because in this workspace nothing
  // is unlinked: the project names its team, the tasks name their project, and a resolved
  // link is silent. Asserted over the whole step, not one section.
  for (const question of [
    "Unlinked rows land in team",
    "Teams with no parent land under",
    "Tickets with no project land in",
  ]) {
    await expect(dialog.getByText(question)).toHaveCount(0);
  }

  // --- step 4: who these people are ----------------------------------------

  await expect(dialog.getByText("Who these people are")).toBeVisible();
  // The panel is mounted from the moment the dialog opens, not from this click, and
  // `peopleSeen`'s query key is the plan itself — so every column mapped above restarts it.
  // The default timeout races the *last* restart, triggered by the "En cours" select a few
  // lines up; a generous one here is the cost of the request being real rather than stubbed
  // into the component, which is the whole reason this file exists.
  await expect(dialog.getByText(person.name)).toBeVisible({ timeout: 20_000 });
  // The only select on this step, one row per person the *mapped* columns name — and this
  // workspace names one, on both task pages, counted once. Scoped to that person's own row:
  // the folded panel keeps every `StepColumns` select mounted beside this one now, so the
  // dialog's own combobox count is no longer the bound this line is testing.
  const personRow = dialog.locator("div").filter({ hasText: person.name }).last();
  const match = personRow.getByRole("combobox");
  await expect(match).toHaveCount(1);
  await match.selectOption(me.user.id);

  await dialog.getByRole("button", { name: "Preview the import" }).click();

  // --- step 5: the last read, then the only write --------------------------

  await expect(dialog.getByText("4 pages out of 4.")).toBeVisible();
  // The server's own arithmetic, not the screen's guess at it: all three bases take part in
  // a resolved link, and three rows are placed by one — the project into its team, the two
  // tasks into their project.
  await expect(
    dialog.getByText("3 of them are linked to each other; 3 rows are placed by those relations"),
  ).toBeVisible();
  // Two columns were left at "— none —" — `Avancement` on the projects base and `Notes` on
  // the tasks base — so both are preserved in the "imported from Notion" section rather
  // than dropped, and the reader is told which properties that covers before they confirm.
  await expect(dialog.getByText("Unmapped properties: Avancement, Notes.")).toBeVisible();

  await dialog.getByRole("button", { name: "Import 4 pages" }).click();

  // One project, not two: `TicketImport` only makes a container project for tickets whose
  // own relation answered nothing, and here it answered.
  await expect(
    dialog.getByText(
      "Imported 1 team, 1 project, 2 tickets, 0 documents in 0 folders, and 0 dependencies.",
    ),
  ).toBeVisible();
  await dialog.getByRole("button", { name: "Done" }).click();
  await expect(dialog).toHaveCount(0);

  // --- and then the rows ---------------------------------------------------

  // The team came over at the top level and the project sits under it — which it can only
  // do by way of its `Team` relation, since the destination chosen in step 2 was a
  // different team entirely.
  await expect(sidebarRow(page, teamTitle)).toHaveClass(/nav-depth-0/);
  await expect(sidebarRow(page, projectTitle)).toHaveClass(/nav-depth-1/);

  await page.getByRole("button", { name: teamTitle, exact: true }).click();

  const mapped = ticketRow(page, stub.workspace.mappedTicketTitle);
  await expect(mapped).toBeVisible();
  // The whole branch in one assertion: a column called `Etat` holding a word called
  // `En cours` became a Kanso status, because somebody said so on screen 3.
  await expect(mapped.getByTestId("status-pill")).toHaveText("In progress");
  await expect(mapped).toContainText(projectTitle);

  // And the option nobody mapped took the writer's default rather than becoming a seventh
  // status nothing else understands.
  const defaulted = ticketRow(page, stub.workspace.defaultedTicketTitle);
  await expect(defaulted.getByTestId("status-pill")).toHaveText("Todo");

  // The assignee is not on the list row — it has no column for one — so it is read where
  // the application does draw it, on the ticket's own page.
  const identifier = await mapped.getByTestId("row-id").innerText();
  await page.goto(`/t/${identifier}`);
  // Not `exact`: the chip holds the avatar's own initials beside the name, in one element.
  const chip = page.getByText(me.user.displayName);
  await expect(chip).toHaveCount(1);
  await expect(chip).toBeVisible();

  // And what no column claimed is still here rather than lost: `Notes` was left at
  // "— none —" on step 3, so it comes over as prose under "Imported from Notion".
  await expect(page.getByRole("textbox", { name: "Description" })).toHaveValue(
    /Imported from Notion[\s\S]*Notes: Vu avec le support/,
  );

  /*
   * Read a second time, out of the API, the way scenario 12 reads a bar's dates off both
   * the chart and the endpoint. A name on screen is the right assertion for what a reader
   * sees; the *id* is what says the correspondence step wrote a link to the account the
   * reader chose rather than to somebody who happens to share a display name.
   */
  const after = await apiAs(ADMIN);
  try {
    const [teamKey, number] = identifier.split("-");
    const response = await after.get(`/api/tickets/by-key/${teamKey}/${number}`);
    expect(response.ok(), `Could not read back ${identifier}`).toBeTruthy();
    const ticket = (await response.json()) as { status: string; assigneeIds: string[] };
    expect(ticket.status).toBe("in_progress");
    expect(ticket.assigneeIds).toEqual([me.user.id]);
  } finally {
    await after.dispose();
  }

  // Screen 1 says nothing is changed in Notion at any step. The workspace recorded every
  // write it was asked for, and it was asked for none.
  expect(stub.mutations, `Notion was written to: ${stub.mutations.join(", ")}`).toEqual([]);
});

/**
 * The `Becomes` button of one base's row — the cycling one, named for what it currently
 * says.
 *
 * Located by the base's name rather than by position: step 2 draws its rows in the order
 * the workspace search answered, and a scenario that counted on that order would break on
 * a change nobody could see.
 */
function becomesButton(dialog: Locator, baseName: string): Locator {
  return dialog.getByRole("button", { name: new RegExp(`^${escaped(baseName)} becomes: `) });
}

/**
 * Presses one base's `Becomes` button until it reads [target].
 *
 * One button cycling five answers rather than a select, so reaching `Tickets` from the
 * `Ignore` everything starts at is three presses. Bounded by the length of the cycle: a
 * target this never reaches is a changed vocabulary, and looping forever would report it as
 * a timeout in the wrong place.
 */
async function cycleTo(dialog: Locator, baseName: string, target: string): Promise<void> {
  const button = becomesButton(dialog, baseName);
  for (let press = 0; press < 5; press++) {
    const name = await button.getAttribute("aria-label");
    if (name === `${baseName} becomes: ${target}`) return;
    await button.click();
  }
  throw new Error(`'${baseName} becomes: ${target}' was not reachable by cycling the button`);
}

/**
 * One base's section of step 3.
 *
 * By the base's own name, which carries a per-run suffix, so it cannot match the section of
 * a sibling base or of another run's leftovers.
 */
function section(dialog: Locator, baseName: string): Locator {
  return dialog.locator("section").filter({ hasText: baseName });
}

const escaped = (raw: string) => raw.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
