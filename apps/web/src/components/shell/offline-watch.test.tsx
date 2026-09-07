import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { DEFAULT_PREFERENCES } from "@/lib/api";
import { useOffline } from "@/store/offline";
import { AppShell } from "./app-shell";

/**
 * The queue is emptied from wherever the reader is standing — `KAN-88`.
 *
 * `useOfflineWatch` was mounted on the inbox page and nowhere else, which was harmless
 * while the only write it could hold was a notification marked read: you were on the
 * inbox to make one. `KAN-88` routes every ticket patch through the same queue, so a
 * status changed on the board would have sat on disk until somebody happened to open the
 * inbox — a write the application had promised to send and never did.
 *
 * Asserted by mounting the shell, because the mount *location* is the whole subject: the
 * hook itself is covered by the queue's own tests, and no test of it can tell you which
 * screens run it.
 */

/** What the flush actually put on the wire. */
const sent: string[] = [];
/** Set by a test that needs its write to stay in the queue for the banner to list. */
let unreachable = false;

vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  sendRaw: (path: string, method: string) => {
    sent.push(`${method} ${path}`);
    // A `TypeError` with no `status` is the shape of a request that never arrived, which
    // is what leaves a write `queued` rather than `rejected`.
    return unreachable ? Promise.reject(new TypeError("Failed to fetch")) : Promise.resolve(undefined);
  },
}));

/**
 * The four the shell gates on. Mocked rather than seeded: `Me` and `SetupState` are
 * wide, and none of their fields is the subject here — the tree rendering at all is.
 */
vi.mock("@/lib/queries", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/queries")>()),
  useMe: () => ({ data: { user: { id: "u1", displayName: "Tykok", email: "t@k.local", instanceRole: "owner" }, teamIds: [], preferences: { ...DEFAULT_PREFERENCES, onboardedAt: "2026-09-01T10:00:00Z" } }, isLoading: false, error: null }),
  useAuthMode: () => ({ data: { mode: "dev", passwordLoginEnabled: false, providers: [] }, isLoading: false }),
  useSetupState: () => ({ data: { needsOwner: false, setupCompletedAt: "2026-09-01T10:00:00Z", notion: { configured: false, bootstrapped: false, appConfigured: false } }, isLoading: false }),
  useSyncStatus: () => ({ data: undefined }),
}));

// The shell reads the address bar. Neither value matters here; their absence throws.
vi.mock("next/navigation", () => ({
  usePathname: () => "/",
  useRouter: () => ({ push: () => {}, replace: () => {} }),
}));

describe("a write queued on any screen", () => {
  beforeEach(async () => {
    sent.length = 0;
    unreachable = false;
    for (const write of useOffline.getState().writes) {
      await useOffline.getState().discard(write.id);
    }
  });

  it("is flushed by the shell, not by the one page that draws the banner", async () => {
    await useOffline.getState().hold({
      reference: "KAN-142",
      summary: "status → Done",
      request: { path: "/api/tickets/t1", method: "PATCH", body: { status: "done" } },
    });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <AppShell>
          <div>a board, say</div>
        </AppShell>
      </QueryClientProvider>,
    );

    await waitFor(() => expect(sent).toEqual(["PATCH /api/tickets/t1"]));
  });

  it("is listed by the shell, on whatever screen the reader is on", async () => {
    unreachable = true;
    await useOffline.getState().hold({
      reference: "KAN-142",
      summary: "status → Done",
      request: { path: "/api/tickets/t1", method: "PATCH", body: { status: "done" } },
    });

    render(
      <QueryClientProvider client={new QueryClient()}>
        <AppShell>
          <div>a board, say</div>
        </AppShell>
      </QueryClientProvider>,
    );

    // The row the reader came for: which write is waiting, not how many.
    await waitFor(() => expect(screen.getByTestId("offline-banner")).toBeTruthy());
    expect(screen.getByText("status → Done")).toBeTruthy();
    expect(screen.getByText("a board, say")).toBeTruthy();
  });
});
