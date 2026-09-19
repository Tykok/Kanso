import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import type { NotionImportPlanRow } from "@/lib/api";
import { ImportDetails } from "./import-details";

/**
 * The fold must not cost the reader a match they already made.
 *
 * `StepPeople` keeps `edits` in a `useState` of its own — its own comment argues why: a
 * seeded guess must not be written in until somebody has actually looked at it. Mounting
 * it only under `{open && …}` unmounts it on every fold, which throws `edits` away with
 * it; on reopen it recomputes assignments from an empty map and tells the shell an
 * emptier one than the reader left it with. Columns need no such test — their state lives
 * in the shell, not in `StepColumns`.
 */

const SEEN = vi.hoisted(() => [{ id: "notion-1", name: "Ada" }]);
const VIEW = vi.hoisted(() => ({ available: true, people: [] }));
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
    notionImportApi: { ...actual.notionImportApi, peopleSeen: async () => SEEN },
    notionPeopleApi: { ...actual.notionPeopleApi, view: async () => VIEW },
  };
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

const noop = () => {};

function details() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  );
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
    { wrapper },
  );
  return calls;
}

const toggle = () => fireEvent.click(screen.getByRole("button", { name: /columns and people/i }));

describe("closing the fold on a person the reader already matched", () => {
  it("keeps the match instead of asking StepPeople to forget it", async () => {
    const calls = details();

    toggle(); // first open — mounts StepPeople, which fires its own request
    const select = await screen.findByRole("combobox");
    fireEvent.change(select, { target: { value: "user-1" } });
    await waitFor(() => expect(calls.at(-1)).toEqual({ "notion-1": "user-1" }));

    toggle(); // close
    toggle(); // reopen

    // Still there in the UI, not reverted to "Unmatched" by a fresh mount.
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
