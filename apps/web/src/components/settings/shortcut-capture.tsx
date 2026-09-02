"use client";

import { useState, type KeyboardEvent } from "react";
import { chordOf, formatChord } from "@/lib/actions";
import { Button } from "@/components/ui/button";
import { isModifierKey } from "./shortcut-rows";

/**
 * "Press a combination" — the one interactive part of the shortcuts table.
 *
 * A `readOnly` text input and not a button, and that is the whole design. The shell's
 * single `keydown` listener stands down over any `INPUT`, `TEXTAREA` or `SELECT` — "a bare
 * key belongs to whoever is typing" — so a capture that *is* a text field is inert to the
 * dispatcher for free, in the guard that already exists, and the reader can press `c`
 * without also creating a ticket. The alternative was a `<button>` plus
 * `stopPropagation`, which works only as long as React keeps delegating to the root
 * container and nothing else attaches a capture-phase listener above it: a silent,
 * version-dependent dependency in exchange for nothing. §6.5's third rule leans on the
 * same guard from the other side — "a bare printable key is allowed, because bare keys are
 * already inert inside text fields".
 *
 * `readOnly` rather than `disabled`: a disabled input takes no focus and receives no
 * `keydown`, which is the entire job.
 *
 * What it cannot promise is the browser's own reflexes. `preventDefault` suppresses
 * find-in-page in every engine, and `⌘K`/`Ctrl+K` reaches the address bar in some builds
 * of Chrome whatever the page says — so a reader who wants that chord may have to capture
 * it in another browser. There is no API for it and pretending otherwise would be worse
 * than the sentence in this comment.
 */
export function ShortcutCapture({
  label,
  isMac,
  refuse,
  onCapture,
}: {
  /** The action being bound, for the labels a screen reader reads out. */
  label: string;
  isMac: boolean;
  /** Why this chord cannot go here, or nothing. `refuseChord`, bound to the row. */
  refuse: (chord: string) => string | undefined;
  onCapture: (chord: string) => void;
}) {
  const [armed, setArmed] = useState(false);
  const [refused, setRefused] = useState<string>();

  const disarm = () => {
    setArmed(false);
    setRefused(undefined);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    // A modifier being held is not yet a chord. Without this, `⇧` on its own arrives as
    // `Shift+Shift`, which parses — so it would be *stored* rather than refused.
    if (isModifierKey(event.key)) return;

    // The two ways out, and they behave differently on purpose: `Escape` cancels and
    // leaves the focus here, `Tab` cancels and is allowed to walk the focus on, which is
    // what somebody pressing it is asking for.
    if (event.key === "Escape") {
      event.preventDefault();
      disarm();
      return;
    }
    if (event.key === "Tab") {
      disarm();
      return;
    }

    event.preventDefault();
    const chord = chordOf(event);
    const why = refuse(chord);
    // Still armed after a refusal: the reader's next press is almost always another
    // attempt, and closing the field would make them re-open it to make it.
    if (why) {
      setRefused(`${formatChord(chord, isMac)} — ${why}`);
      return;
    }
    onCapture(chord);
    disarm();
  };

  if (!armed) {
    return (
      <Button
        type="button"
        variant="outline"
        size="xs"
        aria-label={`Add a key for ${label}`}
        onClick={() => setArmed(true)}
      >
        Add a key
      </Button>
    );
  }

  return (
    <span className="inline-flex flex-col items-end gap-0.5">
      <input
        autoFocus
        readOnly
        value=""
        data-testid="shortcut-capture"
        aria-label={`Press a combination for ${label}`}
        placeholder="press a combination"
        // Focus leaving is a cancel: the field is a mode, and a mode nobody can see they
        // are in is how a later keystroke ends up somewhere it was not aimed.
        onBlur={disarm}
        onKeyDown={onKeyDown}
        className="h-5 w-[152px] rounded-sm border border-primary bg-transparent px-1.5 text-11 outline-none placeholder:text-faint"
      />
      {refused && (
        <span role="status" data-testid="shortcut-refusal" className="text-11 text-urgent">
          {refused}
        </span>
      )}
    </span>
  );
}
