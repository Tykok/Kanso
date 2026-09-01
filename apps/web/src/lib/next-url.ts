/** Where a member goes when the return address cannot be trusted. */
const HOME = "/";

/**
 * The `?next=` a sign-in may honour, and nothing else.
 *
 * The API serves the OAuth consent screen on its own origin — the session cookie is
 * `SameSite=Lax`, so the decision has to be posted first-party — and an anonymous visitor
 * reaching it is sent here to sign in with the way back in `next`. That value is
 * reflected into a redirect immediately after a successful login, which makes it the one
 * open-redirect surface in the app and the one a member is least able to check: they have
 * just typed their password, so wherever they land next reads as Kanso's.
 *
 * Two shapes pass, and they are the two the flow actually produces: a path inside this
 * app, or an absolute URL whose **parsed origin** equals the API's. Parsed, never a
 * prefix — `https://api.example.com.evil.com/` starts with the API's origin as text, and
 * `https://api.example.com@evil.com/` reads as it to a person. `URL` answers both in one
 * comparison, because an origin is scheme, host and port together.
 *
 * Its own module rather than a helper inside the page, because vitest here collects only
 * `.test.ts` files: a check living in a `.tsx` component could not be tested at all, and
 * this is the last function in the app that should go untested.
 */
export function safeNext(raw: string | null | undefined, apiOrigin: string): string {
  if (!raw) return HOME;

  // Browsers strip tab and newline out of a URL before resolving it, so `/<tab>/evil.com`
  // navigates to `//evil.com`. Stripping them here means this function reads the same
  // string the browser will.
  const value = raw.replace(/[\t\n\r]/g, "");

  // A single leading slash and no backslash behind it: `//evil.com` is protocol-relative,
  // and `/\evil.com` is the same thing to any browser that normalises the backslash.
  if (value.startsWith("/")) {
    return value.startsWith("//") || value.startsWith("/\\") ? HOME : value;
  }

  try {
    const target = new URL(value);
    // A misconfigured API origin must not open the redirect this function exists to
    // close, so it is parsed inside the same `try` and refused the same way.
    if (target.origin !== new URL(apiOrigin).origin) return HOME;
    // `javascript:` and friends parse, and their origin is the string "null" — which no
    // http(s) origin can equal, so the comparison above has already refused them.
    return target.toString();
  } catch {
    return HOME;
  }
}
