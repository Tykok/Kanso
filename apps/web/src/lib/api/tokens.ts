/**
 * API tokens, from the member's side of them.
 *
 * Its own slice beside `oauth.ts` rather than a section of it, because the two are
 * deliberately different doors and `V27__api_tokens.sql` is emphatic about it: an OAuth
 * grant begins with a person on a consent screen, and a token is for the thing that has no
 * browser to show one in. A token also does not work on `/api/mcp` at all. Folding the two
 * clients together would put those facts one property away from each other.
 *
 * Four calls: what I have, what I could ask for, make one, take one back.
 */

import { request } from "./core";

/**
 * A token as it can ever be read back, shaped by `dev.kanso.tokens.ApiTokenResponse`.
 *
 * There is no `secret` here and no optional one standing in for it — the server's own type
 * has no such field either, so a listing cannot serialise a plaintext it does not hold.
 * [NewApiToken] is the one shape that carries one.
 */
export type ApiToken = {
  id: string;
  name: string;
  /** `kanso_pat_AbCdEf` — the first six characters, enough to recognise, not to use. */
  prefix: string;
  scopes: string[];
  /** The server's sentences for `scopes`, the same ones the consent screen prints. */
  scopeProse: string[];
  /**
   * Never used, which is a different fact from used long ago — and **optional, because
   * the key is absent rather than null.**
   *
   * The shared Jackson mapper omits nulls, the same behaviour `ActivityService` records
   * for an activity payload: a token that has never been presented comes back with no
   * `lastUsedAt` at all. Typing this `string | null` is what a reader expects from the
   * Kotlin `OffsetDateTime?` and it is a claim about the wire that is not true, so
   * `=== null` never matches and the never-used branch never runs. Found in a browser as
   * `Last used Invalid Date`, which is `new Date(undefined)` — and no amount of `tsc`
   * would have found it, because the lie was in this very declaration.
   */
  lastUsedAt?: string | null;
  createdAt: string;
};

/**
 * The answer to a creation, and the only payload in this API that carries a live secret.
 *
 * [warning] is the server's own sentence about there being no second chance to copy it.
 * Rendered rather than rewritten here for the reason `GrantSummary`'s prose is: the wording
 * that tells somebody a secret is about to be unrecoverable should have one copy, and the
 * server already has to hold it for the callers that have no screen at all.
 */
export type NewApiToken = {
  token: ApiToken;
  secret: string;
  warning: string;
};

/** One scope on offer, shaped by `dev.kanso.tokens.ApiTokenScopeChoice`. */
export type ApiTokenScopeChoice = { scope: string; prose: string };

/**
 * "Never used", and not an empty cell, a formatted epoch, or an `Invalid Date`.
 *
 * A token has no `last_used_at` until it is presented for the first time, and that absence
 * is precisely the answer the column exists to give — "has anything ever picked this up" is
 * what somebody asks before revoking a row they do not recognise.
 *
 * The guard is falsy rather than `=== null` on purpose, and it is the fix for a real bug
 * rather than defensiveness: the server sends **no key at all** for an unused token, so the
 * value here is `undefined`, and a strict null check hands `undefined` to `new Date`. All
 * three of the wrong answers were reachable from one missing key — an empty cell reads as
 * data that failed to load, `new Date(null)` is 1 January 1970, and `new Date(undefined)`
 * is `Invalid Date`, which is the one this screen actually printed.
 */
export const lastUsedLabel = (lastUsedAt?: string | null): string =>
  !lastUsedAt ? "Never used" : `Last used ${new Date(lastUsedAt).toLocaleString()}`;

export const tokensApi = {
  list: () => request<ApiToken[]>("/api/me/tokens"),
  scopes: () => request<ApiTokenScopeChoice[]>("/api/me/tokens/scopes"),
  create: (body: { name: string; scopes: string[] }) =>
    request<NewApiToken>("/api/me/tokens", { method: "POST", body: JSON.stringify(body) }),
  /**
   * Encoded for the reason `revokeGrant` gives about a client id: this is a path segment
   * built from a value the server chose, and a URL assembled by concatenation is one
   * malformed id away from addressing something else.
   */
  revoke: (id: string) =>
    request<void>(`/api/me/tokens/${encodeURIComponent(id)}`, { method: "DELETE" }),
};
