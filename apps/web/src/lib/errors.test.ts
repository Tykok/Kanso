import { describe, expect, it } from "vitest";
import { ApiError } from "./api";
import { actionErrorMessage } from "./errors";

describe("actionErrorMessage", () => {
  it("prefers the problem document's detail, which is written for a person", () => {
    const error = new ApiError(403, "Only an admin may change teams");
    expect(actionErrorMessage(error)).toBe("Only an admin may change teams");
  });

  it("ignores the problem body, which only the disposition modal reads", () => {
    const error = new ApiError(409, "The contents changed since they were counted", {
      counts: { subTeams: 2, projects: 3, tickets: 50 },
    });
    expect(actionErrorMessage(error)).toBe("The contents changed since they were counted");
  });

  it("names the status when the server sent no detail at all", () => {
    expect(actionErrorMessage(new ApiError(409, ""))).toBe("Request failed (409)");
  });

  it("reports a transport failure rather than staying silent", () => {
    expect(actionErrorMessage(new TypeError("Failed to fetch"))).toBe("Failed to fetch");
  });

  it("still says something for a value that is not an error at all", () => {
    expect(actionErrorMessage("boom")).toBe("Something went wrong");
    expect(actionErrorMessage(undefined)).toBe("Something went wrong");
  });
});
