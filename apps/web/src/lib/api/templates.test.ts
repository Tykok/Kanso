import { beforeEach, describe, expect, it, vi } from "vitest";

import { templatesApi } from "./templates";

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => ({}) });
  vi.stubGlobal("fetch", fetchMock);
});

const calledPath = () => String(fetchMock.mock.calls[0][0]);

describe("templatesApi", () => {
  it("names the team it is resolving against", async () => {
    await templatesApi.resolve("abc", "team-1");
    expect(calledPath()).toContain("/api/tickets/templates/abc/resolved?teamId=team-1");
  });

  /**
   * A draft has no team, and `teamId=undefined` in a query string would reach the server as
   * the literal word — which parses as neither a uuid nor an absence.
   */
  it("omits the team for a draft rather than sending an empty one", async () => {
    await templatesApi.resolve("abc", undefined);
    expect(calledPath()).toContain("/api/tickets/templates/abc/resolved");
    expect(calledPath()).not.toContain("teamId");
  });

  it("asks for the instance catalogue alone when no team is named", async () => {
    await templatesApi.list(undefined);
    expect(calledPath()).not.toContain("teamId");
  });
});
