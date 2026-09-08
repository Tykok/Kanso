import { expect, request as playwrightRequest, test } from "@playwright/test";
import {
  ADMIN,
  API_URL,
  WEB_URL,
  apiAs,
  seedInstance,
  seedTeam,
  seedTicket,
  unique,
  uniqueKey,
} from "./support";

test.beforeAll(seedInstance);

/**
 * The ticket this file publishes, withdrawn again afterwards.
 *
 * The roadmap window is capped at 400 rows and `PublicRoadmapRepository.findPublished`
 * has no `ORDER BY` at all, so which published tickets are inside it is arbitrary. A run
 * that leaves its ticket published adds a row to that window for every run after it, and
 * the assertions below then answer to how many times this file has been run against the
 * instance rather than to what the projection does. Both directions rot: `shown` is
 * eventually not in the window, and `hidden`'s absence eventually passes for the wrong
 * reason — truncation reads exactly like exclusion from here.
 *
 * In an `afterEach` and not at the end of the test, because a run that fails half way is
 * precisely the run that would otherwise leave the row behind.
 */
let publishedTicket: string | undefined;

test.afterEach(async () => {
  if (!publishedTicket) return;
  const admin = await apiAs(ADMIN);
  try {
    const withdrawn = await admin.patch(`/api/tickets/${publishedTicket}/publication`, {
      data: { public: false },
    });
    expect(withdrawn.ok(), "the published ticket is withdrawn again").toBeTruthy();
  } finally {
    publishedTicket = undefined;
    await admin.dispose();
  }
});

/**
 * Scenario 22 — the public surfaces, visited the way a stranger visits them.
 *
 * Every other scenario in this suite opens a page through `openAs`, which plants an
 * identity in `localStorage` before the first script runs. This one must not: the claim
 * under test is that three routes answer with no identity at all, and a helper that
 * supplies one would make the test unable to fail. So it uses a bare browser context and
 * a bare API context, and the first thing it checks is that a route the rest of the
 * application relies on is still refused to the same caller — otherwise a chain opened
 * too widely would look like a pass.
 */
test("scenario 22 — the roadmap and the contributor page answer without a session, and nothing private does", async ({
  browser,
}) => {
  const admin = await apiAs(ADMIN);
  const team = await seedTeam(admin, { name: unique("Public"), key: uniqueKey() });

  const shown = await seedTicket(admin, { teamId: team.id, title: unique("Sub-tickets, two levels") });
  const hidden = await seedTicket(admin, { teamId: team.id, title: unique("The acquisition") });

  /** `/api/public/roadmap/KAN/142`, the two-segment shape `/api/tickets/by-key` uses. */
  const route = (identifier: string) =>
    `/api/public/roadmap/${team.key}/${identifier.slice(identifier.lastIndexOf("-") + 1)}`;

  // Publishing is a scoped write: the same permission as moving the ticket.
  const published = await admin.patch(`/api/tickets/${shown.id}/publication`, {
    data: { public: true },
  });
  expect(published.ok(), "an admin may publish a ticket").toBeTruthy();
  publishedTicket = shown.id;

  const pointers = await admin.put(`/api/tickets/${shown.id}/where-to-look`, {
    data: { files: [{ path: "apps/web/src/components/publik/shell.tsx", note: "the chrome" }] },
  });
  expect(pointers.ok(), "and may say where to look").toBeTruthy();

  /**
   * The drawing's badges, and the label the page narrows its list to.
   *
   * `good first step` is matched by name across the instance, so defining it here is what
   * turns the eyebrow from `Unclaimed · N` into `Good first step · N`. The second label
   * goes on the *private* ticket: a stranger may not learn its title, and must not learn
   * what it was filed under either.
   */
  const label = async (name: string) => {
    const made = await admin.post(`/api/teams/${team.id}/labels`, { data: { name } });
    expect(made.ok(), `a maintainer may define ${name}`).toBeTruthy();
    return ((await made.json()) as { id: string }).id;
  };
  const firstStep = await label("good first step");
  const secret = await label("project-cormorant");

  for (const [ticketId, labelIds] of [
    [shown.id, [firstStep]],
    [hidden.id, [firstStep, secret]],
  ] as const) {
    const marked = await admin.put(`/api/tickets/${ticketId}/labels`, { data: labelIds });
    expect(marked.ok(), "labels are a scoped write on the ticket").toBeTruthy();
  }

  // --- the API, with no credentials whatsoever --------------------------------
  const anonymous = await playwrightRequest.newContext({ baseURL: API_URL });
  try {
    /**
     * The control, and it cannot be the obvious one.
     *
     * This scenario originally asserted that `GET /api/tickets` 401s without credentials,
     * so that everything below could not pass for the wrong reason. It does not: the suite
     * runs the stack in `KANSO_AUTH_MODE=dev`, and `DevAuthenticationFilter` falls back to
     * `dev@kanso.local` when the header is absent — it provisions that user on the spot.
     * In dev mode *no* request is anonymous, so no assertion made from here can prove a
     * route needs a session. `PublicLeakTest` is where that is proved, in process, against
     * the real filter chain.
     *
     * What this asserts instead is that the mode is what we think it is: a header-less
     * caller is answered, and answered as the fallback identity rather than as the admin
     * who published the ticket. That makes the content assertions below meaningful — the
     * public projection has to exclude an unpublished ticket *even from a caller the
     * filter chain considers signed in*, which is a stronger statement than excluding it
     * from a caller it refuses outright.
     */
    const control = await anonymous.get("/api/me");
    expect(control.status(), "dev mode answers a header-less caller").toBe(200);
    const fallback = (await control.json()) as { user: { email: string } };
    expect(fallback.user.email, "and answers as the fallback identity, not as the admin").not.toBe(
      ADMIN,
    );

    const roadmap = await anonymous.get("/api/public/roadmap");
    expect(roadmap.status(), "the roadmap answers a stranger").toBe(200);
    const body = await roadmap.text();
    expect(body).toContain(shown.title);
    expect(body, "a ticket nobody published must not be in the window").not.toContain(hidden.title);
    expect(body, "and no address of anybody's may be either").not.toContain("@");

    const page = await anonymous.get(route(shown.identifier));
    expect(page.status()).toBe(200);
    const contributor = await page.text();
    expect(contributor, "the badge is the label's own name").toContain("good first step");
    expect(
      contributor,
      "and a label a private ticket wears is as private as its title",
    ).not.toContain("project-cormorant");

    // A 404 rather than a 403 for the private one: 403 confirms it exists.
    const denied = await anonymous.get(route(hidden.identifier));
    expect(denied.status(), "an unpublished key is indistinguishable from a missing one").toBe(404);

    // Voting: anonymous, state-changing, and idempotent.
    const first = await anonymous.post(`${route(shown.identifier)}/vote`);
    expect(first.status()).toBe(200);
    const firstCount = ((await first.json()) as { votes: number }).votes;
    const again = await anonymous.post(`${route(shown.identifier)}/vote`);
    expect(((await again.json()) as { votes: number }).votes, "a second click is a no-op").toBe(
      firstCount,
    );

    const votingPrivate = await anonymous.post(`${route(hidden.identifier)}/vote`);
    expect(votingPrivate.status(), "nor may a stranger vote a private ticket up the list").toBe(404);
  } finally {
    await anonymous.dispose();
  }

  // --- the pages, in a browser that has never signed in ----------------------
  const context = await browser.newContext({ baseURL: WEB_URL });
  try {
    const visitor = await context.newPage();

    await visitor.goto("/roadmap");
    await expect(visitor.getByRole("heading", { level: 1, name: "What we are working on" })).toBeVisible();
    await expect(visitor.getByRole("link", { name: shown.title })).toBeVisible();
    await expect(visitor.getByText(hidden.title)).toHaveCount(0);
    /**
     * The column heading is a **category** since `KAN-90` — `CATEGORY_LABELS.unstarted`,
     * which reads `Not started`. `seedTicket` files into `todo`, and the roadmap draws one
     * column per non-empty category.
     *
     * It asserted `/Todo/` before, on the rule that "the statuses are the application's
     * own; nothing is reworded for the shop window". That rule held while every team read
     * the same six words. This page is every published ticket in the instance, so the
     * words would give it one column per word per team — `Done` beside `Livré`, meaning
     * the same thing. `RoadmapGroup` on the server is where the argument is written. Each
     * *card* still carries its own team's word, so nothing is reworded where a reader is
     * looking at one ticket.
     */
    await expect(visitor.getByRole("heading", { level: 2, name: /Not started/ })).toBeVisible();

    // The app's own chrome is not here. A stranger has no scope to pick and no palette.
    await expect(visitor.getByTestId("nav-item")).toHaveCount(0);

    // Voting from the page, and the control staying pressed across a reload.
    const voteControl = visitor.getByRole("button", { name: `Vote for ${shown.identifier}` });
    await expect(voteControl).toHaveAttribute("aria-pressed", "false");
    await voteControl.click();
    await expect(voteControl).toHaveAttribute("aria-pressed", "true");
    await visitor.reload();
    await expect(
      visitor.getByRole("button", { name: `Vote for ${shown.identifier}` }),
    ).toHaveAttribute("aria-pressed", "true");

    // Screen 28, reached from the roadmap.
    await visitor.getByRole("link", { name: shown.title }).click();
    await expect(visitor.getByRole("heading", { level: 1, name: shown.title })).toBeVisible();
    await expect(visitor.getByText("apps/web/src/components/publik/shell.tsx")).toBeVisible();
    /**
     * Four badges, as the drawing has them: the status, the ticket's labels, and `nobody on
     * it` last — which is not a label but `ticket_assignees` being empty. The eyebrow says
     * `Good first step` rather than `Unclaimed` only because the label above exists; the
     * count is left loose because every published ticket wearing it on this instance is in
     * it, which is the honest reading and not a number this scenario owns.
     */
    await expect(visitor.getByText("good first step").first()).toBeVisible();
    await expect(visitor.getByText(/good first step · \d+ available/)).toBeVisible();
    await expect(visitor.getByText("nobody on it")).toBeVisible();
    await expect(
      visitor.getByText(/This ticket is visible because it is marked public/),
    ).toBeVisible();

    // A private key typed into the address bar says the same thing a missing one does.
    await visitor.goto(`/roadmap/${hidden.identifier}`);
    await expect(visitor.getByRole("heading", { level: 1, name: "Not published" })).toBeVisible();

    // The landing page is /about; / stays the application.
    await visitor.goto("/about");
    await expect(
      visitor.getByRole("heading", { level: 1, name: /The board and the page/ }),
    ).toBeVisible();
    await expect(visitor.getByText("docker compose up kanso")).toBeVisible();
    await expect(visitor.getByText(/AGPL-3\.0/).first()).toBeVisible();
  } finally {
    await context.close();
  }
});
