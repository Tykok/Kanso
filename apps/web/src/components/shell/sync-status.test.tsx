import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { SyncStatus } from "./sync-status";

const reading = vi.hoisted(() => ({ current: undefined as unknown }));

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return { ...actual, useSyncStatus: () => ({ data: reading.current }) };
});

const jobs = (j: Record<string, number>) => ({ mirrorEnabled: true, bootstrapped: true, jobs: j });

describe("the sync status at the foot of the sidebar", () => {
  beforeEach(() => {
    reading.current = undefined;
  });

  it("draws no bar when nothing is left", () => {
    reading.current = jobs({ done: 12 });
    render(<SyncStatus canConfigure />);
    expect(screen.queryByRole("progressbar")).toBeNull();
  });

  it("draws a bar while a wave drains", () => {
    reading.current = jobs({ pending: 8, running: 2 });
    render(<SyncStatus canConfigure />);
    expect(screen.getByRole("progressbar")).toBeTruthy();
  });

  it("links an admin to the queue, and gives a member the words only", () => {
    reading.current = jobs({ pending: 1 });
    const { unmount } = render(<SyncStatus canConfigure />);
    expect(screen.getByRole("link").getAttribute("href")).toBe("/settings?section=sync-queue");
    unmount();

    render(<SyncStatus canConfigure={false} />);
    expect(screen.queryByRole("link")).toBeNull();
    expect(screen.getByText("Notion: connected · 1 queued")).toBeTruthy();
  });
});
