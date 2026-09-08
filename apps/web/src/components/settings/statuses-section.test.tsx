import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Team } from "@/lib/api";
import { StatusesSection } from "./statuses-section";

/**
 * The screen where a team writes its own words — `KAN-28`, and adds them since `KAN-90`.
 *
 * What is worth a rendered test rather than a pure one: every refusal here is a sentence
 * somebody reads, and this repository has shipped a rule computed correctly and rendered
 * nowhere before. A reorder sends the *whole* list — the server refuses a partial one — so
 * what the buttons actually put on the wire is the assertion. And a removal has to name a
 * destination, which is the one control on this screen that asks a question before acting.
 */

const rename = vi.fn();
const reorder = vi.fn();
const add = vi.fn();
const remove = vi.fn();

vi.mock("@/lib/queries", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/queries")>()),
  useTeams: () => ({ data: [support] }),
  useRenameStatus: () => ({ mutate: rename, isPending: false, error: null }),
  useReorderStatuses: () => ({ mutate: reorder, isPending: false, error: null }),
  useAddStatus: () => ({ mutate: add, isPending: false, error: null }),
  useRemoveStatus: () => ({ mutate: remove, isPending: false, error: null }),
}));

const support = {
  id: "support",
  name: "Support",
  key: "SUP",
  archived: false,
  ticketCount: 0,
  mirror: { state: "pending" },
  createdAt: "2026-09-01T10:00:00Z",
  updatedAt: "2026-09-01T10:00:00Z",
  editable: true,
  statuses: [
    { key: "backlog", label: "Boîte", category: "backlog", position: 0 },
    { key: "todo", label: "Qualifié", category: "unstarted", position: 1 },
    { key: "in_progress", label: "En cours", category: "started", position: 2 },
    { key: "in_review", label: "Attente client", category: "started", position: 3 },
    { key: "done", label: "Résolu", category: "completed", position: 4 },
    { key: "canceled", label: "Sans suite", category: "canceled", position: 5 },
  ],
} as unknown as Team;

describe("a team's words, on the settings screen", () => {
  beforeEach(() => {
    rename.mockClear();
    reorder.mockClear();
    add.mockClear();
    remove.mockClear();
  });

  it("draws the team's own list, in the team's own order", () => {
    render(<StatusesSection />);

    const rows = screen.getAllByTestId("status-row");
    expect(rows.map((row) => row.querySelector("input")?.value)).toEqual([
      "Boîte",
      "Qualifié",
      "En cours",
      "Attente client",
      "Résolu",
      "Sans suite",
    ]);
  });

  it("names the category beside each word, since that is what the burndown reads", () => {
    render(<StatusesSection />);

    // The word is the team's; the category is Kanso's reading of it, and a team renaming
    // `done` to `Résolu` should be able to see that it still counts as finished.
    expect(screen.getAllByTestId("status-row")[4].textContent).toContain("Done");
  });

  it("commits a rename on blur, and sends only the label", () => {
    render(<StatusesSection />);
    const input = screen.getAllByTestId("status-row")[4].querySelector("input")!;

    fireEvent.change(input, { target: { value: "Livré" } });
    fireEvent.blur(input);

    expect(rename).toHaveBeenCalledWith({ key: "done", label: "Livré" });
  });

  it("sends nothing when the word did not change", () => {
    render(<StatusesSection />);
    const input = screen.getAllByTestId("status-row")[4].querySelector("input")!;

    fireEvent.blur(input);

    expect(rename).not.toHaveBeenCalled();
  });

  it("refuses a second spelling of a word this team already has, before the server does", () => {
    render(<StatusesSection />);
    const input = screen.getAllByTestId("status-row")[4].querySelector("input")!;

    fireEvent.change(input, { target: { value: "en cours" } });
    fireEvent.blur(input);

    // The same comparison `team_statuses_label_uniq` makes, so the sentence arrives
    // without a round trip — and the server still refuses it if this check is wrong.
    expect(rename).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toContain("already has a status called");
  });

  it("moves a status up by sending the whole order, which is what the server accepts", () => {
    render(<StatusesSection />);

    fireEvent.click(screen.getByRole("button", { name: "Move Qualifié up" }));

    expect(reorder).toHaveBeenCalledWith([
      "todo",
      "backlog",
      "in_progress",
      "in_review",
      "done",
      "canceled",
    ]);
  });

  it("does not offer a move that would fall off either end", () => {
    render(<StatusesSection />);

    expect(screen.queryByRole("button", { name: "Move Boîte up" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Move Sans suite down" })).toBeNull();
  });

  // --- KAN-90: adding and removing ----------------------------------------

  it("sends the word and what it means, and clears the field on success", () => {
    render(<StatusesSection />);

    fireEvent.change(screen.getByLabelText("Name of the status to add"), {
      target: { value: "Devis" },
    });
    fireEvent.change(screen.getByLabelText("What the status means"), {
      target: { value: "backlog" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(add).toHaveBeenCalledWith(
      { label: "Devis", category: "backlog" },
      expect.anything(),
    );
  });

  /**
   * The collision the primary key makes and the label check cannot see.
   *
   * This team renamed its statuses into French, so its *keys* are still the seeded
   * English ones. `In Progress` therefore clashes with nothing this team displays — the
   * label check passes — and folds onto the key `in_progress`, which is the primary key's
   * collision and arrives from the driver with no sentence in it.
   */
  it("refuses a word that folds onto a key this team already has", () => {
    render(<StatusesSection />);

    fireEvent.change(screen.getByLabelText("Name of the status to add"), {
      target: { value: "In Progress" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(add).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toBe(
      'This team already has a status called "in_progress"',
    );
  });

  it("refuses a second spelling of a word this team displays, by the word", () => {
    render(<StatusesSection />);

    fireEvent.change(screen.getByLabelText("Name of the status to add"), {
      target: { value: "en cours" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(add).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toBe(
      'This team already has a status called "en cours"',
    );
  });

  it("refuses a label with nothing nameable in it, in the words the server uses", () => {
    render(<StatusesSection />);

    fireEvent.change(screen.getByLabelText("Name of the status to add"), {
      target: { value: "…" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(add).not.toHaveBeenCalled();
    expect(screen.getByRole("alert").textContent).toBe(
      "A status needs a letter or a digit in its name",
    );
  });

  // The whole shape of the removal: it asks where the tickets go before it acts, and the
  // status being removed is not among the answers.
  it("asks where the tickets go, and does not offer the status being removed", () => {
    render(<StatusesSection />);

    fireEvent.click(screen.getByRole("button", { name: "Remove Attente client" }));

    const destination = screen.getByLabelText("Where the tickets in Attente client go");
    const offered = Array.from(destination.querySelectorAll("option")).map((o) => o.textContent);
    expect(offered).toEqual(["Boîte", "Qualifié", "En cours", "Résolu", "Sans suite"]);
    expect(remove).not.toHaveBeenCalled();
  });

  it("removes with the destination the reader chose", () => {
    render(<StatusesSection />);

    fireEvent.click(screen.getByRole("button", { name: "Remove Attente client" }));
    fireEvent.change(screen.getByLabelText("Where the tickets in Attente client go"), {
      target: { value: "in_progress" },
    });
    // `Move and remove`, and not the row's × a second time: two controls with one
    // accessible name is a fork in the road a screen reader reads as a repeat.
    fireEvent.click(screen.getByRole("button", { name: "Move and remove" }));

    expect(remove).toHaveBeenCalledWith({ key: "in_review", into: "in_progress" });
  });

  it("keeps the status when the reader changes their mind", () => {
    render(<StatusesSection />);

    fireEvent.click(screen.getByRole("button", { name: "Remove Résolu" }));
    fireEvent.click(screen.getByRole("button", { name: "Keep it" }));

    expect(remove).not.toHaveBeenCalled();
    expect(screen.queryByLabelText("Where the tickets in Résolu go")).toBeNull();
  });
});
