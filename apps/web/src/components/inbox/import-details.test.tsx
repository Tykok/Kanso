import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { useState, type ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { NotionImportPlanRow } from "@/lib/api";
import type { BaseMapping, NotionImportSchema } from "./import-columns";
import { ImportDetails } from "./import-details";
import type { ImportPlanEntry } from "./import-map";

/**
 * What the panel must do without anybody ever unfolding it.
 *
 * Columns and people fold because they only ever confirm a guess, but the guess still has
 * to reach the request: neither `NotionImportService` nor `TicketImport.resolveAssignees`
 * fall back to a suggestion of their own when the client sends nothing (see
 * `import-details.tsx`'s own comment for the server-side citations). So `StepColumns` and
 * `StepPeople` are mounted unconditionally, and the two tests below are what would have
 * caught the regression if they had existed before it shipped: a plan the server has
 * already guessed at must reach the shell's `mappings` and `people` with no interaction at
 * all.
 *
 * The third describe block covers round 1's defect instead — closing the fold must not
 * cost the reader a match they already made — which unconditional mounting subsumes but
 * does not obviously prove; the test still exercises the open/close/reopen path directly.
 *
 * The fourth is finding 2 from the whole-branch review: closing the fold is not the only
 * way this component disappears. `Back` from the confirmation unmounts it outright, and a
 * match kept in `StepPeople`'s own state did not survive that — only a fold did. `edits`
 * is lifted to `import-dialog.tsx` for it, and that describe block proves the lift by
 * actually tearing `ImportDetails` down and remounting it, not merely hiding it.
 */

const SEEN = vi.hoisted(() => vi.fn());
const VIEW = vi.hoisted(() => vi.fn());
const SCHEMA = vi.hoisted(() => vi.fn());
const MEMBERS = vi.hoisted(() => [
  {
    id: "user-1",
    email: "ada@example.com",
    displayName: "Ada Lovelace",
    instanceRole: "member",
    hasPassword: true,
  },
]);

vi.mock("@/lib/queries", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/queries")>();
  return { ...actual, usePeople: () => ({ data: MEMBERS }) };
});

vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return {
    ...actual,
    notionImportApi: { ...actual.notionImportApi, peopleSeen: SEEN, schema: SCHEMA },
    notionPeopleApi: { ...actual.notionPeopleApi, view: VIEW },
  };
});

beforeEach(() => {
  SEEN.mockReset().mockResolvedValue([]);
  VIEW.mockReset().mockResolvedValue({ available: true, people: [] });
  SCHEMA.mockReset().mockResolvedValue(undefined);
});

const PLAN: NotionImportPlanRow[] = [
  {
    sourceId: "base",
    target: "tickets",
    columns: { assignees: "Assignee" },
    values: {},
    fallback: {},
  },
];

const BASES: ImportPlanEntry[] = [{ sourceId: "base", name: "Tasks", target: "tickets", pages: 4 }];

const SUGGESTED_SCHEMA: NotionImportSchema = {
  sourceId: "base",
  target: "tickets",
  columns: [{ name: "Status", type: "status", options: ["Todo", "Done"] }],
  fields: [{ field: "status", candidates: ["Status"], prefill: {} }],
  suggestion: { columns: { status: "Status" }, values: {} },
  defaults: { status: null },
};

const noop = () => {};

function wrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
}

const toggle = () => fireEvent.click(screen.getByRole("button", { name: /columns and people/i }));

describe("what reaches the shell before anybody unfolds the panel", () => {
  it("seeds the server's column suggestion with no interaction at all", async () => {
    SCHEMA.mockResolvedValue(SUGGESTED_SCHEMA);
    const onSeed = vi.fn();

    render(
      <ImportDetails
        bases={BASES}
        kept={{ base: "tickets" }}
        mappings={{}}
        fallbacks={{}}
        teams={[]}
        projects={[]}
        plan={[]}
        edits={{}}
        onMapping={noop}
        onSeed={onSeed}
        onFallback={noop}
        onEdit={noop}
        onPeople={noop}
        onLoading={noop}
      />,
      { wrapper: wrapper() },
    );

    // Never opened. `StepColumns` still fetches the schema and seeds it regardless.
    await waitFor(() =>
      expect(onSeed).toHaveBeenCalledWith("base", { columns: { status: "Status" }, values: {} }),
    );
  });

  it("carries the standing person correspondence with no interaction at all", async () => {
    SEEN.mockResolvedValue([{ id: "notion-1", name: "Ada" }]);
    VIEW.mockResolvedValue({
      available: true,
      people: [{ notion: { id: "notion-1", name: "Ada" }, userId: "user-1" }],
    });
    const calls: Record<string, string | null>[] = [];

    render(
      <ImportDetails
        bases={[]}
        kept={{}}
        mappings={{}}
        fallbacks={{}}
        teams={[]}
        projects={[]}
        plan={PLAN}
        edits={{}}
        onMapping={noop}
        onSeed={noop}
        onFallback={noop}
        onEdit={noop}
        onPeople={(people) => calls.push(people)}
        onLoading={noop}
      />,
      { wrapper: wrapper() },
    );

    // Never opened. The correspondence `notion-people` already confirmed still reaches the
    // shell, because `StepPeople` mounts and runs its effect either way.
    await waitFor(() => expect(calls.at(-1)).toEqual({ "notion-1": "user-1" }));

    // The lid is telling the truth too: a person is matched, not "nothing mapped yet".
    expect(screen.getByText(/1 person matched/i)).not.toBeNull();
  });

  /**
   * The lid specifically: `detailsSummary` reads the shell's own `mappings`, so proving it
   * is right requires a stand-in for the shell that actually carries `onSeed` back into a
   * `mappings` state the way `import-dialog.tsx` does — a plain spy proves the call
   * happened, not that the count painted on screen would have been real.
   */
  it("paints a real count on the lid, not the empty-handed default", async () => {
    SCHEMA.mockResolvedValue(SUGGESTED_SCHEMA);

    function Harness() {
      const [mappings, setMappings] = useState<Record<string, BaseMapping>>({});
      return (
        <ImportDetails
          bases={BASES}
          kept={{ base: "tickets" }}
          mappings={mappings}
          fallbacks={{}}
          teams={[]}
          projects={[]}
          plan={[]}
          edits={{}}
          onMapping={noop}
          onSeed={(sourceId, seed) => setMappings((current) => ({ ...current, [sourceId]: seed }))}
          onFallback={noop}
          onEdit={noop}
          onPeople={noop}
          onLoading={noop}
        />
      );
    }

    render(<Harness />, { wrapper: wrapper() });

    expect(screen.getByText(/nothing mapped yet/i)).not.toBeNull();
    await waitFor(() => expect(screen.getByText(/1 field guessed/i)).not.toBeNull());
  });
});

/** A stand-in for `import-dialog.tsx`'s own `edits` state and `setEdit` callback. */
function EditsHarness({
  children,
}: {
  children: (props: {
    edits: Record<string, string | null>;
    onEdit: (id: string, value: string | null) => void;
  }) => ReactNode;
}) {
  const [edits, setEdits] = useState<Record<string, string | null>>({});
  const onEdit = (id: string, value: string | null) =>
    setEdits((current) => ({ ...current, [id]: value }));
  return <>{children({ edits, onEdit })}</>;
}

describe("closing the fold on a person the reader already matched", () => {
  it("keeps the match instead of asking StepPeople to forget it", async () => {
    SEEN.mockResolvedValue([{ id: "notion-1", name: "Ada" }]);
    const calls: Record<string, string | null>[] = [];

    render(
      <EditsHarness>
        {({ edits, onEdit }) => (
          <ImportDetails
            bases={[]}
            kept={{}}
            mappings={{}}
            fallbacks={{}}
            teams={[]}
            projects={[]}
            plan={PLAN}
            edits={edits}
            onMapping={noop}
            onSeed={noop}
            onFallback={noop}
            onEdit={onEdit}
            onPeople={(people) => calls.push(people)}
            onLoading={noop}
          />
        )}
      </EditsHarness>,
      { wrapper: wrapper() },
    );

    toggle(); // open — the row is there already, mounting was never conditional on this

    // Folded shut, the body is `display: none` rather than gone from the DOM — the whole
    // reason a close and a reopen on this screen do not cost `StepPeople` its state.
    const body = screen.getByTestId("import-details-body");
    expect(body.style.display).toBe("");

    const select = await screen.findByRole("combobox");
    fireEvent.change(select, { target: { value: "user-1" } });
    await waitFor(() => expect(calls.at(-1)).toEqual({ "notion-1": "user-1" }));

    toggle(); // close
    expect(body.style.display).toBe("none");
    toggle(); // reopen
    expect(body.style.display).toBe("");

    // Still there in the UI, not reverted to "Unmatched" by a remount.
    await waitFor(() => {
      const reopened = screen.getByRole("combobox") as HTMLSelectElement;
      expect(reopened.value).toBe("user-1");
    });

    // And the shell was never told an emptier map in between — a remount would have
    // recomputed assignments from a blank `edits` and reported `{}` on the way back.
    const editedAt = calls.findIndex((call) => call["notion-1"] === "user-1");
    expect(editedAt).toBeGreaterThan(-1);
    for (const call of calls.slice(editedAt)) {
      expect(call).toEqual({ "notion-1": "user-1" });
    }
  });
});

/**
 * Finding 2: `Back` from the confirmation unmounts `ImportPlan` and everything folded
 * beneath it, `ImportDetails` included — a different, harsher event than folding shut,
 * which only hides the body behind `display: none`. Before this fix `edits` was
 * `StepPeople`'s own state, so it did not survive that unmount: a match made, then a trip
 * to the confirmation screen and back, silently reverted to "Unmatched" because the fresh
 * `StepPeople` instance re-seeded itself from the standing correspondence with no memory
 * of the edit. `edits` is lifted to `import-dialog.tsx` for exactly this — proved here by
 * actually unmounting `ImportDetails`, not merely folding it, and rendering a fresh
 * instance from the same lifted `edits` the way `import-dialog.tsx` does across a Back.
 */
describe("a person match surviving a real unmount, not only a fold", () => {
  it("keeps the match after ImportDetails itself is torn down and remounted", async () => {
    SEEN.mockResolvedValue([{ id: "notion-1", name: "Ada" }]);

    function Harness() {
      const [edits, setEdits] = useState<Record<string, string | null>>({});
      const [onPlanScreen, setOnPlanScreen] = useState(true);
      const onEdit = (id: string, value: string | null) =>
        setEdits((current) => ({ ...current, [id]: value }));

      return (
        <>
          <button type="button" onClick={() => setOnPlanScreen((shown) => !shown)}>
            go to the confirmation screen and back
          </button>
          {onPlanScreen ? (
            <ImportDetails
              bases={[]}
              kept={{}}
              mappings={{}}
              fallbacks={{}}
              teams={[]}
              projects={[]}
              plan={PLAN}
              edits={edits}
              onMapping={noop}
              onSeed={noop}
              onFallback={noop}
              onEdit={onEdit}
              onPeople={noop}
              onLoading={noop}
            />
          ) : (
            // The confirmation screen draws none of this — the point is that `ImportPlan`
            // and `ImportDetails` are not merely hidden while it is up.
            <span>the confirmation screen</span>
          )}
        </>
      );
    }

    render(<Harness />, { wrapper: wrapper() });

    toggle();
    const select = await screen.findByRole("combobox");
    fireEvent.change(select, { target: { value: "user-1" } });
    await waitFor(() => expect((screen.getByRole("combobox") as HTMLSelectElement).value).toBe("user-1"));

    const stepScreen = () =>
      fireEvent.click(screen.getByRole("button", { name: /confirmation screen and back/i }));

    stepScreen(); // to the confirmation screen — ImportDetails is gone, not hidden
    expect(screen.queryByRole("combobox")).toBeNull();
    expect(screen.getByText("the confirmation screen")).not.toBeNull();

    stepScreen(); // back — a fresh ImportDetails, fed the same lifted edits
    toggle();

    await waitFor(() => {
      const reopened = screen.getByRole("combobox") as HTMLSelectElement;
      expect(reopened.value).toBe("user-1");
    });
  });
});
