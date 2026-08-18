import { describe, expect, it } from "vitest";
import { readGoogleClientFile, redirectUriProblem } from "./google-client-file";

const REDIRECT = "http://localhost:8080/login/oauth2/code/google";

describe("readGoogleClientFile", () => {
  /** What "Download JSON" hands back for an OAuth client of type "Web application". */
  it("reads the web shape Google Cloud downloads", () => {
    const file = JSON.stringify({
      web: {
        client_id: "1234-abc.apps.googleusercontent.com",
        project_id: "kanso-123456",
        auth_uri: "https://accounts.google.com/o/oauth2/auth",
        token_uri: "https://oauth2.googleapis.com/token",
        client_secret: "GOCSPX-downloaded",
        redirect_uris: [REDIRECT],
      },
    });

    expect(readGoogleClientFile(file)).toEqual({
      clientId: "1234-abc.apps.googleusercontent.com",
      clientSecret: "GOCSPX-downloaded",
      redirectUris: [REDIRECT],
    });
  });

  /**
   * The other client type Google will let you create. Kanso wants the web one, but a
   * paste is a paste — filling the two fields from it beats silently doing nothing, and
   * the check that follows is what tells them the client is wrong.
   */
  it("reads the installed shape too", () => {
    const file = JSON.stringify({
      installed: { client_id: "9876-desktop.apps.googleusercontent.com", client_secret: "GOCSPX-desktop" },
    });

    expect(readGoogleClientFile(file)).toEqual({
      clientId: "9876-desktop.apps.googleusercontent.com",
      clientSecret: "GOCSPX-desktop",
      redirectUris: null,
    });
  });

  /** Not every file lists them, and "the file did not say" is not "the list is empty". */
  it("tells a file with no redirect_uris apart from one with an empty list", () => {
    const named = readGoogleClientFile(JSON.stringify({ web: { client_id: "a", client_secret: "b", redirect_uris: [] } }));
    expect(named?.redirectUris).toEqual([]);
    expect(readGoogleClientFile(JSON.stringify({ web: { client_id: "a", client_secret: "b" } }))?.redirectUris).toBeNull();
  });

  /** The paste this replaces still has to work — it is what most people will do. */
  it("leaves a plain client id alone", () => {
    expect(readGoogleClientFile("1234-abc.apps.googleusercontent.com")).toBeNull();
    expect(readGoogleClientFile("")).toBeNull();
    expect(readGoogleClientFile("   ")).toBeNull();
  });

  /** A half-copied file is the likeliest mistake, and must not take the field down. */
  it("does not throw on malformed JSON", () => {
    expect(readGoogleClientFile('{"web": {"client_id": "1234-abc')).toBeNull();
    expect(readGoogleClientFile("{}")).toBeNull();
    expect(readGoogleClientFile("null")).toBeNull();
    expect(readGoogleClientFile("[1, 2, 3]")).toBeNull();
    // Valid JSON, right shape, nothing in the one field that matters.
    expect(readGoogleClientFile(JSON.stringify({ web: { client_secret: "GOCSPX-only" } }))).toBeNull();
  });

  /** A client id is the useful half even when the secret was stripped out of the file. */
  it("accepts a file whose secret is missing and reports the id it did find", () => {
    expect(readGoogleClientFile(JSON.stringify({ web: { client_id: "1234-abc" } }))).toEqual({
      clientId: "1234-abc",
      clientSecret: "",
      redirectUris: null,
    });
  });
});

describe("redirectUriProblem", () => {
  /**
   * The second most common misconfiguration after a wrong secret, and the file already
   * knows the answer — so it is said at paste time rather than found at sign-in time.
   */
  it("says so when Kanso's redirect URI is not in the file's list", () => {
    const file = { clientId: "a", clientSecret: "b", redirectUris: ["https://other.example/callback"] };

    const problem = redirectUriProblem(file, REDIRECT);

    expect(problem).toContain(REDIRECT);
    expect(problem).toContain("https://other.example/callback");
  });

  it("stays quiet when the URI is listed", () => {
    expect(
      redirectUriProblem({ clientId: "a", clientSecret: "b", redirectUris: [REDIRECT] }, REDIRECT),
    ).toBeNull();
  });

  /** Google compares character for character, and so does this. */
  it("counts a trailing slash as a different URI, the way Google does", () => {
    expect(
      redirectUriProblem({ clientId: "a", clientSecret: "b", redirectUris: [`${REDIRECT}/`] }, REDIRECT),
    ).not.toBeNull();
  });

  /** Nothing to compare against is not a problem to report. */
  it("says nothing about a file that never listed any", () => {
    expect(redirectUriProblem({ clientId: "a", clientSecret: "b", redirectUris: null }, REDIRECT)).toBeNull();
  });

  it("names an empty list as the empty list it is", () => {
    expect(redirectUriProblem({ clientId: "a", clientSecret: "b", redirectUris: [] }, REDIRECT)).toContain(REDIRECT);
  });
});
