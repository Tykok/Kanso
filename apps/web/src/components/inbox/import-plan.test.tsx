import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NotionImportSchema } from "./import-columns";
import type { NotionImportSource, Team } from "@/lib/api";
import { ImportDetails } from "./import-details";
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
 * a stack trace out of a passing run. `usePeople`, `notionImportApi.peopleSeen` and
 * `notionPeopleApi.view` are stubbed for the same reason: `ImportDetails` mounts
 * `StepColumns` and `StepPeople` unconditionally now — that is the fix for the finding that
 * an unopened panel used to import with no column mapping and no assignees at all — so
 * every render here fires their requests too, real client or not.
 */

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return {
    ...actual,
    useImportSchema: () => ({ data: undefined }),
    usePeople: () => ({ data: undefined }),
  };
});

const createTeam = vi.hoisted(() => vi.fn());
/** Left at its default (never resolving) except in the one test that needs it in flight. */
const SCHEMA = vi.hoisted(() => vi.fn(() => new Promise<NotionImportSchema>(() => {})));

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    api: { ...actual.api, createTeam },
    notionImportApi: { ...actual.notionImportApi, peopleSeen: async () => [], schema: SCHEMA },
    notionPeopleApi: {
      ...actual.notionPeopleApi,
      view: async () => ({ available: true, people: [] }),
    },
  };
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
      busy={false}
      details={
        <ImportDetails
          bases={[]}
          kept={{}}
          mappings={{}}
          fallbacks={{}}
          teams={[]}
          projects={[]}
          plan={[]}
          onMapping={noop}
          onSeed={noop}
          onFallback={noop}
          onPeople={noop}
          onLoading={noop}
        />
      }
    />,
    { wrapper },
  );
}

describe("the plan screen's destination team", () => {
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

  it("keeps columns and people folded until they are asked for", () => {
    stepTwo({ teams: [team()] });

    // `StepColumns` and `StepPeople` are mounted unconditionally now — they seed the
    // shell's request whether or not anybody looks — so "folded" is no longer "absent
    // from the DOM"; it is this attribute, which drives the `display: none` that hides
    // them. `import-details.test.tsx` covers the seeding and the display toggle itself.
    const toggle = screen.getByRole("button", { name: /columns and people/i });
    expect(toggle.getAttribute("aria-expanded")).toBe("false");
  });
});

/**
 * Finding 1: a base whose schema has not arrived yet must not be allowed past Preview.
 *
 * `StepColumns` seeds the shell's `mappings` from the server's suggestion only once its
 * schema query answers; clicking through earlier sends `columns: {}` for that base and
 * `NotionImportService` takes it verbatim. `ImportDetails` combines `StepColumns` and
 * `StepPeople`'s own in-flight state and reports it up through `onLoading`; this is the
 * proof that a real, unresolved schema request reaches all the way to the button — a
 * fake `useImportSchema` stub, the way the tests above use one, would not exercise the
 * wiring this finding is about.
 */
describe("the Preview button waits for the folded panel", () => {
  function pending({ teams = [team()] }: { teams?: Team[] } = {}) {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    });
    const wrapper = ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    );

    function Harness() {
      const [busy, setBusy] = useState(false);
      return (
        <ImportPlan
          sources={SOURCES}
          loading={false}
          mapping={MAPPING}
          kept={MAPPING}
          mappings={{}}
          counts={importCounts(SOURCES, MAPPING)}
          planEmpty={false}
          teams={teams}
          teamId={teams[0]?.id ?? ""}
          teamRequired
          onTeam={noop}
          onCycle={noop}
          onSuggest={noop}
          onNext={noop}
          pending={false}
          busy={busy}
          details={
            <ImportDetails
              bases={[{ sourceId: "base", name: "Tasks", target: "tickets", pages: 4 }]}
              kept={MAPPING}
              mappings={{}}
              fallbacks={{}}
              teams={[]}
              projects={[]}
              plan={[]}
              onMapping={noop}
              onSeed={noop}
              onFallback={noop}
              onPeople={noop}
              onLoading={setBusy}
            />
          }
        />
      );
    }

    return render(<Harness />, { wrapper });
  }

  const previewButton = () =>
    screen.getByRole("button", { name: /preview the import/i }) as HTMLButtonElement;

  it("stays disabled while a base's schema is still in flight", async () => {
    SCHEMA.mockImplementation(() => new Promise<NotionImportSchema>(() => {}));
    pending();

    await waitFor(() => expect(previewButton().disabled).toBe(true));
    expect(screen.getByText(/waiting for the columns and the people/i)).not.toBeNull();
  });

  it("re-enables once the schema answers", async () => {
    let resolve: (schema: NotionImportSchema) => void = () => {};
    SCHEMA.mockImplementation(() => new Promise<NotionImportSchema>((r) => (resolve = r)));
    pending();

    await waitFor(() => expect(previewButton().disabled).toBe(true));

    resolve({
      sourceId: "base",
      target: "tickets",
      columns: [],
      fields: [],
      suggestion: { columns: {}, values: {} },
      defaults: {},
    });

    await waitFor(() => expect(previewButton().disabled).toBe(false));
  });
});
