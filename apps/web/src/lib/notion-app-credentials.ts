/**
 * Reading the two values a Notion public integration hands out.
 *
 * Notion, unlike Google Cloud, offers no downloadable client file — so the paste this
 * accepts is whatever a person actually has in the clipboard after visiting the
 * integration page: the authorization URL Notion prints there, the labelled block
 * around the two secrets, or the pair as JSON out of a password manager. All three
 * carry the same two facts, and transcribing either of them by hand is the step that
 * goes wrong without anything noticing until a consent screen refuses.
 *
 * Client-side only, like `google-client-file`: the paste exists to fill two fields,
 * and the server has nothing to do with it that those two fields do not say.
 */

export type NotionAppCredentials = {
  clientId: string;
  /** "" when the paste carried no secret; the id is still worth filling in. */
  clientSecret: string;
  /** `null` means the paste did not say — which is not the same as an empty list. */
  redirectUris: string[] | null;
};

/** Notion issues the client id as a UUID. */
const CLIENT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/**
 * The OAuth client secret. Deliberately narrow: an *internal* integration token is
 * the thing this screen replaced, and one pasted here has to fall through as "no
 * secret found" rather than be saved as a client secret — the alternative is Notion
 * refusing the exchange much later, in words about OAuth, to somebody who pasted
 * what Notion itself called a token.
 */
const CLIENT_SECRET = /^secret_[A-Za-z0-9_-]{16,}$/;

/** Splits a blob into candidate values, whether it arrived as JSON, lines, or prose. */
function valuesIn(text: string): string[] {
  return text.split(/[\s,"'{}:[\]]+/).filter(Boolean);
}

function fromAuthorizeUrl(text: string): NotionAppCredentials | null {
  let url: URL;
  try {
    url = new URL(text);
  } catch {
    return null;
  }

  const clientId = url.searchParams.get("client_id")?.trim();
  if (!clientId) return null;

  const redirectUri = url.searchParams.get("redirect_uri")?.trim();
  return {
    clientId,
    clientSecret: "",
    redirectUris: redirectUri ? [redirectUri] : null,
  };
}

/**
 * The parsed credentials, or `null` when the text carries no more than one of them —
 * which is what typing a value by hand looks like, and has to keep working.
 *
 * Never throws. A half-copied blob is the likeliest mistake of all, and an exception
 * here would take down the field being pasted into.
 */
export function readNotionAppCredentials(pasted: string): NotionAppCredentials | null {
  const text = pasted.trim();
  if (!text) return null;
  if (text.startsWith("http")) return fromAuthorizeUrl(text);

  const values = valuesIn(text);
  const clientId = values.find((value) => CLIENT_ID.test(value));
  const clientSecret = values.find((value) => CLIENT_SECRET.test(value));
  if (!clientId || !clientSecret) return null;

  return { clientId, clientSecret, redirectUris: null };
}

/**
 * What to say when the URI the integration registered is not the one Kanso answers on.
 *
 * Character for character, because that is how Notion compares them: a trailing slash
 * is a different URI, and reporting it as equal would be worse than saying nothing.
 */
export function redirectUriProblem(
  credentials: NotionAppCredentials,
  expected: string,
): string | null {
  if (credentials.redirectUris === null) return null;
  if (credentials.redirectUris.includes(expected)) return null;

  const listed = credentials.redirectUris.length
    ? `it names ${credentials.redirectUris.join(", ")}`
    : "it names none at all";
  return (
    `This integration does not have ${expected} as a redirect URI — ${listed}. ` +
    `Add it in Notion, exactly as written: Notion compares it character for character.`
  );
}
