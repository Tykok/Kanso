"use client";

import { useEffect, useRef } from "react";
import { Kbd } from "@/components/ui/kbd";
import type { Suggestion } from "@/lib/filter-query";

/**
 * What could go where the caret is, as a list under the box.
 *
 * Its own file because `filter-input.tsx` is the box, the mirror that underlines and the
 * caret bookkeeping, and this is a list of rows: nothing here reads the text, and nothing
 * in the box reads a row. `suggest` has already decided what is offered and in which
 * order, so this file's whole job is to draw it and to say which row `↵` would take.
 *
 * Deliberately not the command palette's component. It looks like it — a list, `↑↓`, a
 * highlight — but the palette is an overlay with a field of its own, and this list has no
 * field: it is anchored under one, it never takes focus, and closing it must not close
 * anything else. Borrowing the frame would mean borrowing the backdrop and the focus trap
 * along with it, and the whole claim of §7 is that filtering stopped being modal.
 */
export function FilterCompletions({
  items,
  listId,
  active,
  pending,
  onActive,
  onAccept,
}: {
  items: readonly Suggestion[];
  /** Named by the input's `aria-controls`, and each row's id by `aria-activedescendant`. */
  listId: string;
  active: number;
  /** The catalogue has not landed. There is nothing to offer *yet*, which is not nothing. */
  pending: boolean;
  onActive: (index: number) => void;
  onAccept: (item: Suggestion) => void;
}) {
  const list = useRef<HTMLDivElement>(null);

  // Kept visible rather than centred, so a long vocabulary — every person in the instance
  // — scrolls by one row under a still highlight instead of jumping.
  useEffect(() => {
    list.current
      ?.querySelector<HTMLElement>('[data-active="true"]')
      ?.scrollIntoView({ block: "nearest" });
  }, [active, items.length]);

  return (
    <div className="absolute left-0 right-0 top-full z-30 mt-1 overflow-hidden rounded-md border border-border bg-popover shadow-float">
      <div ref={list} id={listId} role="listbox" aria-label="Filters" className="max-h-[40vh] overflow-y-auto py-1">
        {items.length === 0 ? (
          <p className="px-3 py-4 text-center text-12 text-faint">
            {pending
              ? "Asking the server which filters it serves…"
              : "Nothing here answers that word."}
          </p>
        ) : (
          items.map((item, index) => {
            // The word to type, then the name it stands for — `e-treport` / `E. Treport`,
            // `todo` / `Todo`. A key is its own name, so it is printed once: `status status`
            // would be the list explaining the language to itself.
            const token = item.insert.replace(/:$/, "");
            const named = item.label.toLowerCase() !== token.toLowerCase();
            return (
            <button
              key={item.id}
              id={`${listId}-${index}`}
              type="button"
              role="option"
              aria-selected={index === active}
              data-testid="filter-suggestion"
              data-active={index === active}
              className={
                index === active
                  ? "flex w-full items-center gap-2.5 bg-accent px-3 py-1.5 text-left text-13 text-foreground"
                  : "flex w-full items-center gap-2.5 px-3 py-1.5 text-left text-13 text-muted-foreground"
              }
              // The box must not lose the caret to a click on a row. `mousedown` is where
              // focus moves, so the row refuses it there and does its work on `click` —
              // otherwise accepting a suggestion with the mouse would blur the input,
              // which is one of the two things that ask the question.
              onMouseDown={(event) => event.preventDefault()}
              onMouseMove={() => onActive(index)}
              onClick={() => onAccept(item)}
            >
              <span className="shrink-0 font-mono text-12">{token}</span>
              {named && <span className="truncate">{item.label}</span>}
              <span className="flex-1" />
              {item.detail && <span className="shrink-0 text-11 text-faint">{item.detail}</span>}
            </button>
            );
          })
        )}
      </div>

      <div className="flex items-center gap-2.5 border-t border-border px-3 py-2 text-11 text-faint">
        <span>
          <Kbd>↑</Kbd> <Kbd>↓</Kbd> move
        </span>
        <span>
          <Kbd>↵</Kbd> <Kbd>⇥</Kbd> accept
        </span>
        <span>
          <Kbd>esc</Kbd> close
        </span>
      </div>
    </div>
  );
}
