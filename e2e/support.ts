import {
  expect,
  request as playwrightRequest,
  type APIRequestContext,
  type Browser,
  type Locator,
  type Page,
} from "@playwright/test";

export const API_URL = process.env.KANSO_API_URL ?? "http://localhost:8080";
export const WEB_URL = process.env.KANSO_WEB_URL ?? "http://localhost:3000";

export const ADMIN = "owner@kanso.test";
export const MEMBER = "member@kanso.test";

/** The owner's password. It only ever serves to claim a fresh instance. */
const OWNER_PASSWORD = "kanso-e2e-owner-password";

/** Unique per call, so re-running the suite against the same database stays safe. */
export function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.random().toString(36).slice(2, 5)}`;
}

/** `@Size(min = 2, max = 8)` on `TeamRequest.key`: five characters fit. */
export function uniqueKey(): string {
  return `E${Math.random().toString(36).slice(2, 6).toUpperCase()}`;
}

/**
 * An HTTP context whose identity comes from the header alone. Every caller gets its
 * own: a login response sets a session cookie, and the `dev` mode filter only kicks
 * in when there is no authentication at all — sharing one context would silently make
 * everybody act as the first identity used.
 */
export async function apiAs(email: string): Promise<APIRequestContext> {
  return playwrightRequest.newContext({
    baseURL: API_URL,
    extraHTTPHeaders: { "X-Kanso-User": email },
  });
}

/**
 * This person's own id, off `GET /api/me` answered as them.
 *
 * A context of its own rather than a parameter borrowed from the caller's: `/api/me`
 * answers for whoever the header names, so reusing a context bound to a different
 * identity would report the wrong person's id with no error to say so.
 */
export async function userIdOf(email: string): Promise<string> {
  const api = await apiAs(email);
  try {
    const response = await api.get("/api/me");
    expect(response.ok(), `Could not read /api/me as ${email}`).toBeTruthy();
    const body = (await response.json()) as { user: { id: string } };
    return body.user.id;
  } finally {
    await api.dispose();
  }
}

/**
 * Brings the instance to the minimum state in which the application agrees to show a
 * list: an owner exists, and both test accounts have been through the preferences
 * step. Without the second point, every test would land on `/setup`. Idempotent: the
 * stack is long-lived and the suite is replayed against it.
 */
export async function seedInstance(): Promise<void> {
  const anonymous = await playwrightRequest.newContext({ baseURL: API_URL });
  try {
    const state = await anonymous.get("/api/setup/state");
    expect(state.ok(), `No answer from the API at ${API_URL}. Is the stack up?`).toBeTruthy();
    const body = (await state.json()) as { needsOwner: boolean };
    if (body.needsOwner) {
      const claimed = await anonymous.post("/api/setup/owner", {
        data: { email: ADMIN, displayName: "E2E owner", password: OWNER_PASSWORD },
      });
      expect(claimed.ok(), "Could not claim the instance").toBeTruthy();
    }
  } finally {
    await anonymous.dispose();
  }

  for (const email of [ADMIN, MEMBER]) {
    const api = await apiAs(email);
    try {
      const saved = await api.put("/api/me/preferences", { data: { onboarded: true } });
      expect(saved.ok(), `Could not onboard ${email}`).toBeTruthy();
    } finally {
      await api.dispose();
    }
  }
}

/**
 * A page that acts as somebody.
 *
 * The client reads its identity out of `localStorage` on every request, so it has to
 * be there before the first script runs. `addInitScript` also replays on every
 * navigation, which a one-off `evaluate` would not. `baseURL` is repeated here: a
 * context created by hand does not inherit the configuration's `use` options.
 *
 * `viewport` is optional and defaults to Playwright's own — every scenario before
 * the mobile drawer wanted the desktop shell, and this keeps them asking for
 * nothing extra. A scenario that needs the under-720px layout passes its own.
 */
export async function openAs(
  browser: Browser,
  email: string,
  options?: { viewport?: { width: number; height: number } },
): Promise<Page> {
  const context = await browser.newContext({
    baseURL: WEB_URL,
    ...(options?.viewport ? { viewport: options.viewport } : {}),
  });
  await context.addInitScript((who: string) => {
    window.localStorage.setItem("kanso.devUser", who);
  }, email);
  const page = await context.newPage();
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
  return page;
}

// --- API seeding shortcuts ---------------------------------------------------
// Scenarios 2 to 5 only need a stage; scenario 1 alone has to build it with the
// mouse, since it is the one testing the creation paths.

export type SeededTeam = { id: string; name: string; key: string };
export type SeededProject = { id: string; name: string; teamId?: string };
export type SeededTicket = { id: string; identifier: string; title: string };

export async function seedTeam(
  api: APIRequestContext,
  body: { name: string; key: string; parentTeamId?: string },
): Promise<SeededTeam> {
  const response = await api.post("/api/teams", { data: body });
  expect(response.ok(), `Could not create the team ${body.name}`).toBeTruthy();
  return (await response.json()) as SeededTeam;
}

/** Puts a user in a team. Admin-only on the server, so call it with an ADMIN context. */
export async function seedMember(
  api: APIRequestContext,
  teamId: string,
  userId: string,
): Promise<void> {
  const response = await api.post(`/api/teams/${teamId}/members`, {
    data: { userId, role: "member" },
  });
  if (!response.ok()) throw new Error(`seedMember: ${response.status()} ${await response.text()}`);
}

export async function seedProject(
  api: APIRequestContext,
  body: { name: string; teamId?: string },
): Promise<SeededProject> {
  const response = await api.post("/api/projects", { data: body });
  expect(response.ok(), `Could not create the project ${body.name}`).toBeTruthy();
  return (await response.json()) as SeededProject;
}

/**
 * A `YYYY-MM-DD` day as the API stores one: an instant that carries no time.
 *
 * `hasTime: false` is the whole point — a bound with a time is a moment and is
 * converted into the reader's zone, and a plan made of moments moves a column sideways
 * for anyone west of UTC on dates nobody touched.
 */
export const floatingDay = (day: string) => ({ at: `${day}T00:00:00Z`, hasTime: false });

export async function seedTicket(
  api: APIRequestContext,
  body: {
    teamId: string;
    title: string;
    projectId?: string;
    /**
     * `YYYY-MM-DD`, both optional and both floating.
     *
     * Seeded rather than typed in, unlike everything the timeline scenario then does
     * with the mouse. The interface has no way to give a ticket a *start*: the detail
     * panel offers a due date and nothing else, and the only other path is the timeline
     * itself — the very thing under test. Building the stage through the screen would
     * mean proving the drop gesture works by using the drop gesture.
     */
    start?: string;
    due?: string;
  },
): Promise<SeededTicket> {
  const { start, due, ...rest } = body;
  const response = await api.post("/api/tickets", {
    data: {
      ...rest,
      ...(start ? { start: floatingDay(start) } : {}),
      ...(due ? { due: floatingDay(due) } : {}),
    },
  });
  expect(response.ok(), `Could not create the ticket ${body.title}`).toBeTruthy();
  return (await response.json()) as SeededTicket;
}

// --- interface handles -------------------------------------------------------

/** The sidebar row carrying this name, container included. */
export function sidebarRow(page: Page, name: string): Locator {
  return page.getByTestId("nav-item").filter({ has: page.getByRole("button", { name, exact: true }) });
}

/**
 * The Views group's link to a route — `Documents`, `Trash`, `Workload`.
 *
 * Scoped to a `nav-item` rather than asked of the page, because the word is not the
 * column's alone: a document's own bar carried a second `Documents` link until it was
 * folded into the shell's trail, and a page that names a route in its copy would put a
 * third one on screen. `sidebarRow` cannot serve here — it filters on a `button`, which
 * is `All tickets` and the scope rows, and every route row is a `Link`.
 */
export function navLink(page: Page, name: string): Locator {
  return page
    .getByTestId("nav-item")
    .getByRole("link", { name, exact: true });
}

/**
 * The List / Board / Timeline strip's button, by its own name.
 *
 * Scoped to the strip, and `exact`, both deliberately. `getByRole("button", { name:
 * "Board" })` over the page matched three elements on a database this suite had already
 * run against: a team `17-views` seeds is called `Board-<suffix>`, so a non-`exact` name
 * matches the row *and* its `⋯`, and the view button is only the third. That made the
 * failure depend on which files had run first — green for the file's author, red in the
 * suite — which is why the fix is a scope and a name rather than a `.first()`.
 */
export function viewButton(page: Page, name: "List" | "Board" | "Timeline"): Locator {
  return page.getByRole("group", { name: "View" }).getByRole("button", { name, exact: true });
}

/** Opens a row's `⋯` menu and returns the open menu. */
export async function openRowMenu(page: Page, name: string): Promise<Locator> {
  await page.getByRole("button", { name: `Actions for ${name}`, exact: true }).click();
  const menu = page.getByRole("menu", { name: `Actions for ${name}` });
  await expect(menu).toBeVisible();
  return menu;
}

/** The ticket row carrying this title. */
export function ticketRow(page: Page, title: string): Locator {
  return page.getByTestId("ticket-row").filter({ hasText: title });
}
