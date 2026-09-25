import { describe, expect, it } from "vitest";
import { syncQueueHref } from "./sync-queue-href";

describe("where See the queue goes", () => {
  it("takes a configurator to the queue and a member to what they can read", () => {
    expect(syncQueueHref(true)).toBe("/settings?section=sync-queue");
    expect(syncQueueHref(false)).toBe("/settings?section=connections");
  });
});
