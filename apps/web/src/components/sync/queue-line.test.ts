import { describe, expect, it } from "vitest";
import type { QueuedJob } from "@/lib/api";
import { queueLine } from "./sync-queue-line";

const NOW = new Date("2026-09-25T17:00:00Z");
const job = (over: Partial<QueuedJob>): QueuedJob => ({
  id: 1,
  entity: "project",
  entityId: "5e1acffe-fb7b-4f9c-b18a-ae84d95e5e05",
  operation: "upsert",
  status: "pending",
  attempts: 0,
  ...over,
});

describe("what a queue row says", () => {
  it("says a claimed job is being pushed", () => {
    expect(queueLine(job({ status: "running" }), NOW).state).toBe("Pushing");
  });

  it("says a due job is waiting", () => {
    expect(queueLine(job({ nextAttemptAt: "2026-09-25T16:59:00Z" }), NOW).state).toBe("Waiting");
  });

  it("counts down to the next try, with the reason it was put back", () => {
    const line = queueLine(
      job({
        nextAttemptAt: "2026-09-25T17:00:12Z",
        error: "No Notion database registered for 'teams' — run the bootstrap first",
      }),
      NOW,
    );
    expect(line.state).toBe("Retry in 12 s");
    expect(line.reason?.summary).toBe(
      "No Notion database registered for 'teams' — run the bootstrap first",
    );
  });

  it("switches to minutes past one", () => {
    expect(queueLine(job({ nextAttemptAt: "2026-09-25T17:04:30Z" }), NOW).state).toBe(
      "Retry in 5 min",
    );
  });

  it("says how many tries a failure took", () => {
    expect(queueLine(job({ status: "failed", attempts: 1 }), NOW).state).toBe(
      "Failed after 1 attempt",
    );
    expect(queueLine(job({ status: "failed", attempts: 8 }), NOW).state).toBe(
      "Failed after 8 attempts",
    );
  });
});
