/**
 * The OAuth door, from the member's side of it.
 *
 * One screen, two calls: what holds a grant on my account, and take one away. Nothing
 * about the flow itself is here — registration, consent and tokens all happen between an
 * agent and the API, with no browser of ours involved — so this slice is only ever the
 * aftermath.
 */

import { apiOrigin, request } from "./core";

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
  /**
   * How many permissions the grant carries that this version of Kanso has no sentence
   * for. A count and not the strings: the server refuses to send an unrecognised scope
   * name, so there is no stranger's text arriving here to render.
   */
  unrecognisedScopes: number;
  grantedAt: string;
};

/**
 * What to say about permissions the server could not name, or nothing at all.
 *
 * Said rather than swallowed. A row listing one permission when the grant holds two has
 * told a member something false about their own account, and this is the screen they
 * would have used to check. The sentence ends in the only action available — Revoke is
 * the button already beside it — because "something is here that I cannot explain" is
 * only useful next to a way out.
 */
export const unrecognisedScopeNote = (count: number): string | null => {
  if (count <= 0) return null;
  const permissions = count === 1 ? "1 further permission" : `${count} further permissions`;
  return (
    `${permissions} this version of Kanso cannot name — likely granted by a different ` +
    "version. Revoke the application if you did not expect it."
  );
};

/**
 * The one command that connects an agent, printed so it can be copied rather than typed.
 *
 * Built from [apiOrigin] and not from `API_URL`: a published image inlines nothing, so
 * the constant is the empty string and the command would read `kanso /api/mcp` — a line
 * the shell accepts and the agent cannot resolve, run somewhere Kanso's origin means
 * nothing. The default is a call rather than a constant so `next build` never reaches
 * `window` while prerendering this module's importers; `agents-section` passes
 * `useApiOrigin()` because the value it gets is drawn into the HTML.
 *
 * `/api/mcp` is `dev.kanso.mcp.McpResource.PATH`, which is also the string every issued
 * token is bound to, so it is not free to differ.
 */
export const mcpAddCommand = (apiUrl: string = apiOrigin()): string =>
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
