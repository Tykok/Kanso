import { describe, expect, it } from "vitest";
import { safeNext } from "./next-url";

const API = "http://localhost:8080";

describe("safeNext", () => {
  it("keeps a relative path", () => {
    expect(safeNext("/settings", API)).toBe("/settings");
  });

  it("keeps an absolute URL on the API's own origin, which is where consent lives", () => {
    const consent = `${API}/oauth/consent?client_id=claude-code&state=abc`;
    expect(safeNext(consent, API)).toBe(consent);
  });

  it("refuses any other origin", () => {
    // `next` is reflected into a redirect on the one page a member is trained to trust.
    expect(safeNext("https://evil.example.com/", API)).toBe("/");
    expect(safeNext("//evil.example.com/", API)).toBe("/");
    // A prefix check passes this one; an origin comparison does not.
    expect(safeNext("http://localhost:8080.evil.example.com/", API)).toBe("/");
  });

  it("refuses the lookalikes a string comparison reads as the API", () => {
    // Every one of these contains the API's origin as text, and none of them is it.
    expect(safeNext("https://localhost:8080@evil.example.com/", API)).toBe("/");
    expect(safeNext(`https://evil.example.com/?x=${API}/oauth/consent`, API)).toBe("/");
    expect(safeNext("https://api.example.com.evil.example.com/", "https://api.example.com")).toBe("/");
    // A backslash: some browsers normalise it to a slash, so `\\evil.com` is `//evil.com`.
    expect(safeNext("\\\\evil.example.com/", API)).toBe("/");
    expect(safeNext("/\\evil.example.com/", API)).toBe("/");
    // A tab is stripped by the browser before it resolves the URL, turning this into
    // `//evil.example.com`.
    expect(safeNext("/\t/evil.example.com/", API)).toBe("/");
  });

  it("refuses a scheme that is not http", () => {
    expect(safeNext("javascript:alert(1)", API)).toBe("/");
  });

  it("refuses the same origin under a scheme that is not the API's", () => {
    // The origin comparison already covers it — scheme is part of an origin — and it is
    // worth stating, because "same host" is the check people write instead.
    expect(safeNext("https://localhost:8080/oauth/consent", API)).toBe("/");
  });

  it("falls back to the root for nothing at all", () => {
    expect(safeNext(null, API)).toBe("/");
    expect(safeNext("", API)).toBe("/");
  });

  it("falls back to the root when the API origin itself is unparseable", () => {
    // Misconfiguration must not open the redirect it exists to close.
    expect(safeNext(`${API}/oauth/consent`, "not-a-url")).toBe("/");
  });
});
