"use client";

import { cn } from "@/lib/utils";

/**
 * `CommandPalette`, `DetailPanel` and `HelpOverlay` each moved to a file of their
 * own — the four surfaces this module used to hold shared nothing but `Backdrop`,
 * and a file that draws four independent things is exactly the "too much in one
 * place" this branch's components are meant to avoid. Re-exported here rather than
 * repointing every import: `page.tsx` — outside this task's file set — imports all
 * three from `"@/components/overlays"`, and a rename that only serves this file's
 * own shape isn't this task's to ask of it.
 */
export { CommandPalette } from "./command-palette";
export { DetailPanel } from "./detail-panel";
export { HelpOverlay } from "./help-overlay";

/**
 * Exported since the composer moved into a file of its own. `DialogFrame`, for its
 * part, rewrites these four lines: it needs `role="dialog"`, a `tabIndex` and a
 * `keydown` boundary, none of which this wrapper takes.
 *
 * `panelClassName` is the one thing that varies between what this wraps: the
 * composer draws a wide, 640px form; the command palette and the help panel are
 * happy at the narrower default. A width is not a colour, a radius or a shadow, so
 * it stays a plain Tailwind class rather than growing its own token.
 */
export function Backdrop({
  onClose,
  panelClassName,
  children,
}: {
  onClose: () => void;
  panelClassName?: string;
  children: React.ReactNode;
}) {
  return (
    <div
      className="fixed inset-0 z-20 flex items-start justify-center bg-black/34 pt-[12vh]"
      onClick={onClose}
    >
      <div
        className={cn(
          "w-[min(560px,92vw)] overflow-hidden rounded-panel bg-popover shadow-float",
          panelClassName,
        )}
        onClick={(event) => event.stopPropagation()}
      >
        {children}
      </div>
    </div>
  );
}
