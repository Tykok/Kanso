import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { SetupState } from "@/lib/api";
import { keys } from "@/lib/queries";
import { ConnectionsSection } from "./connections-section";

/**
 * The two gestures, and the one that used to be four.
 *
 * Connecting through Notion grants a token and no parent page, so an instance spends a
 * real interval in "connected, nowhere to write" — and the button offered there could
 * only come back with the API's refusal. Saving the page is now what creates the
 * databases, and this file is what says so.
 */

const saveNotion = vi.hoisted(() => vi.fn());
const bootstrapNotion = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, saveNotion, bootstrapNotion } };
});

// Everything this section draws beside the Notion card reaches for the network and has
// nothing to do with what is under test here.
vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return {
    ...actual,
    useSyncStatus: () => ({ data: undefined }),
    useSyncDetail: () => ({ data: undefined }),
    useRetryFailedPushes: () => ({ mutate: vi.fn(), isPending: false }),
  };
});

// The card reads the address bar for the `notion_connected` / `notion_error` params
// Notion's callback appends. Outside Next's own runtime `useSearchParams()` has no
// provider to read from and returns `null`, so the callback-banner branch's `.get()`
// throws before anything under test here ever renders. Same fix `offline-watch.test.tsx`
// applies for the same reason: what the address bar says is not the subject of this file.
vi.mock("next/navigation", () => ({ useSearchParams: () => new URLSearchParams() }));

vi.mock("@/components/setup/notion-connect", () => ({ NotionConnect: () => null }));
vi.mock("@/components/setup/notion-page-field", () => ({
  NotionPageField: ({ onChange }: { onChange: (id: string) => void }) => (
    <button type="button" onClick={() => onChange("page-1")}>
      Pick a page
    </button>
  ),
}));

const state = (notion: Partial<SetupState["notion"]> = {}): SetupState => ({
  needsOwner: false,
  notion: {
    configured: true,
    managedByEnvironment: false,
    bootstrapped: false,
    appConfigured: true,
    appManagedByEnvironment: false,
    ...notion,
  },
  google: { configured: false, managedByEnvironment: false },
});

function section(notion: Partial<SetupState["notion"]> = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  // The settings page already holds this in `useSetupState()` before this card ever
  // mounts — seeded here so invalidating it is something a test can observe, rather
  // than a no-op against a cache that never heard of the key.
  client.setQueryData(keys.setupState, state(notion));
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  const rendered = render(<ConnectionsSection state={state(notion)} canConfigure />, { wrapper });
  return { client, ...rendered };
}

describe("the Notion card in settings", () => {
  beforeEach(() => {
    saveNotion.mockReset();
    bootstrapNotion.mockReset();
  });

  it("creates the databases as part of saving the page", async () => {
    saveNotion.mockResolvedValue(state({ parentPageId: "page-1" }));
    bootstrapNotion.mockResolvedValue({ mirrorEnabled: true, bootstrapped: true, jobs: {} });
    section();

    fireEvent.click(screen.getByRole("button", { name: /pick a page/i }));
    // Two cards, two `Save` buttons — Google's own is beside Notion's. Notion's card
    // draws first, so it is the first match; a query scoped to it would need a test id
    // this component has no other reason to carry.
    fireEvent.click(screen.getAllByRole("button", { name: /^save$/i })[0]);

    await waitFor(() => expect(saveNotion).toHaveBeenCalledWith({ parentPageId: "page-1" }));
    await waitFor(() => expect(bootstrapNotion).toHaveBeenCalledOnce());
  });

  /** Creating them twice is the force case, and force is not what Save means. */
  it("does not re-create databases that already exist", async () => {
    saveNotion.mockResolvedValue(state({ parentPageId: "page-1", bootstrapped: true }));
    section({ bootstrapped: true });

    fireEvent.click(screen.getByRole("button", { name: /pick a page/i }));
    fireEvent.click(screen.getAllByRole("button", { name: /^save$/i })[0]);

    await waitFor(() => expect(saveNotion).toHaveBeenCalled());
    expect(bootstrapNotion).not.toHaveBeenCalled();
  });

  it("keeps the token field folded away until it is asked for", () => {
    section();

    expect(screen.queryByLabelText(/integration token/i)).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: /paste an integration token/i }));
    expect(screen.queryByLabelText(/integration token/i)).not.toBeNull();
  });

  /**
   * A chain that stops halfway still moved the server: the page landed even though the
   * bootstrap after it did not. The error has to stay visible — `saveNotion.isError` is
   * how the card says which half failed — but the setup state cannot be left holding the
   * pre-save answer while the note above reads it, or the screen re-asks for a page it
   * already has. Asserted on `isInvalidated` rather than on a refetch: nothing here
   * mounts a `useSetupState()` of its own for a refetch to be observed through, and the
   * settings page that does is exactly what this cache entry is standing in for.
   */
  it("still marks the setup state stale when the bootstrap step fails", async () => {
    saveNotion.mockResolvedValue(state({ parentPageId: "page-1" }));
    bootstrapNotion.mockRejectedValue(new Error("Notion said no"));
    const { client } = section();

    fireEvent.click(screen.getByRole("button", { name: /pick a page/i }));
    fireEvent.click(screen.getAllByRole("button", { name: /^save$/i })[0]);

    await waitFor(() => expect(screen.getByText(/notion said no/i)).toBeTruthy());
    expect(client.getQueryState(keys.setupState)?.isInvalidated).toBe(true);
  });

  /**
   * "An environment variable wins over the database" — one of this repository's stated
   * invariants — verified rather than eyeballed: the field is seeded open for exactly
   * the case an operator needs it, to see what `NOTION_TOKEN` actually set, and there is
   * nothing left to save over it.
   */
  it("shows the token field openly and locks Save when the environment manages it", () => {
    section({ managedByEnvironment: true });

    expect(screen.queryByLabelText(/integration token/i)).not.toBeNull();
    const notionSave = screen.getAllByRole("button", { name: /^save$/i })[0] as HTMLButtonElement;
    expect(notionSave.disabled).toBe(true);
  });
});
