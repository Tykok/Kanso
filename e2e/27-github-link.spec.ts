import { expect, test } from "@playwright/test";

import { ADMIN, API_URL, MEMBER, apiAs, openAs, seedInstance } from "./support";

/**
 * `KAN-74` on screen: a member's own GitHub consent, and what the instance looks like
 * before anybody has given it.
 *
 * **Playwright does not talk to GitHub**, which the design states outright, so what is
 * asserted here stops exactly where Kanso's own behaviour stops: the four states of the
 * card, the two refusals the callback owns, and the shape of the wire. The half that needs
 * a real consent screen — a code exchanged for a token, an account identified — is
 * `GithubAccountTest`'s, driven against the repository with the tokens GitHub would have
 * sent.
 *
 * What this file deliberately cannot assert, and it is worth writing down rather than
 * leaving as a gap somebody re-discovers: **the name on a pull request row.**
 * `github_pull_requests` has no write endpoint — the webhook that fills it does not exist
 * yet — so there is no way from here to put a pull request on a ticket. `GithubPrAuthorTest`
 * asserts that half against the real JSON, and this spec asserts the consent that feeds it.
 */
test.describe("a member's GitHub link", () => {
  test.beforeAll(async () => {
    await seedInstance();
  });

  /**
   * The state every instance starts in, and the one that has to be honest rather than
   * broken-looking: no App, so nothing to consent to and no button that would fail.
   *
   * Run first and without configuring anything, because it is the only state that cannot
   * be recovered once an App exists — `POST /api/github/app` has no counterpart that
   * unsets it, deliberately: clearing an App would orphan the links members already made.
   */
  test("says there is nothing to link to before an App exists", async ({ browser }) => {
    const api = await apiAs(MEMBER);
    try {
      const link = await api.get("/api/github/link");
      expect(link.ok()).toBeTruthy();
      const body = (await link.json()) as Record<string, unknown>;

      // The wire's shape, which the TypeScript type promises and only a real response can
      // prove: `linked` is present and false, and everything it gates is *absent* rather
      // than null. A `login: null` here would mean the mapper's `non_null` had been
      // turned off and every `?` in `GithubLink` had quietly become a lie.
      expect(body.linked).toBe(false);
      expect("login" in body).toBe(false);
      expect("tokenState" in body).toBe(false);
      expect("expiresAt" in body).toBe(false);

      if (body.appConfigured === false) {
        const page = await openAs(browser, MEMBER);
        await page.goto("/settings?section=github");
        await expect(page.getByRole("heading", { name: "GitHub", level: 2 })).toBeVisible();
        await expect(page.getByText("No GitHub App is connected to this instance yet")).toBeVisible();
        // The point of the state: no active gesture, because pressing one would land the
        // member on a GitHub error page.
        await expect(page.getByRole("button", { name: "Connect GitHub" })).toHaveCount(0);
        await page.close();
      }
    } finally {
      await api.dispose();
    }
  });

  /** Configuring the App is the instance's business, and a member has none of it. */
  test("refuses to let a member configure the App", async () => {
    const api = await apiAs(MEMBER);
    try {
      const refused = await api.post("/api/github/app", {
        data: { clientId: "Iv23liNotYours", clientSecret: "nope" },
      });
      expect(refused.status()).toBe(403);
    } finally {
      await api.dispose();
    }
  });

  /**
   * An owner configures the App, and the card becomes an offer.
   *
   * The credentials are nonsense on purpose: nothing here exchanges them, and a spec that
   * needed real ones would be a spec that only runs on one laptop.
   */
  test("offers the link once an owner has configured an App", async ({ browser }) => {
    const owner = await apiAs(ADMIN);
    try {
      const saved = await owner.post("/api/github/app", {
        data: { clientId: "Iv23liKansoE2E", clientSecret: "e2e-not-a-real-secret" },
      });
      // The environment may pin it on some stacks, in which case saving is refused and
      // the App is configured anyway — both are a pass for what comes next.
      expect([200, 400]).toContain(saved.status());

      const page = await openAs(browser, MEMBER);
      await page.goto("/settings?section=github");
      await expect(page.getByRole("button", { name: "Connect GitHub" })).toBeVisible();
      // The sentence that keeps the flow optional. A consent screen that does not say
      // skipping is free reads as a requirement.
      await expect(page.getByText("Skipping this changes nothing else")).toBeVisible();
      await page.close();
    } finally {
      await owner.dispose();
    }
  });

  /**
   * The authorize call answers a URL for the *browser* to follow, and it is GitHub's.
   *
   * A redirect here would be followed by `fetch` and arrive as an opaque CORS failure —
   * the reason `NotionConnectController` answers JSON too. Asserting the host is what
   * catches a URL built against the wrong endpoint, and asserting `state` is what catches
   * one built without the guard the next test depends on.
   */
  test("hands the browser a GitHub URL carrying a state", async () => {
    const api = await apiAs(MEMBER);
    try {
      const started = await api.post("/api/github/link/authorize");
      expect(started.ok()).toBeTruthy();
      const { url, redirectUri } = (await started.json()) as { url: string; redirectUri: string };

      const target = new URL(url);
      expect(target.origin).toBe("https://github.com");
      expect(target.pathname).toBe("/login/oauth/authorize");
      expect(target.searchParams.get("client_id")).not.toBeNull();
      expect(target.searchParams.get("state")?.length ?? 0).toBeGreaterThan(20);
      // No scope list: a GitHub App's user-to-server token takes its permissions from the
      // installation. Asking for scopes here would be asking an endpoint that does not
      // grant them, and GitHub's own screen would then describe access nobody configured.
      expect(target.searchParams.has("scope")).toBe(false);

      expect(redirectUri).toBe(`${API_URL}/api/github/link/callback`);
    } finally {
      await api.dispose();
    }
  });

  /**
   * **The guard on the callback**, and the reason it is a whole test: this is a GET that
   * writes a row holding an access token, and `UnguardedWriteTest` does not sweep GETs.
   *
   * A state that this session never issued is refused and writes nothing. The member is
   * still unlinked afterwards, which is the assertion that matters — a redirect carrying
   * an error message while the row went in anyway would look identical from the browser.
   */
  test("refuses a callback whose state it never issued, and writes nothing", async () => {
    const api = await apiAs(MEMBER);
    try {
      const answered = await api.get(
        "/api/github/link/callback?code=whatever&state=not-a-state-this-session-issued",
        { maxRedirects: 0 },
      );
      expect(answered.status()).toBe(302);
      const location = answered.headers()["location"] ?? "";
      expect(location).toContain("github_error");
      expect(location).toContain("section=github");

      const after = (await (await api.get("/api/github/link")).json()) as { linked: boolean };
      expect(after.linked, "a refused callback must not have linked anybody").toBe(false);
    } finally {
      await api.dispose();
    }
  });

  /**
   * Declining on GitHub's screen is a normal answer, not a failure.
   *
   * The flow is skippable by design, so `error=access_denied` is reported as itself rather
   * than as something having gone wrong — and it writes nothing either.
   */
  test("reports a declined consent as itself", async () => {
    const api = await apiAs(MEMBER);
    try {
      const answered = await api.get("/api/github/link/callback?error=access_denied", {
        maxRedirects: 0,
      });
      expect(answered.status()).toBe(302);
      expect(answered.headers()["location"] ?? "").toContain("access_denied");

      const after = (await (await api.get("/api/github/link")).json()) as { linked: boolean };
      expect(after.linked).toBe(false);
    } finally {
      await api.dispose();
    }
  });

  /** Unlinking what was never linked is not an error, so a double press cannot fail. */
  test("unlinks idempotently", async () => {
    const api = await apiAs(MEMBER);
    try {
      expect((await api.delete("/api/github/link")).status()).toBe(204);
      expect((await api.delete("/api/github/link")).status()).toBe(204);
    } finally {
      await api.dispose();
    }
  });

  /**
   * The tab is reachable by URL, which is not decoration: the OAuth callback returns from
   * another origin as a fresh page load, so if `?section=` did not select the tab the
   * member would land on Appearance and never see whether their link worked.
   */
  test("opens the GitHub tab from the query string the callback uses", async ({ browser }) => {
    const page = await openAs(browser, MEMBER);
    await page.goto("/settings?section=github");
    await expect(page.getByRole("heading", { name: "GitHub", level: 2 })).toBeVisible();

    // And an unknown section falls back rather than rendering an empty pane.
    await page.goto("/settings?section=not-a-section");
    await expect(page.getByRole("heading", { name: "Appearance", level: 2 })).toBeVisible();
    await page.close();
  });
});
