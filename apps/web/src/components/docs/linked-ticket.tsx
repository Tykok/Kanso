"use client";

import { Kbd } from "../ui/kbd";

/**
 * What `c` opens: a title, and nothing else.
 *
 * Not the composer, and that is the decision the README calls already made. The composer
 * asks which team, which project, which priority; here the page has answered all three,
 * and the one thing it cannot answer is what the ticket is called.
 */
export function LinkedTicketPrompt({
  title,
  onChange,
  onCancel,
  onSubmit,
}: {
  title: string;
  onChange: (title: string) => void;
  onCancel: () => void;
  onSubmit: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-20 flex items-start justify-center bg-black/34 pt-[12vh]"
      onClick={onCancel}
    >
      <div
        className="w-[min(560px,92vw)] overflow-hidden rounded-panel bg-popover p-3.5 shadow-float"
        onClick={(event) => event.stopPropagation()}
      >
        <label className="flex flex-col gap-2">
          <span className="text-11 text-faint">New ticket, linked to this page</span>
          <input
            autoFocus
            data-testid="linked-ticket-title"
            value={title}
            placeholder="What has to happen?"
            className="w-full"
            onChange={(event) => onChange(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") {
                event.preventDefault();
                onSubmit();
              }
              if (event.key === "Escape") {
                event.preventDefault();
                onCancel();
              }
              event.stopPropagation();
            }}
          />
        </label>
        <div className="flex items-center gap-2 pt-2.5 text-11 text-faint">
          <Kbd>↵</Kbd> create and link
          <Kbd>esc</Kbd> cancel
        </div>
      </div>
    </div>
  );
}
