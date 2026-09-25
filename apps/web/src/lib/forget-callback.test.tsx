import { describe, expect, it } from "vitest";
import { forgetCallback } from "./forget-callback";

describe("forgetting an OAuth callback's answer", () => {
  it("drops the answer and keeps the tab it landed on", () => {
    window.history.replaceState(null, "", "/settings?section=connections&notion_connected=1");
    forgetCallback("connections");
    expect(window.location.pathname + window.location.search).toBe(
      "/settings?section=connections",
    );
  });
});
