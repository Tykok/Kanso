"use client";

import { useState } from "react";
import type { DocBlockContent, DocBlockKind } from "@/lib/api";
import { Backdrop } from "../overlays";
import { Kbd } from "../ui/kbd";

/**
 * What `/` opens: the seven blocks, and the empty content each one starts on.
 *
 * The starting content is here rather than on the server because it is a *drawing*
 * decision — a numbered list opens with one empty item because that is what somebody who
 * pressed `/` and chose "numbered list" is about to type into. The server only refuses a
 * shape that does not match its kind.
 */
const CHOICES: { kind: DocBlockKind; label: string; hint: string; content: DocBlockContent }[] = [
  { kind: "paragraph", label: "Text", hint: "A plain run", content: { text: "" } },
  { kind: "heading", label: "Heading", hint: "Enters the table of contents", content: { text: "", level: 2 } },
  { kind: "numbered_list", label: "Numbered list", hint: "Steps, in order", content: { items: [""] } },
  { kind: "checkbox", label: "Checkbox", hint: "Something still to do", content: { text: "", checked: false } },
  { kind: "callout", label: "Callout", hint: "The decision, set apart", content: { title: "", text: "" } },
  {
    kind: "table",
    label: "Table",
    hint: "Two columns to start",
    content: { columns: ["", ""], rows: [["", ""]] },
  },
];

/**
 * The insert menu, on the `blockInsert` overlay `store/ui.ts` already carries.
 *
 * `ticket_link` is absent on purpose: `#` inserts one, and it needs a ticket to point at
 * — a "ticket link" entry here would create a block referring to nothing, which is the
 * one state `blocks.tsx` has to draw an apology for.
 */
export function InsertMenu({
  onChoose,
  onClose,
}: {
  onChoose: (kind: DocBlockKind, content: DocBlockContent) => void;
  onClose: () => void;
}) {
  const [filter, setFilter] = useState("");
  const matching = CHOICES.filter((choice) =>
    choice.label.toLowerCase().includes(filter.trim().toLowerCase()),
  );

  return (
    <Backdrop onClose={onClose} panelClassName="w-[420px]">
      <div className="flex flex-col gap-2 p-3">
        <input
          autoFocus
          data-testid="insert-filter"
          aria-label="Insert a block"
          placeholder="Insert a block…"
          value={filter}
          className="w-full"
          onChange={(event) => setFilter(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && matching[0]) {
              event.preventDefault();
              onChoose(matching[0].kind, matching[0].content);
            }
            // Handled here rather than left to bubble: the line below stops it, and it
            // has to, because every character of a document would otherwise reach the
            // shell's own key dispatcher.
            if (event.key === "Escape") {
              event.preventDefault();
              onClose();
            }
            event.stopPropagation();
          }}
        />

        <div className="flex flex-col">
          {matching.map((choice) => (
            <button
              key={choice.kind}
              data-testid="insert-choice"
              className="flex items-baseline gap-3 rounded-md px-2 py-2 text-left hover:bg-accent"
              onClick={() => onChoose(choice.kind, choice.content)}
            >
              <span className="text-13 text-foreground">{choice.label}</span>
              <span className="text-11 text-faint">{choice.hint}</span>
            </button>
          ))}
          {matching.length === 0 && (
            <span className="px-2 py-2 text-12 text-faint">No block by that name.</span>
          )}
        </div>

        <div className="flex items-center gap-2 px-2 pt-1 text-11 text-faint">
          <Kbd>#</Kbd> mention a ticket
          <Kbd>@</Kbd> a person
          <Kbd>c</Kbd> a new ticket, linked
        </div>
      </div>
    </Backdrop>
  );
}
