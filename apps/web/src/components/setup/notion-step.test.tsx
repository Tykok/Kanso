import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { InstanceRole, SetupState } from "@/lib/api";
import { NotionStep } from "./notion-step";

/**
 * Where the import dialog is mounted, and who is shown it.
 *
 * The first is not a matter of taste. `FormCard` is a `<form>`, the wizard submits on
 * Enter and on its primary button, and a `<button>` with no `type` inside a form is a
 * submit button — which `import-step-two`'s Next, a shadcn `Button`, is. Mounted inside
 * the card, pressing Next in the middle of an import would advance the wizard, unmount the
 * step and take the half-finished dialog with it. So the dialog is a sibling of the form,
 * and [ImportDialog] is stubbed here as exactly the shape that would prove it wrong: one
 * bare `<button>` inside it. Nothing about the real dialog is under test.
 *
 * The second is the plan's shape: `buildPlan` hides this step from a member, not from a
 * viewer, and a viewer may configure nothing — so the two blocks would be controls whose
 * every save comes back 403.
 */

const role = vi.hoisted(() => ({ current: "owner" as InstanceRole }));

vi.mock("@/lib/queries", () => ({
  useMe: () => ({ data: { user: { instanceRole: role.current } } }),
  // Read by `NotionImportCard`, whose own file tests what it does with the answer.
  useTeams: () => ({ data: [], isPending: false }),
  // `./data` reads this at module scope, and this step invalidates that entry once the
  // databases exist. The literal rather than the real module: the mock replaces it whole.
  keys: { setupState: ["setupState"] },
}));

const bootstrapNotion = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, bootstrapNotion } };
});

// Both reach for the network on mount, and neither has anything to do with this file.
vi.mock("./notion-connect", () => ({ NotionConnect: () => null }));
vi.mock("./notion-page-field", () => ({ NotionPageField: () => null }));
vi.mock("@/components/settings/notion-people-section", () => ({
  NotionPeopleSection: () => <p>Notion people</p>,
}));
vi.mock("@/components/inbox/import-dialog", () => ({
  ImportDialog: () => (
    <div>
      <button>Next</button>
    </div>
  ),
}));

const state = (notion: Partial<SetupState["notion"]> = {}): SetupState => ({
  needsOwner: false,
  notion: {
    configured: true,
    managedByEnvironment: false,
    bootstrapped: true,
    parentPageId: "1f0e0e0e0e0e0e0e0e0e0e0e0e0e0e0e",
    appConfigured: false,
    appManagedByEnvironment: false,
    ...notion,
  },
  google: { configured: false, managedByEnvironment: false },
});

function step(
  overrides: { state?: SetupState; onDone?: () => void; onState?: (next: SetupState) => void } = {},
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(
    <NotionStep
      head={null}
      state={overrides.state ?? state()}
      onState={overrides.onState ?? (() => {})}
      onDone={overrides.onDone ?? (() => {})}
      onSkip={() => {}}
    />,
    { wrapper },
  );
}

const open = () => fireEvent.click(screen.getByRole("button", { name: /import from notion/i }));

describe("the Notion step of the wizard", () => {
  beforeEach(() => {
    role.current = "owner";
    bootstrapNotion.mockReset();
  });

  /**
   * Connecting through Notion grants a token and no page, so this is the state every
   * instance passes through — and the button, offered there, could only come back with
   * the server's "No Notion parent page configured". The field that answers it is on
   * this same screen; the sentence is what points at it.
   */
  it("does not offer to create the databases before a parent page is saved", () => {
    step({ state: state({ bootstrapped: false, parentPageId: undefined }) });

    expect(screen.queryByRole("button", { name: /create the databases/i })).toBeNull();
    expect(screen.queryByText(/parent page/i)).not.toBeNull();
  });

  it("offers to create them once there is a page to create them under", () => {
    step({ state: state({ bootstrapped: false }) });

    expect(screen.queryByRole("button", { name: /create the databases/i })).not.toBeNull();
  });

  /**
   * `/api/admin/notion/bootstrap` answers the mirror's own status — four database ids and
   * the queue — and not a setup state. Written into the wizard's cache entry it left
   * `notion` undefined on the next render, which is a blank screen at the exact moment
   * the databases were created.
   */
  it("does not take the bootstrap answer for a setup state", async () => {
    bootstrapNotion.mockResolvedValue({ mirrorEnabled: true, bootstrapped: true, jobs: {} });
    const onState = vi.fn();
    step({ state: state({ bootstrapped: false }), onState });

    fireEvent.click(screen.getByRole("button", { name: /create the databases/i }));

    await waitFor(() => expect(bootstrapNotion).toHaveBeenCalled());
    expect(onState).not.toHaveBeenCalled();
  });

  it("does not advance the wizard when a button inside the import dialog is pressed", () => {
    const onDone = vi.fn();
    step({ onDone });

    open();
    fireEvent.click(screen.getByRole("button", { name: "Next" }));

    expect(onDone).not.toHaveBeenCalled();
  });

  it("offers the people table and the import once Notion is configured", () => {
    step();

    expect(screen.queryByText(/notion people/i)).not.toBeNull();
    expect(screen.queryByRole("button", { name: /import from notion/i })).not.toBeNull();
  });

  /** Nothing to read and nothing to import from: the workspace is not connected yet. */
  it("offers neither before Notion is connected", () => {
    step({ state: state({ configured: false }) });

    expect(screen.queryByText(/notion people/i)).toBeNull();
    expect(screen.queryByRole("button", { name: /import from notion/i })).toBeNull();
  });

  it("offers neither to a viewer, who may configure nothing", () => {
    role.current = "viewer";
    step();

    expect(screen.queryByText(/notion people/i)).toBeNull();
    expect(screen.queryByRole("button", { name: /import from notion/i })).toBeNull();
  });
});
