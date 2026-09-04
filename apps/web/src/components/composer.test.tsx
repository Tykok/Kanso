import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Project, Team } from "@/lib/api";
import { ComposerForm } from "./composer";

/**
 * What the composer says when it will not create a ticket.
 *
 * `newTicketBody` and `chosenEstimate` have been tested since they were written, because
 * they are functions; the refusal was not, because it is a render. `if (!trimmed) return;`
 * passed every one of those tests by doing nothing at all — the assertion that catches it
 * has to be about what a person sees, and until `.tsx` tests got a document there was
 * nowhere to put one.
 *
 * `ComposerForm` is exported for this file. The alternative was rendering `Composer`, which
 * would mean standing up four queries and `/api/me` to reach a form that needs none of
 * them: the refusal is the form's, and so is the test.
 */

const { createTicket } = vi.hoisted(() => ({ createTicket: vi.fn() }));

// Partial, because the form reads `EFFORT_POINTS` and `TICKET_PRIORITIES` from the same
// module and those are the real vocabulary the selects are built from.
vi.mock("@/lib/api", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api")>();
  return { ...actual, api: { ...actual.api, createTicket } };
});

/** A team the composer may file into, and a project of it. Only the fields this form reads. */
const CORE = { id: "team-core", name: "Core", archived: false, editable: true } as Team;
const PROJECTS: Project[] = [];

/** `retry: false` so a rejected mutation settles once — nothing here waits on a second try. */
function mount(props: { teams: Team[]; teamCount: number }) {
  const client = new QueryClient({ defaultOptions: { mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ComposerForm
        teams={props.teams}
        teamCount={props.teamCount}
        projects={PROJECTS}
        users={[]}
        scope={{ kind: "all" }}
        onClose={() => {}}
      />
    </QueryClientProvider>,
  );
}

// The counts below are per-test, so a mock carried between them would make the order of
// this file part of what it asserts.
beforeEach(() => createTicket.mockClear());

const title = () => screen.getByPlaceholderText("New ticket…");

/** No `@testing-library/jest-dom` in this root, so the text is read off the node. */
const refusal = () => screen.getByRole("alert").textContent;

describe("the composer's refusal of an empty title", () => {
  it("says what is missing when ↵ arrives on nothing", () => {
    mount({ teams: [CORE], teamCount: 1 });
    fireEvent.keyDown(title(), { key: "Enter" });

    // The assertion the bare `return` failed: not "nothing was created" — that part it got
    // right — but that the reader was told why.
    expect(refusal()).toBe("A ticket needs a title.");
    expect(createTicket).not.toHaveBeenCalled();
  });

  it("says it for the click as well, and leaves the button live to be clicked", () => {
    mount({ teams: [CORE], teamCount: 1 });
    const create = screen.getByRole("button", { name: "Create" }) as HTMLButtonElement;

    // Disabling it was the other way to settle this, and this is the line that records the
    // choice: greying the button would have answered the click and left ↵ silent.
    expect(create.disabled).toBe(false);
    fireEvent.click(create);
    expect(refusal()).toBe("A ticket needs a title.");
  });

  it("refuses whitespace, which is what makes the trim part of the rule", () => {
    mount({ teams: [CORE], teamCount: 1 });
    fireEvent.change(title(), { target: { value: "   " } });
    fireEvent.keyDown(title(), { key: "Enter" });

    expect(refusal()).toBe("A ticket needs a title.");
    expect(createTicket).not.toHaveBeenCalled();
  });

  it("withdraws the objection as soon as the field is being filled", () => {
    mount({ teams: [CORE], teamCount: 1 });
    fireEvent.keyDown(title(), { key: "Enter" });
    fireEvent.change(title(), { target: { value: "A" } });

    // Otherwise the sentence outlives the thing it was about, and reads as a refusal of the
    // title now in the box.
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("still creates when there is a title, which is what keeps the four above honest", async () => {
    mount({ teams: [CORE], teamCount: 1 });
    fireEvent.change(title(), { target: { value: "  Ship it  " } });
    fireEvent.keyDown(title(), { key: "Enter" });

    // A form that refused everything would satisfy every assertion above this one.
    await vi.waitFor(() => expect(createTicket).toHaveBeenCalledTimes(1));
    expect(createTicket.mock.calls[0][0]).toMatchObject({ title: "Ship it" });
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("why the team select is empty", () => {
  it("says the instance has no team yet", () => {
    mount({ teams: [], teamCount: 0 });

    // `composerEmptyReason`'s "no-teams", drawn. It was exported and called by nothing, so
    // this sentence existed only in its comment.
    expect(screen.getByText("No team on this instance yet — this files as a draft.")).toBeTruthy();
  });

  it("says a team exists but will not take this actor's tickets", () => {
    mount({ teams: [], teamCount: 3 });

    expect(screen.getByText("No team here takes your tickets — this files as a draft.")).toBeTruthy();
  });

  it("keeps the ordinary no-team sentence when there is something to pick", () => {
    mount({ teams: [CORE], teamCount: 1 });

    // The distinction the two above would pass for the wrong reason without: a composable
    // list means the select has an answer, and the footer says what happens rather than why.
    expect(screen.getByText("Files with no team — you can attach one later.")).toBeTruthy();
  });
});
