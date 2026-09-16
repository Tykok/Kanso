import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Team } from "@/lib/api";
import { NotionImportCard } from "./notion-import-card";

/**
 * The guard this card exists for: an instance that has just been claimed has no team, and
 * an import of anything but teams is refused for the whole of that state.
 *
 * `NotionImportService` refuses a plan whose rows are not all teams when no destination is
 * given, and `import-step-two` disables its own Next for the same reason — greyed out,
 * with nothing on screen saying why or what to do about it. Which is what is tested here:
 * not that the import is blocked, but that somebody standing in front of it is told a team
 * is what is missing and handed the two ways to get one.
 *
 * `useTeams` is stubbed rather than served out of a `QueryClient`: it reads the archived
 * toggle from a zustand store before it fetches, and this file is about what the card does
 * with the answer rather than about how the answer arrives.
 */

const teams = vi.hoisted(() => ({ data: undefined as Team[] | undefined }));

vi.mock("@/lib/queries", () => ({ useTeams: () => teams }));

const createTeam = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, createTeam } };
});

const team = (overrides: Partial<Team> = {}): Team => ({
  id: "team-a",
  name: "Design",
  key: "DES",
  archived: false,
  ticketCount: 0,
  mirror: { state: "pending" },
  editable: true,
  statuses: [],
  ...overrides,
});

function card(onOpen: () => void = () => {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(<NotionImportCard onOpen={onOpen} />, { wrapper });
}

const button = (name: RegExp) => screen.getByRole("button", { name });

describe("the import card in the setup wizard", () => {
  beforeEach(() => {
    teams.data = undefined;
    createTeam.mockReset();
  });

  it("offers the import straight away once a team exists", () => {
    teams.data = [team()];
    const onOpen = vi.fn();
    card(onOpen);

    fireEvent.click(button(/import from notion/i));

    expect(onOpen).toHaveBeenCalledOnce();
    expect(screen.queryByLabelText(/first team/i)).toBeNull();
  });

  /**
   * An archived team is not a destination — `import-dialog` filters it out of the list it
   * offers — so a card that counted it would send somebody to a picker with nothing in it.
   */
  it("does not count an archived team as somewhere to import into", () => {
    teams.data = [team({ archived: true })];
    card();

    expect(screen.queryByLabelText(/first team/i)).not.toBeNull();
  });

  it("says why tickets need a team, and still opens the dialog for a teams base", () => {
    teams.data = [];
    const onOpen = vi.fn();
    card(onOpen);

    expect(screen.queryByText(/no team yet/i)).not.toBeNull();
    fireEvent.click(button(/import from notion/i));
    expect(onOpen).toHaveBeenCalledOnce();
  });

  it("creates the team it was given the name of", async () => {
    teams.data = [];
    createTeam.mockResolvedValue(team({ name: "Product" }));
    card();

    fireEvent.change(screen.getByLabelText(/first team/i), { target: { value: "Product" } });
    fireEvent.click(button(/create the team/i));

    await waitFor(() => expect(createTeam).toHaveBeenCalledWith({ name: "Product" }));
  });

  /** A blank name is refused by the server too; refusing it here costs no round trip. */
  it("will not create a team with no name", () => {
    teams.data = [];
    card();

    fireEvent.click(button(/create the team/i));

    expect(createTeam).not.toHaveBeenCalled();
  });
});
