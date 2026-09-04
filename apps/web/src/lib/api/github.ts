/**
 * A member's own GitHub link — the second consent, and the one that puts a name where the
 * feed otherwise says *via #418*.
 *
 * Its own slice rather than a section of `core.ts`, following what `tokens.ts` argues:
 * this is a *personal* grant, and the GitHub App's instance-wide configuration is a
 * different door with a different audience. Folding them together would put "an owner
 * configured an App" one property away from "I consented", which are not the same fact
 * and not the same person's business.
 *
 * Four calls: what I have, start asking, stop, and — for an owner — the App to ask
 * through.
 */

import { request } from "./core";

/**
 * What can still be done with a linked member's token, closed on the server by
 * `GithubTokenState`.
 *
 * Four words because GitHub issues two shapes of token and `V36` stores both: an App that
 * did not opt into expiring tokens issues one that never expires, and one that did issues
 * a refresh token beside it. `expired` is the third, undocumented shape — an expiry with
 * nothing to renew it — which the schema admits and this vocabulary therefore names.
 */
export const GITHUB_TOKEN_STATES = ["permanent", "active", "refreshable", "expired"] as const;
export type GithubTokenState = (typeof GITHUB_TOKEN_STATES)[number];

/**
 * A member's link, shaped by `dev.kanso.github.GithubLinkResponse`.
 *
 * **`linked` is the only field that is always there, and everything about the link is
 * optional.** The server's mapper omits nulls, so an unlinked member's response carries
 * `login` not at all rather than as `null` — the trap `tokens.ts` records at length after
 * it produced a "Last used Invalid Date" on screen. So: one boolean to branch on, and
 * `?` on everything it gates. Never `| null`.
 *
 * `appConfigured` is a different question from `linked` and the two are deliberately
 * separate, the distinction `NotionSettingsState` already draws: an App makes the button
 * *possible*, consent makes the name *appear*, and an instance can have the first without
 * the second for as long as it likes. Conflating them would leave the screen unable to
 * tell "nobody has set up GitHub" from "set up, and I have not consented".
 */
export type GithubLink = {
  appConfigured: boolean;
  appManagedByEnvironment: boolean;
  linked: boolean;
  /** `tykok`, without the `@`. Absent when `linked` is false. */
  login?: string;
  tokenState?: GithubTokenState;
  /** Absent for a token GitHub issued with no expiry, which is a real shape and not an error. */
  expiresAt?: string;
  linkedAt?: string;
};

export const githubApi = {
  link: () => request<GithubLink>("/api/github/link"),

  /**
   * Answers with a URL rather than following a redirect, and the reason is the same one
   * `startNotionConnect` carries: a 302 from an endpoint called by `fetch` is followed by
   * `fetch`, not by the window, and arrives as an opaque CORS failure. The browser has to
   * navigate itself — `window.location.assign(url)`.
   */
  startLink: () =>
    request<{ url: string; redirectUri: string }>("/api/github/link/authorize", {
      method: "POST",
    }),

  unlink: () => request<void>("/api/github/link", { method: "DELETE" }),

  /**
   * The App's OAuth client, for an owner. Saving it connects nothing and unlinks nobody —
   * it is what makes consent *askable*.
   */
  saveApp: (body: { clientId: string; clientSecret?: string }) =>
    request<GithubLink>("/api/github/app", { method: "POST", body: JSON.stringify(body) }),
};
