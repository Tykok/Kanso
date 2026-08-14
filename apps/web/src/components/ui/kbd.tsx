import * as React from "react";

import { cn } from "@/lib/utils";

/**
 * A shortcut key, drawn as a keycap: 11px mono, a soft fill, a border that is a
 * touch heavier on its lower edge than its other three sides — the same detail a
 * physical key has. Three screens show one (the composer, the context menus, the
 * command palette's footer) and two other tasks render this same component rather
 * than reinventing it, so nothing here is tied to where a `<Kbd>` happens to sit.
 */
function Kbd({ className, ...props }: React.ComponentProps<"kbd">) {
  return (
    <kbd
      data-slot="kbd"
      className={cn(
        "inline-flex items-center justify-center rounded-[4px] border border-b-2 bg-accent px-[5px] py-[3px] font-mono text-11 text-muted-foreground",
        className,
      )}
      {...props}
    />
  );
}

export { Kbd };
