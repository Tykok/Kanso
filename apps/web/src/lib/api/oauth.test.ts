import { describe, expect, it } from "vitest";
import { mcpAddCommand } from "./oauth";

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
