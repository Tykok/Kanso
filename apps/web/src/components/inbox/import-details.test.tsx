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
        onMapping={noop}
        onSeed={onSeed}
        onFallback={noop}
        onPeople={noop}
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
        onMapping={noop}
        onSeed={noop}
        onFallback={noop}
        onPeople={(people) => calls.push(people)}
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
          onMapping={noop}
          onSeed={(sourceId, seed) => setMappings((current) => ({ ...current, [sourceId]: seed }))}
          onFallback={noop}
          onPeople={noop}
        />
      );
    }

    render(<Harness />, { wrapper: wrapper() });

    expect(screen.getByText(/nothing mapped yet/i)).not.toBeNull();
    await waitFor(() => expect(screen.getByText(/1 field guessed/i)).not.toBeNull());
  });
});

describe("closing the fold on a person the reader already matched", () => {
  it("keeps the match instead of asking StepPeople to forget it", async () => {
    SEEN.mockResolvedValue([{ id: "notion-1", name: "Ada" }]);
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
        onMapping={noop}
        onSeed={noop}
        onFallback={noop}
        onPeople={(people) => calls.push(people)}
      />,
      { wrapper: wrapper() },
    );

    toggle(); // open — the row is there already, mounting was never conditional on this
    const select = await screen.findByRole("combobox");
    fireEvent.change(select, { target: { value: "user-1" } });
    await waitFor(() => expect(calls.at(-1)).toEqual({ "notion-1": "user-1" }));

    toggle(); // close
    toggle(); // reopen

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
