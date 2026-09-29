import { fireEvent, render, screen, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SyncView } from "./sync-view";

const reading = vi.hoisted(() => ({ current: undefined as unknown }));
const retryOne = vi.hoisted(() => vi.fn());
const retryAll = vi.hoisted(() => vi.fn());

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return {
    ...actual,
    useRetryFailedPushes: () => ({ mutate: retryAll, isPending: false }),
    useRetrySyncJob: () => ({ mutate: retryOne, isPending: false }),
    useSyncQueue: () => reading.current,
    useSyncStatus: () => ({
      data: { mirrorEnabled: true, bootstrapped: true, jobs: { pending: 51, failed: 2 } },
    }),
  };
});

const LOADED = {
  dataUpdatedAt: Date.parse("2026-09-25T17:00:00Z"),
  isError: false,
  data: {
    counts: { pending: 51, running: 1, failed: 2 },
    queued: [
      { id: 1, entity: "team", entityId: "a1b2c3d4-0000-0000-0000-000000000000",
        label: "Platform", operation: "upsert", status: "running", attempts: 1 },
      { id: 2, entity: "ticket", entityId: "deadbeef-0000-0000-0000-000000000000",
        operation: "delete", status: "pending", attempts: 0 },
    ],
    failed: [
      { id: 3, entity: "project", entityId: "5e1acffe-0000-0000-0000-000000000000",
        label: "Roadmap", operation: "upsert", status: "failed", attempts: 1,
        error: "Notion API 400 (validation_error): x\n" +
          "body.properties.Start.date should be defined, instead was `undefined`." },
      { id: 4, entity: "ticket", entityId: "0ddba11-0000-0000-0000-000000000000",
        label: "Write the brief", operation: "upsert", status: "failed", attempts: 0,
        error: "Blocked by project 5e1acffe-0000-0000-0000-000000000000, which Notion " +
          "refused: Notion API 400 (validation_error): x" },
    ],
  },
};

describe("the sync page", () => {
  beforeEach(() => {
    reading.current = LOADED;
    retryOne.mockReset();
    retryAll.mockReset();
  });

  it("does not call a queue it has not read yet empty", () => {
    reading.current = { data: undefined, isError: false, dataUpdatedAt: 0 };
    render(<SyncView />);
    expect(screen.getByText("Reading the queue…")).toBeTruthy();
    expect(screen.queryByText("Nothing waiting.")).toBeNull();
  });

  it("says so when the queue could not be read, rather than showing it empty", () => {
    reading.current = { data: undefined, isError: true, dataUpdatedAt: 0 };
    render(<SyncView />);
    expect(
      screen.getByText("The queue could not be read. It is tried again every few seconds."),
    ).toBeTruthy();
    expect(screen.queryByText("Nothing waiting.")).toBeNull();
  });

  it("counts the whole queue, not the rows it lists", () => {
    render(<SyncView />);
    expect(screen.getByText("51")).toBeTruthy();
  });

  it("names a row by its entity, and by type and id when the entity is gone", () => {
    render(<SyncView />);
    expect(screen.getByText("Platform")).toBeTruthy();
    expect(screen.getByText("ticket deadbeef")).toBeTruthy();
  });

  it("reads a refusal as one line, and a push stuck behind one as what it waits on", () => {
    render(<SyncView />);
    expect(screen.getByText("Notion refused the page: Start is invalid")).toBeTruthy();
    expect(screen.getByText("Waiting on a project Notion refused")).toBeTruthy();
  });

  it("retries the one row whose button was pressed", () => {
    render(<SyncView />);
    const row = screen.getByText("Roadmap").closest("li") as HTMLElement;
    fireEvent.click(within(row).getByRole("button", { name: "Retry" }));
    expect(retryOne).toHaveBeenCalledWith(3);
    expect(retryAll).not.toHaveBeenCalled();
  });

  it("offers a retry on refusals only, since everything queued is tried anyway", () => {
    render(<SyncView />);
    const row = screen.getByText("Platform").closest("li") as HTMLElement;
    expect(within(row).queryByRole("button")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Retry all" }));
    expect(retryAll).toHaveBeenCalled();
  });
});
