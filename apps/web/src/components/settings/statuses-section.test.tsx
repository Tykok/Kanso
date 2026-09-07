import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import type { Team } from "@/lib/api";
import { StatusesSection } from "./statuses-section";

/**
 * The screen where a team writes its own words — `KAN-28`.
 *
 * Two things are worth a rendered test rather than a pure one. The duplicate refusal is a
 * sentence somebody reads, and this repository has shipped a rule computed correctly and
 * rendered nowhere before. And a reorder sends the *whole* list — the server refuses a
 * partial one — so what the buttons actually put on the wire is the assertion.
 */

const rename = vi.fn();
const reorder = vi.fn();

vi.mock("@/lib/queries", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/queries")>()),
  useTeams: () => ({ data: [support] }),
  useRenameStatus: () => ({ mutate: rename, isPending: false, error: null }),
  useReorderStatuses: () => ({ mutate: reorder, isPending: false, error: null }),
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
});
