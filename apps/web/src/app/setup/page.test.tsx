import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import SetupRoute from "./page";

/**
 * The four branches this ladder actually draws, read together rather than proved one
 * function at a time — the bug finding 10 found was a *state* the ladder fell through,
 * not a wrong sentence in any one of them, and nothing short of rendering the component
 * with each combination of `setup` and `me` would have caught it.
 *
 * `useSetupState` and `useMe` are stubbed directly rather than answered through a real
 * `QueryClient`: this page's own branches are what is under test, not react-query's. A
 * `QueryClientProvider` still wraps every render, because the owner-account branch mounts
 * `AccountStep`, which reaches for one on every render whether or not it ever fires a
 * request.
 */

const SETUP_STATE = vi.hoisted(() => vi.fn());
const ME = vi.hoisted(() => vi.fn());
const REPLACE = vi.hoisted(() => vi.fn());
const REFETCH_ME = vi.hoisted(() => vi.fn());

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: REPLACE, push: () => {} }),
}));

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return {
    ...actual,
    useSetupState: SETUP_STATE,
    useMe: ME,
  };
});

beforeEach(() => {
  REPLACE.mockReset();
  REFETCH_ME.mockReset();
});

/** `AccountStep` reaches for a `QueryClient` even when it never fires a request. */
function renderSetup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <SetupRoute />
    </QueryClientProvider>,
  );
}

describe("/setup, across the four states the ladder tells apart", () => {
  it("asks for the owner account when the instance has none", () => {
    SETUP_STATE.mockReturnValue({
      data: { needsOwner: true },
      error: null,
      isPending: false,
      refetch: vi.fn(),
    });
    ME.mockReturnValue({ data: undefined, error: new ApiError(401, "Unauthorized"), isPending: false });

    renderSetup();

    expect(screen.getByRole("heading", { name: /create your account/i })).not.toBeNull();
  });

  it("offers sign-in when the instance is claimed and the caller is only signed out", () => {
    SETUP_STATE.mockReturnValue({
      data: { needsOwner: false },
      error: null,
      isPending: false,
      refetch: vi.fn(),
    });
    ME.mockReturnValue({ data: undefined, error: new ApiError(401, "Unauthorized"), isPending: false });

    renderSetup();

    expect(screen.getByText(/this instance already has an owner/i)).not.toBeNull();
    expect(screen.getByRole("link", { name: /go to sign in/i })).not.toBeNull();
  });

  it("redirects to the board when the instance is claimed and the caller is signed in", async () => {
    SETUP_STATE.mockReturnValue({
      data: { needsOwner: false },
      error: null,
      isPending: false,
      refetch: vi.fn(),
    });
    ME.mockReturnValue({
      data: { user: { id: "u1" } },
      error: null,
      isPending: false,
      refetch: vi.fn(),
    });

    renderSetup();

    await waitFor(() => expect(REPLACE).toHaveBeenCalledWith("/"));
  });

  /**
   * Finding 10: owner exists, `/api/me` answers something other than a 401 (a 500, a
   * network failure once it has settled) — `claimed && signedIn` is false so the redirect
   * never fires, the 401 branch does not match, and before this fix `!needsOwner` rendered
   * "Opening Kanso…" forever with no way out. Answered the way `setup.error` already is.
   */
  it("offers a retry instead of dead-ending on any other /api/me failure", () => {
    SETUP_STATE.mockReturnValue({
      data: { needsOwner: false },
      error: null,
      isPending: false,
      refetch: vi.fn(),
    });
    ME.mockReturnValue({
      data: undefined,
      error: new ApiError(500, "The instance is not answering."),
      isPending: false,
      refetch: REFETCH_ME,
    });

    renderSetup();

    expect(screen.getByText(/cannot reach the instance/i)).not.toBeNull();
    expect(screen.getByText(/the instance is not answering/i)).not.toBeNull();
    expect(screen.queryByText(/opening kanso/i)).toBeNull();

    fireEvent.click(screen.getByRole("button", { name: /try again/i }));
    expect(REFETCH_ME).toHaveBeenCalled();
  });
});
