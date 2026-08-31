import { describe, expect, it } from "vitest";
import { readNotionAppCredentials, redirectUriProblem } from "./notion-app-credentials";

const CLIENT_ID = "1f2e3d4c-5b6a-7980-9a1b-2c3d4e5f6a7b";
const REDIRECT = "http://localhost:8080/api/setup/notion/callback";

describe("readNotionAppCredentials", () => {
  /**
   * Notion's integration page prints a ready-made authorization URL. It carries the
   * client id and the redirect URI the integration was registered with, which is both
   * halves of what a mistake here looks like.
   */
  it("reads the authorization URL Notion prints on the integration page", () => {
    const url =
      "https://api.notion.com/v1/oauth/authorize?client_id=" +
      CLIENT_ID +
      "&response_type=code&owner=user&redirect_uri=" +
      encodeURIComponent(REDIRECT);

    expect(readNotionAppCredentials(url)).toEqual({
      clientId: CLIENT_ID,
      clientSecret: "",
      redirectUris: [REDIRECT],
    });
  });

  /** Selecting the secrets block on Notion's page and copying it takes the labels too. */
  it("reads an id and a secret out of the labelled block Notion renders", () => {
    const pasted = `OAuth client ID\n${CLIENT_ID}\nOAuth client secret\nsecret_AbCdEf0123456789AbCdEf0123456789`;

    expect(readNotionAppCredentials(pasted)).toEqual({
      clientId: CLIENT_ID,
      clientSecret: "secret_AbCdEf0123456789AbCdEf0123456789",
      redirectUris: null,
    });
  });

  /** Kept together in a password manager, they come back as JSON often enough. */
  it("reads them out of JSON", () => {
    const pasted = JSON.stringify({
      client_id: CLIENT_ID,
      client_secret: "secret_AbCdEf0123456789AbCdEf0123456789",
    });

    expect(readNotionAppCredentials(pasted)).toEqual({
      clientId: CLIENT_ID,
      clientSecret: "secret_AbCdEf0123456789AbCdEf0123456789",
      redirectUris: null,
    });
  });

  /** Typing the two values by hand still has to work, so a lone value is not a paste. */
  it("leaves a single value alone", () => {
    expect(readNotionAppCredentials(CLIENT_ID)).toBeNull();
    expect(readNotionAppCredentials("secret_AbCdEf0123456789AbCdEf0123456789")).toBeNull();
    expect(readNotionAppCredentials("")).toBeNull();
    expect(readNotionAppCredentials("   ")).toBeNull();
  });

  /**
   * An internal integration token is the thing this screen replaced, and it is not a
   * client secret. Filling the field with one would buy a refusal from Notion much
   * later, in words about OAuth, to somebody who pasted what Notion called a token.
   */
  it("refuses to read an internal integration token as a client secret", () => {
    expect(readNotionAppCredentials(`${CLIENT_ID}\nntn_012345678901234567890123456789012345678901234`)).toBeNull();
  });

  /** A half-copied blob is the likeliest mistake and must not take the field down. */
  it("does not throw on anything else", () => {
    expect(readNotionAppCredentials('{"client_id": "1f2e3d4c')).toBeNull();
    expect(readNotionAppCredentials("https://api.notion.com/v1/oauth/authorize")).toBeNull();
    expect(readNotionAppCredentials("not a credential at all")).toBeNull();
    expect(readNotionAppCredentials("{}")).toBeNull();
  });
});

describe("redirectUriProblem", () => {
  it("says so when Kanso's redirect URI is not the one the integration registered", () => {
    const problem = redirectUriProblem(
      { clientId: CLIENT_ID, clientSecret: "", redirectUris: ["https://other.example/callback"] },
      REDIRECT,
    );

    expect(problem).toContain(REDIRECT);
    expect(problem).toContain("https://other.example/callback");
  });

  it("stays quiet when it matches", () => {
    expect(
      redirectUriProblem({ clientId: CLIENT_ID, clientSecret: "", redirectUris: [REDIRECT] }, REDIRECT),
    ).toBeNull();
  });

  /** Notion compares it character for character, and so does this. */
  it("counts a trailing slash as a different URI", () => {
    expect(
      redirectUriProblem({ clientId: CLIENT_ID, clientSecret: "", redirectUris: [`${REDIRECT}/`] }, REDIRECT),
    ).not.toBeNull();
  });

  /** Nothing to compare against is not a problem to report. */
  it("says nothing about a paste that never carried one", () => {
    expect(redirectUriProblem({ clientId: CLIENT_ID, clientSecret: "", redirectUris: null }, REDIRECT)).toBeNull();
  });
});
