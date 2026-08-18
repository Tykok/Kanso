/**
 * Reading the OAuth client file Google Cloud downloads.
 *
 * Setting Google sign-in up used to be two careful copy-pastes — an id and a secret,
 * each long, each wrong in a way nothing notices until someone tries to sign in. Google
 * offers the same client as a JSON file, so accepting a paste of that file removes both
 * transcriptions and, because the file lists the redirect URIs the client was created
 * with, lets the mismatch be named at paste time instead of at sign-in time.
 *
 * Client-side only, and deliberately: the file is read to fill two fields, and there is
 * nothing for a server to do with it that the two fields do not already say.
 */

export type GoogleClientFile = {
  clientId: string;
  /** "" when the file carried no secret; the id is still worth filling in. */
  clientSecret: string;
  /** `null` means the file did not list any, which is not the same as an empty list. */
  redirectUris: string[] | null;
};

function asStrings(value: unknown): string[] | null {
  if (!Array.isArray(value)) return null;
  return value.filter((entry): entry is string => typeof entry === "string");
}

/**
 * The parsed client, or `null` when this is not one — including when it is a plain
 * client id typed or pasted normally, which has to keep working.
 *
 * Never throws. A half-copied file is the likeliest mistake of all, and an exception
 * here would take down the field being pasted into.
 */
export function readGoogleClientFile(pasted: string): GoogleClientFile | null {
  const text = pasted.trim();
  // A client id never starts with a brace, so this is the cheap way to leave the
  // ordinary paste entirely alone.
  if (!text.startsWith("{")) return null;

  let parsed: unknown;
  try {
    parsed = JSON.parse(text);
  } catch {
    return null;
  }
  if (typeof parsed !== "object" || parsed === null) return null;

  // Google wraps the credentials under the client's type. `web` is the one Kanso wants;
  // `installed` is accepted because a paste of the wrong client type is better answered
  // by the credential check than by a field that quietly ignored it.
  const record = parsed as Record<string, unknown>;
  const section = (record.web ?? record.installed) as Record<string, unknown> | undefined;
  if (typeof section !== "object" || section === null) return null;

  const clientId = typeof section.client_id === "string" ? section.client_id.trim() : "";
  if (!clientId) return null;

  return {
    clientId,
    clientSecret: typeof section.client_secret === "string" ? section.client_secret.trim() : "",
    redirectUris: asStrings(section.redirect_uris),
  };
}

/**
 * What to say when the file's own redirect URIs do not include Kanso's.
 *
 * Character for character, because that is how Google compares them: a trailing slash
 * is a different URI, and reporting it as equal would be worse than saying nothing.
 */
export function redirectUriProblem(file: GoogleClientFile, expected: string): string | null {
  if (file.redirectUris === null) return null;
  if (file.redirectUris.includes(expected)) return null;

  const listed = file.redirectUris.length
    ? `it lists ${file.redirectUris.join(", ")}`
    : "it lists none at all";
  return (
    `This client does not have ${expected} as an authorised redirect URI — ${listed}. ` +
    `Add it in Google Cloud, exactly as written: Google compares it character for character.`
  );
}
