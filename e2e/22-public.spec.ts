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

  const pointers = await admin.put(`/api/tickets/${shown.id}/where-to-look`, {
    data: { files: [{ path: "apps/web/src/components/publik/shell.tsx", note: "the chrome" }] },
  });
  expect(pointers.ok(), "and may say where to look").toBeTruthy();

  // --- the API, with no credentials whatsoever --------------------------------
  const anonymous = await playwrightRequest.newContext({ baseURL: API_URL });
  try {
    // The control: this instance runs in dev mode, where identity comes from a header.
    // Without one, a private route has to refuse — if it does not, everything below
    // passes for the wrong reason.
    const refused = await anonymous.get("/api/tickets");
    expect(
      refused.status(),
      "the ordinary tickets endpoint must still need a session",
    ).toBe(401);

    const roadmap = await anonymous.get("/api/public/roadmap");
    expect(roadmap.status(), "the roadmap answers a stranger").toBe(200);
    const body = await roadmap.text();
    expect(body).toContain(shown.title);
    expect(body, "a ticket nobody published must not be in the window").not.toContain(hidden.title);
    expect(body, "and no address of anybody's may be either").not.toContain("@");

    const page = await anonymous.get(route(shown.identifier));
    expect(page.status()).toBe(200);

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
    // The column heading is the application's own label, not a word invented for the
    // shop window: `STATUS_LABELS.backlog`.
    await expect(visitor.getByRole("heading", { level: 2, name: /Backlog/ })).toBeVisible();

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
