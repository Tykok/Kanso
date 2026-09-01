import { describe, expect, it } from "vitest";
import { mcpAddCommand, unrecognisedScopeNote } from "./oauth";

describe("mcpAddCommand", () => {
  it("points the agent at the API's MCP endpoint, not at the web app", () => {
    // The empty state's only job is to be copied and pasted. A command naming the web
    // origin would install a server that answers HTML to every MCP call, and the
    // person reading the error would have no reason to suspect the host.
    expect(mcpAddCommand("https://kanso.example.com")).toBe(
      "claude mcp add --transport http kanso https://kanso.example.com/api/mcp",
    );
  });

  it("does not double the slash when the configured API URL ends with one", () => {
    // `NEXT_PUBLIC_API_URL` is written by hand into a compose file, so it arrives both
    // ways. `//api/mcp` is a different resource string from `/api/mcp`, and RFC 8707
    // audience binding compares those literally.
    expect(mcpAddCommand("http://localhost:8080/")).toBe(
      "claude mcp add --transport http kanso http://localhost:8080/api/mcp",
    );
  });
});

describe("unrecognisedScopeNote", () => {
  it("says nothing when every permission has a name", () => {
    // The common case by a very long way, and a note saying "0 further permissions"
    // would be a defect notice on every healthy row.
    expect(unrecognisedScopeNote(0)).toBeNull();
  });

  it("counts one in the singular", () => {
    expect(unrecognisedScopeNote(1)).toContain("1 further permission this version");
  });

  it("counts more than one in the plural", () => {
    expect(unrecognisedScopeNote(3)).toContain("3 further permissions this version");
  });

  it("ends on the action, because a warning with no way out is just an alarm", () => {
    expect(unrecognisedScopeNote(1)).toContain("Revoke the application");
  });
});
