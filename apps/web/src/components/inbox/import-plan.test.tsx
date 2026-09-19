import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NotionImportSource, Team } from "@/lib/api";
import { ImportPlan } from "./import-plan";
import { importCounts, type ImportMapping } from "./import-map";

/**
 * The destination, on an instance that has nowhere to import into.
 *
 * A fresh instance has no team, and a plan that writes anything but teams is refused
 * without one. What the step used to do about that was draw the `<select>` anyway — a
 * dropdown holding "— choose a team —" and nothing else — and disable Next. A control
 * that opens onto nothing does not read as a missing row, it reads as a broken control,
 * and the reader is left with no way to find out which of the two it is.
 *
 * `useImportSchema` is stubbed at its resting state for the reason `import-counts.test.tsx`
 * gives: `RelationHint` returns null until it answers, and letting the fetch start prints
 * a stack trace out of a passing run.
 */

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return { ...actual, useImportSchema: () => ({ data: undefined }) };
});

const createTeam = vi.hoisted(() => vi.fn());

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, createTeam } };
});

const SOURCES: NotionImportSource[] = [
  { id: "base", name: "Tasks", databaseId: "db", pages: 4, pagesExact: true },
];

/** One base kept as tickets: the shortest plan that needs somewhere to land. */
const MAPPING: ImportMapping = { base: "tickets" };

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

const noop = () => {};

function stepTwo({ teams = [] as Team[], teamRequired = true } = {}) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
  return render(
    <ImportPlan
      sources={SOURCES}
      loading={false}
      mapping={MAPPING}
      kept={MAPPING}
      mappings={{}}
      counts={importCounts(SOURCES, MAPPING)}
      planEmpty={false}
      teams={teams}
      teamId=""
      teamRequired={teamRequired}
      onTeam={noop}
      onCycle={noop}
      onSuggest={noop}
      onNext={noop}
      pending={false}
      details={null}
    />,
    { wrapper },
  );
}

describe("step 2's destination team", () => {
  beforeEach(() => {
    createTeam.mockReset();
  });

  it("offers the picker once there is something in it", () => {
    stepTwo({ teams: [team()] });

    expect(screen.queryByRole("combobox", { name: /into team/i })).not.toBeNull();
    expect(screen.queryByLabelText(/first team/i)).toBeNull();
  });

  it("names a team instead of offering a dropdown with nothing in it", () => {
    stepTwo();

    expect(screen.queryByRole("combobox", { name: /into team/i })).toBeNull();
    expect(screen.queryByText(/no team yet/i)).not.toBeNull();
  });

  it("creates the team it was given the name of", async () => {
    createTeam.mockResolvedValue(team({ name: "Product" }));
    stepTwo();

    fireEvent.change(screen.getByLabelText(/first team/i), { target: { value: "Product" } });
    fireEvent.click(screen.getByRole("button", { name: /create the team/i }));

    await waitFor(() => expect(createTeam).toHaveBeenCalledWith({ name: "Product" }));
  });

  /** A plan of teams alone brings its own destinations, so there is nothing to ask about. */
  it("asks for nothing when the plan needs no destination", () => {
    stepTwo({ teamRequired: false });

    expect(screen.queryByRole("combobox", { name: /into team/i })).toBeNull();
    expect(screen.queryByText(/no team yet/i)).toBeNull();
  });
});
