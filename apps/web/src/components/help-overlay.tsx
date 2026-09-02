"use client";

import { isMac } from "@/lib/platform";
import { shortcutRows } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import { Backdrop } from "./overlays";
import { SHORTCUT_SECTIONS } from "./shortcut-sections";
import { Button } from "./ui/button";
import { Kbd } from "./ui/kbd";

/**
 * `?`, reading the **effective** bindings.
 *
 * It used to read the registry's defaults, which was the same thing right up until a
 * reader could change a key — and this is the one screen whose whole purpose is to say
 * which keys exist, so a sheet describing a keyboard the reader does not have is worse
 * than no sheet at all. `useBindings()` is the same merge the dispatcher resolves against,
 * so the two cannot disagree.
 */
export function HelpOverlay({ onClose }: { onClose: () => void }) {
  const { keys } = useBindings();
  const rows = shortcutRows(keys, isMac());

  return (
    <Backdrop onClose={onClose}>
      <div data-testid="panel-header" className="flex items-center gap-2.5 px-4 py-3">
        {/*
          A real heading rather than a `<strong>`: this panel is the one thing on
          screen, and it had no element announcing what it is. It is also what the
          keyboard scenario asserts on — `.shortcuts` stopped being unique the moment
          the list grew a section per mode, and keying a test on a private class is
          what `follow-ups.md` already holds against that suite.
        */}
        <h2 className="flex-1 text-15 font-medium text-foreground">Keyboard</h2>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          Close
        </Button>
      </div>
      <div className="flex max-h-[70vh] flex-col gap-5 overflow-y-auto px-4 pb-4">
        {/*
          Grouped by mode, because a flat list would offer the chart's `h` `l` `⇧h` `⇧l`
          to somebody in the list, where those keys resolve to nothing at all. A
          section with no rows is not printed: an empty heading reads as a gap.
        */}
        {SHORTCUT_SECTIONS.map((section) => {
          const inSection = rows.filter((row) => row.mode === section.mode);
          if (inSection.length === 0) return null;

          return (
            <div key={section.title} className="flex flex-col gap-2">
              <div className="text-11 text-faint">{section.title}</div>
              <div className="grid grid-cols-[auto_1fr] items-center gap-x-3 gap-y-1.5">
                {inSection.map((row) => (
                  <div key={`${section.title}:${row.keys}`} className="contents">
                    <Kbd className="justify-self-start">{row.keys}</Kbd>
                    <span className="text-13 text-muted-foreground">{row.label}</span>
                  </div>
                ))}
              </div>
            </div>
          );
        })}
      </div>
    </Backdrop>
  );
}
