import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
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
}));

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
    appConfigured: false,
    appManagedByEnvironment: false,
    ...notion,
  },
  google: { configured: false, managedByEnvironment: false },
});

function step(overrides: { state?: SetupState; onDone?: () => void } = {}) {
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
      onState={() => {}}
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
