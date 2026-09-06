import { fireEvent, render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import type { DocBlock, DocBlockLock } from "@/lib/api";
import { BlockBody } from "./blocks";
import { BlockLockBadge } from "./block-lock-badge";
import { DocPresence } from "./presence";

/**
 * What the person who cannot type actually sees — `KAN-25`.
 *
 * The pure functions in `doc-locks.test.ts` prove the *sentence*; this proves it reaches a
 * screen, and that the paragraph under it refuses typing without disappearing. That gap is
 * exactly where this repository has been bitten before: `useReportError` exists because a
 * refusal was computed correctly and rendered nowhere on four routes.
 */

const lock = (extra: Partial<DocBlockLock> = {}): DocBlockLock => ({
  userId: "marie",
  displayName: "Marie",
  freesAt: new Date(Date.now() + 25_000).toISOString(),
  takenAt: new Date().toISOString(),
  ...extra,
});

/** `lockedBy` omitted, not null — the shape the wire actually sends. */
const block = (extra: Partial<DocBlock> = {}): DocBlock => ({
  id: "b1",
  pageId: "p1",
  position: 0,
  kind: "paragraph",
  content: { text: "Postgres wins" },
  ticketIds: [],
  ...extra,
});

const body = (props: Partial<Parameters<typeof BlockBody>[0]> = {}) =>
  render(
    <BlockBody
      block={block()}
      tickets={[]}
      teamTickets={[]}
      onCommit={() => {}}
      onFocus={() => {}}
      onBlur={() => {}}
      onKeyDown={() => {}}
      {...props}
    />,
  );

describe("the badge on a held block", () => {
  it("names the holder and counts down", () => {
    render(<BlockLockBadge lock={lock()} />);

    expect(screen.getByTestId("doc-block-lock").textContent).toContain("Marie");
    expect(screen.getByTestId("doc-block-lock").textContent).toMatch(/\d+ s/);
  });

  it("says all three things in the label a screen reader announces", () => {
    render(<BlockLockBadge lock={lock()} />);

    const label = screen.getByTestId("doc-block-lock").getAttribute("aria-label") ?? "";

    // Who, that it ends by itself, and when. Dropping the third turns "wait" into
    // "give up" — a reader not told the block comes back has no reason to believe it will.
    expect(label).toContain("Marie");
    expect(label).toContain("frees itself");
    expect(label).toMatch(/\d+ s/);
  });

  it("stops counting rather than going negative once the claim has lapsed", () => {
    render(<BlockLockBadge lock={lock({ freesAt: new Date(Date.now() - 5_000).toISOString() })} />);

    expect(screen.getByTestId("doc-block-lock").textContent).toBe("Marie · freeing");
  });
});

describe("a block somebody else is holding", () => {
  it("refuses typing without vanishing from the tab order", () => {
    body({ locked: true });

    const field = screen.getByRole("textbox") as HTMLTextAreaElement;
    // `readOnly`, not `disabled`. A disabled textarea cannot be focused, so a keyboard
    // reader would find the paragraph simply absent — and reading it is what somebody
    // waiting for a block wants to do.
    expect(field.readOnly).toBe(true);
    expect(field.disabled).toBe(false);
    expect(field.value).toBe("Postgres wins");
  });

  it("takes typing again once nobody holds it", () => {
    body({ locked: false });

    expect((screen.getByRole("textbox") as HTMLTextAreaElement).readOnly).toBe(false);
  });

  it("locks the checkbox too, because ticking one is the same refused write", () => {
    body({
      block: block({ kind: "checkbox", content: { text: "Echo suppression", checked: false } }),
      locked: true,
    });

    expect((screen.getByRole("checkbox") as HTMLInputElement).disabled).toBe(true);
  });

  it("locks the list's add button, which appends an item to the same block", () => {
    body({
      block: block({ kind: "numbered_list", content: { items: ["one"] } }),
      locked: true,
    });

    expect((screen.getByRole("button", { name: "+ one more" }) as HTMLButtonElement).disabled).toBe(
      true,
    );
  });

  it("keeps showing the holder's edits even while this reader has clicked into it", () => {
    // Found by the two-browser e2e, and it is the one case the whole feature exists for:
    // watching somebody else write. `Autosize` refuses an incoming value while the field
    // has focus, to protect an unsent draft — but a read-only field cannot have one, and
    // without the exception clicking into a locked paragraph froze it for good.
    const { rerender } = render(
      <BlockBody
        block={block({ content: { text: "Context" } })}
        tickets={[]}
        teamTickets={[]}
        onCommit={() => {}}
        onFocus={() => {}}
        onBlur={() => {}}
        onKeyDown={() => {}}
        locked
      />,
    );

    const field = screen.getByRole("textbox") as HTMLTextAreaElement;
    field.focus();
    expect(document.activeElement).toBe(field);

    rerender(
      <BlockBody
        block={block({ content: { text: "Postgres wins" } })}
        tickets={[]}
        teamTickets={[]}
        onCommit={() => {}}
        onFocus={() => {}}
        onBlur={() => {}}
        onKeyDown={() => {}}
        locked
      />,
    );

    expect(field.value).toBe("Postgres wins");
  });

  it("still protects an unsent draft in a block this reader may write in", () => {
    // The other half, and the reason the guard exists at all: a refetch arriving
    // mid-sentence must not replace what somebody is typing. What makes this the protected
    // case and the one above the unprotected one is a *draft*, not a caret.
    const { rerender } = render(
      <BlockBody
        block={block({ content: { text: "Context" } })}
        tickets={[]}
        teamTickets={[]}
        onCommit={() => {}}
        onFocus={() => {}}
        onBlur={() => {}}
        onKeyDown={() => {}}
      />,
    );

    const field = screen.getByRole("textbox") as HTMLTextAreaElement;
    field.focus();
    fireEvent.change(field, { target: { value: "Context, and my unsent sentence" } });

    rerender(
      <BlockBody
        block={block({ content: { text: "somebody else's paragraph" } })}
        tickets={[]}
        teamTickets={[]}
        onCommit={() => {}}
        onFocus={() => {}}
        onBlur={() => {}}
        onKeyDown={() => {}}
      />,
    );

    expect(field.value).toBe("Context, and my unsent sentence");
  });

  it("takes the new text when the caret is merely resting, with nothing typed", () => {
    // The correction `KAN-25` made to a rule that predates it: focus alone is not a draft.
    // A reader who clicked into a paragraph to copy a line out of it used to see it freeze.
    const { rerender } = render(
      <BlockBody
        block={block({ content: { text: "Context" } })}
        tickets={[]}
        teamTickets={[]}
        onCommit={() => {}}
        onFocus={() => {}}
        onBlur={() => {}}
        onKeyDown={() => {}}
      />,
    );

    const field = screen.getByRole("textbox") as HTMLTextAreaElement;
    field.focus();

    rerender(
      <BlockBody
        block={block({ content: { text: "somebody else's paragraph" } })}
        tickets={[]}
        teamTickets={[]}
        onCommit={() => {}}
        onFocus={() => {}}
        onBlur={() => {}}
        onKeyDown={() => {}}
      />,
    );

    expect(field.value).toBe("somebody else's paragraph");
  });

  it("locks every cell of a table, not only the first", () => {
    body({
      block: block({
        kind: "table",
        content: { columns: ["Ticket", "State"], rows: [["KAN-1", "todo"]] },
      }),
      locked: true,
    });

    // The `editable` object is spread into every `Autosize` in every kind, which is what
    // makes the lock reach all seven without seven chances to forget one. A table is the
    // kind with the most of them.
    const cells = screen.getAllByRole("textbox") as HTMLTextAreaElement[];
    expect(cells).toHaveLength(2);
    for (const cell of cells) expect(cell.readOnly).toBe(true);
  });
});

describe("who else is reading", () => {
  it("draws a chip per person, named for a screen reader", () => {
    render(
      <DocPresence
        viewers={[
          { userId: "u1", displayName: "Marie Dupont" },
          { userId: "u2", displayName: "Élie" },
        ]}
      />,
    );

    expect(screen.getAllByTestId("doc-viewer")).toHaveLength(2);
    expect(screen.getByTestId("doc-viewers").getAttribute("aria-label")).toBe(
      "Also reading: Marie Dupont, Élie",
    );
  });

  it("initials a one-word name from its first character, whatever that character is", () => {
    // Dev mode makes accounts out of e-mail local parts, so this sees odder names than a
    // product with a sign-up form would. `Array.from` rather than `[0]`, or a name
    // starting outside the BMP renders as a replacement glyph.
    render(<DocPresence viewers={[{ userId: "u1", displayName: "Élie" }]} />);

    expect(screen.getByTestId("doc-viewer").textContent).toBe("É");
  });

  it("draws nothing at all when nobody else is here", () => {
    const { container } = render(<DocPresence viewers={[]} />);

    // Not an empty row taking space in the bar: "nobody else" and "one other person" have
    // to look different at a glance, and an empty container reads as a thing that failed
    // to load.
    expect(container.innerHTML).toBe("");
  });
});
