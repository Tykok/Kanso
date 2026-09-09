import { render, screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createRef } from "react";
import { expect, it } from "vitest";
import type { BoardColumn } from "./columns";
import { BoardColumnView, type ColumnControl } from "./column";

/**
 * The word above a column, on a screen — the half `columns.test.ts` cannot reach.
 *
 * `BoardColumn` has carried a `label` since `KAN-90` precisely so a header never has to
 * look a key up in a table of Kanso's six. This proves the header actually reads it: a
 * team's own word and a category's word are both keys that table has never heard of, and
 * a lookup answers them with the raw key.
 */

const control: ColumnControl = {
  nameOf: () => undefined,
  onSelect: () => {},
  onOpen: () => {},
  onDrop: () => {},
  onDragStart: () => {},
  onDragEnd: () => {},
};

const draw = (column: BoardColumn) =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <BoardColumnView
        column={column}
        board={createRef<HTMLDivElement>()}
        control={control}
        dragging={null}
      />
    </QueryClientProvider>,
  );

it("prints the team's own word above its own column", () => {
  draw({ status: "devis", label: "Devis", category: "backlog", tickets: [] });

  expect(screen.getByText("Devis")).toBeTruthy();
});

it("prints the category's word above a column on a board spanning teams", () => {
  // `started` is the wire's spelling and is on no screen — the reader chose none of it.
  draw({
    status: "started",
    label: "In flight",
    category: "started",
    tickets: [],
  });

  expect(screen.getByText("In flight")).toBeTruthy();
  expect(screen.queryByText("started")).toBeNull();
});

it("names the column in the label a screen reader hears, too", () => {
  draw({
    status: "started",
    label: "In flight",
    category: "started",
    tickets: [],
  });

  expect(screen.getByLabelText("In flight, 0")).toBeTruthy();
});

it("draws the dot in the colour of what the column means", () => {
  // `statuses.ts` says it outright — where the category is in hand, and the board's own
  // columns are the case it names, the colour is read through it rather than through the
  // key. Read through the key, a word Kanso does not ship comes out `var(--faint)`, and
  // a board of a team's own words would be five grey dots.
  const { container } = draw({
    status: "en_cours",
    label: "En cours",
    category: "started",
    tickets: [],
  });

  const dot = container.querySelector("header > span[aria-hidden]");
  expect((dot as HTMLElement).style.color).toBe("var(--status-progress)");
});
