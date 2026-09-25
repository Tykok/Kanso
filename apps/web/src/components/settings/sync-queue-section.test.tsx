import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SyncQueueSection } from "./sync-queue-section";

const reading = vi.hoisted(() => ({ current: undefined as unknown }));

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return {
    ...actual,
    useRetryFailedPushes: () => ({ mutate: vi.fn(), isPending: false }),
    useSyncQueue: () => reading.current,
  };
});

const LOADED = {
  dataUpdatedAt: Date.parse("2026-09-25T17:00:00Z"),
  isError: false,
  data: {
    counts: { pending: 51, running: 1, failed: 1 },
    queued: [
      { id: 1, entity: "team", entityId: "a1b2c3d4-0000-0000-0000-000000000000",
        label: "Platform", operation: "upsert", status: "running", attempts: 1 },
      { id: 2, entity: "ticket", entityId: "deadbeef-0000-0000-0000-000000000000",
        operation: "delete", status: "pending", attempts: 0 },
    ],
    failed: [
      { id: 3, entity: "project", entityId: "5e1acffe-0000-0000-0000-000000000000",
        label: "Roadmap", operation: "upsert", status: "failed", attempts: 8,
        error: "Notion API 400 (validation_error): x\n" +
          "body.properties.Start.date should be defined, instead was `undefined`." },
    ],
  },
};

describe("the sync queue section", () => {
  beforeEach(() => {
    reading.current = LOADED;
  });

  it("does not call a queue it has not read yet empty", () => {
    reading.current = { data: undefined, isError: false, dataUpdatedAt: 0 };
    render(<SyncQueueSection />);
    expect(screen.getByText("Reading the queue…")).toBeTruthy();
    expect(screen.queryByText("Nothing waiting.")).toBeNull();
  });

  it("says so when the queue could not be read, rather than showing it empty", () => {
    reading.current = { data: undefined, isError: true, dataUpdatedAt: 0 };
    render(<SyncQueueSection />);
    expect(
      screen.getByText("The queue could not be read. It is tried again every few seconds."),
    ).toBeTruthy();
    expect(screen.queryByText("Nothing waiting.")).toBeNull();
  });

  it("counts the whole queue, not the rows it lists", () => {
    render(<SyncQueueSection />);
    expect(screen.getByText("51 waiting")).toBeTruthy();
  });

  it("names a row by its entity, and by type and id when the entity is gone", () => {
    render(<SyncQueueSection />);
    expect(screen.getByText("Platform")).toBeTruthy();
    expect(screen.getByText("ticket deadbeef")).toBeTruthy();
  });

  it("reads a failure as one line", () => {
    render(<SyncQueueSection />);
    expect(screen.getByText("Notion refused the page: Start is invalid")).toBeTruthy();
    expect(screen.getByText("Failed after 8 attempts")).toBeTruthy();
  });
});
