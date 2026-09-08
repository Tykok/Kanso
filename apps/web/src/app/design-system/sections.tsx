"use client";

import { useEffect, useRef, useState } from "react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog";
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuLabel,
  DropdownMenuSeparator,
  DropdownMenuTrigger,
} from "@/components/ui/dropdown-menu";
import { PriorityMark } from "@/components/ui/priority-mark";
import { StatusDot } from "@/components/ui/status-dot";
import { Segmented } from "@/components/settings/panel";
import { ACCENTS, TICKET_PRIORITIES, DEFAULT_STATUSES, type TicketPriority, type TicketStatus } from "@/lib/api";
import { ACCENT_LABELS } from "@/lib/preferences-copy";
import { PRIORITY_LABELS, STATUS_LABELS } from "@/lib/status";
import { cn } from "@/lib/utils";

export function DialogDemo() {
  return (
    <Dialog>
      <DialogTrigger asChild>
        <Button variant="outline">Open dialog</Button>
      </DialogTrigger>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Archive this project?</DialogTitle>
          <DialogDescription>
            Its tickets stay where they are. Archiving only hides the project from the
            sidebar.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="outline">Cancel</Button>
          <Button variant="destructive">Archive</Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}

const STATUS_TEXT_CLASS: Record<TicketStatus, string> = {
  backlog: "text-status-backlog",
  todo: "text-status-todo",
  in_progress: "text-status-progress",
  in_review: "text-status-review",
  done: "text-status-done",
  canceled: "text-status-canceled",
};

const PRIORITY_TEXT_CLASS: Record<TicketPriority, string> = {
  none: "text-priority-none",
  low: "text-priority-low",
  medium: "text-priority-medium",
  high: "text-priority-high",
  urgent: "text-urgent",
};

/**
 * The branch's own instrument for the values nothing else lets a human read: the
 * ten dark accent values extrapolated from indigo's drawn transform, and the
 * status/priority hues against `--background` in each scheme.
 *
 * A real toggle on `<html>`, not two nested `.dark`/plain columns side by side —
 * `--background`, `--foreground`, every status and priority token are declared
 * once on `:root` and once on `.dark` (`tokens.css`), and `:root` matches only the
 * document's actual root element. A `.dark` class added to a wrapper *div* would
 * correctly force dark (that rule matches anywhere), but there is no matching way
 * to force *light* from a nested element if the page's real, stored preference
 * already put `.dark` on `<html>` — a "Light" column in that state would silently
 * inherit dark and show it under the wrong label, which is exactly the kind of
 * technically-present-but-wrong reading this page exists to prevent. Only
 * `--primary`/`--accent-soft`/`--accent-ink` are scoped by the plain attribute
 * selector `[data-accent]`, which *does* work on any element regardless of
 * ambient — that half doesn't need this. This toggle drives the same
 * `document.documentElement` class the real preference switch does (`lib/theme.ts`
 * `applyPreferences`), restoring whatever was there before on unmount.
 */
export function ContrastMatrix() {
  const [scheme, setScheme] = useState<"light" | "dark">("light");
  const originalDark = useRef<boolean | undefined>(undefined);

  // Captured once, on mount, and restored once, on unmount — not on every toggle —
  // so the page's real theme is exactly as this component found it after leaving.
  useEffect(() => {
    originalDark.current = document.documentElement.classList.contains("dark");
    return () => {
      if (originalDark.current !== undefined) {
        document.documentElement.classList.toggle("dark", originalDark.current);
      }
    };
  }, []);

  useEffect(() => {
    document.documentElement.classList.toggle("dark", scheme === "dark");
  }, [scheme]);

  return (
    <div className="flex flex-col gap-5 rounded-lg border border-border bg-background p-4 text-foreground">
      {/* The workbench's own control, not the legacy `.segmented` it exists to have
          replaced: `Segmented` (settings/panel.tsx) is what every real toggle in the
          app draws now. `self-start` keeps it from stretching to the full width of
          this flex column, the way the `.segmented` div it replaces did explicitly
          and `Segmented`'s own root — which takes no `className` — cannot. */}
      <div className="self-start">
        <Segmented
          label="Preview scheme"
          value={scheme}
          onChange={setScheme}
          options={[
            { value: "light", label: "Light" },
            { value: "dark", label: "Dark" },
          ]}
        />
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-11 font-medium tracking-wide text-faint uppercase">
          --primary on --primary-foreground · --accent-ink on --accent-soft
        </span>
        <div className="grid grid-cols-2 gap-2 sm:grid-cols-3">
          {ACCENTS.map((accent) => (
            <div
              key={accent}
              data-accent={accent}
              className="flex flex-col gap-1.5 rounded-md border border-border p-2"
            >
              <span className="text-11 text-faint">{ACCENT_LABELS[accent]}</span>
              <div className="rounded-sm bg-primary px-2 py-1 text-12 text-primary-foreground">Primary</div>
              <div className="rounded-sm bg-accent-soft px-2 py-1 text-12 text-accent-ink">Ink on soft</div>
            </div>
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-11 font-medium tracking-wide text-faint uppercase">
          Status hues on --background
        </span>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          {DEFAULT_STATUSES.map((status) => (
            <div
              key={status}
              className={cn("flex items-center gap-1.5 text-13", STATUS_TEXT_CLASS[status])}
            >
              <StatusDot status={status} />
              {STATUS_LABELS[status]}
            </div>
          ))}
        </div>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-11 font-medium tracking-wide text-faint uppercase">
          Priority hues on --background
        </span>
        <div className="flex flex-wrap gap-x-4 gap-y-2">
          {TICKET_PRIORITIES.map((priority) => (
            <div
              key={priority}
              className={cn("flex items-center gap-1.5 text-13", PRIORITY_TEXT_CLASS[priority])}
            >
              <PriorityMark priority={priority} />
              {PRIORITY_LABELS[priority]}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

export function DropdownDemo() {
  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="outline">Open menu</Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        <DropdownMenuLabel>KAN-14</DropdownMenuLabel>
        <DropdownMenuSeparator />
        <DropdownMenuItem>Rename</DropdownMenuItem>
        <DropdownMenuItem>Archive</DropdownMenuItem>
        <DropdownMenuSeparator />
        <DropdownMenuItem variant="destructive">Delete</DropdownMenuItem>
      </DropdownMenuContent>
    </DropdownMenu>
  );
}
