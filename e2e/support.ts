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
 */
export async function openAs(browser: Browser, email: string): Promise<Page> {
  const context = await browser.newContext({ baseURL: WEB_URL });
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

export async function seedProject(
  api: APIRequestContext,
  body: { name: string; teamId?: string },
): Promise<SeededProject> {
  const response = await api.post("/api/projects", { data: body });
  expect(response.ok(), `Could not create the project ${body.name}`).toBeTruthy();
  return (await response.json()) as SeededProject;
}

export async function seedTicket(
  api: APIRequestContext,
  body: { teamId: string; title: string; projectId?: string },
): Promise<SeededTicket> {
  const response = await api.post("/api/tickets", { data: body });
  expect(response.ok(), `Could not create the ticket ${body.title}`).toBeTruthy();
  return (await response.json()) as SeededTicket;
}

// --- interface handles -------------------------------------------------------

/** The sidebar row carrying this name, container included. */
export function sidebarRow(page: Page, name: string): Locator {
  return page.locator(".nav-item").filter({ has: page.getByRole("button", { name, exact: true }) });
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
  return page.locator(".row").filter({ hasText: title });
}
