"use client";

import { useMemo, useState } from "react";
import { Backdrop } from "./overlays";
import { Kbd } from "./ui/kbd";

type Command = { id: string; label: string; hint?: string; run: () => void };

export function CommandPalette({
  commands,
  onClose,
}: {
  commands: Command[];
  onClose: () => void;
}) {
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);

  const matches = useMemo(() => {
    const needle = query.trim().toLowerCase();
    if (!needle) return commands;
    return commands.filter((command) => command.label.toLowerCase().includes(needle));
  }, [commands, query]);

  // Typing narrows the list, so the highlight goes back to the top — done in the
  // change handler rather than an effect, which would re-render a second time to
  // undo a selection the user never saw.
  const search = (next: string) => {
    setQuery(next);
    setActive(0);
  };

  return (
    <Backdrop onClose={onClose}>
      <div data-testid="panel-header" className="border-b border-border px-4 py-3">
        <input
          className="w-full border-none bg-transparent p-0 text-15 text-foreground outline-none placeholder:text-faint"
          autoFocus
          placeholder="Type a command…"
          value={query}
          onChange={(event) => search(event.target.value)}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === "ArrowDown") {
              event.preventDefault();
              setActive((index) => Math.min(index + 1, matches.length - 1));
            }
            if (event.key === "ArrowUp") {
              event.preventDefault();
              setActive((index) => Math.max(index - 1, 0));
            }
            if (event.key === "Enter") {
              event.preventDefault();
              matches[active]?.run();
            }
            if (event.key === "Escape") onClose();
          }}
        />
      </div>
      <div className="flex max-h-[60vh] flex-col overflow-y-auto">
        {matches.length === 0 && <div className="empty">No matching command</div>}
        {matches.map((command, index) => (
          <button
            key={command.id}
            className="flex w-full items-center gap-2.5 px-4 py-2 text-left text-13 data-[active=true]:bg-accent"
            data-active={index === active}
            onMouseEnter={() => setActive(index)}
            onClick={command.run}
          >
            <span>{command.label}</span>
            {command.hint && <span className="ml-auto text-11 text-faint">{command.hint}</span>}
          </button>
        ))}
      </div>
      <div className="flex items-center gap-2 border-t border-border px-4 py-2 text-11 text-faint">
        <Kbd>↑</Kbd> <Kbd>↓</Kbd> <span>move</span> <Kbd>↵</Kbd> <span>run</span> <Kbd>esc</Kbd>{" "}
        <span>close</span>
      </div>
    </Backdrop>
  );
}
