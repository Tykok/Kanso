/**
 * The OAuth door, from the member's side of it.
 *
 * One screen, two calls: what holds a grant on my account, and take one away. Nothing
 * about the flow itself is here — registration, consent and tokens all happen between an
 * agent and the API, with no browser of ours involved — so this slice is only ever the
 * aftermath.
 */

import { API_URL, request } from "./core";

/**
 * One connected application, shaped by `dev.kanso.oauth.GrantSummary`.
 *
 * `scopes` and `scopeProse` are the same permissions twice. The prose is the server's,
 * written in `OAuthScopes` and shown on the consent screen — a member reads the sentence
 * they agreed to rather than a sentence a component invented, and there is no second copy
 * of that wording here to fall out of step with the first.
 */
export type Grant = {
  /** What the member's own agent configuration calls this client. */
  clientId: string;
  clientName: string;
  scopes: string[];
  scopeProse: string[];
  grantedAt: string;
};

/**
 * The one command that connects an agent, printed so it can be copied rather than typed.
 *
 * Built from `API_URL` and not from `location.origin`: the browser is on the web app,
 * and the door is on the API — an instance where the two are different hosts is the
 * normal one, and a command naming the wrong half fails in a way nobody can debug from
 * the message. `/api/mcp` is `dev.kanso.mcp.McpResource.PATH`, which is also the string
 * every issued token is bound to, so it is not free to differ.
 */
export const mcpAddCommand = (apiUrl: string = API_URL): string =>
  `claude mcp add --transport http kanso ${apiUrl.replace(/\/+$/, "")}/api/mcp`;

export const oauthApi = {
  grants: () => request<Grant[]>("/api/oauth/grants"),
  /**
   * Encoded, because a client id is a string the server chose and this one is a path
   * segment — the day one contains a slash is not the day to find that out.
   */
  revokeGrant: (clientId: string) =>
    request<void>(`/api/oauth/grants/${encodeURIComponent(clientId)}`, { method: "DELETE" }),
};
