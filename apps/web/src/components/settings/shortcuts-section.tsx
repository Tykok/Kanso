"use client";

import { useState } from "react";
import { actionById, formatChord } from "@/lib/actions";
import { isMac } from "@/lib/platform";
import { usePreferences, useSavePreferences } from "@/lib/queries";
import type { RejectedBinding } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Kbd } from "@/components/ui/kbd";
import { ShortcutCapture } from "./shortcut-capture";
import {
  isLockedChord,
  matches,
  refuseChord,
  shortcutTable,
  withChord,
  withoutChord,
  withoutOverride,
  type ShortcutRow,
} from "./shortcut-rows";

/**
 * §6.5 — the page where a reader remaps every key in Kanso.
 *
 * No Save button, for `appearance-section.tsx`'s reason and not a weaker version of it: a
 * keyboard is judged by pressing it, so each capture applies at once, persists in the
 * background, and a failed write puts the previous bindings back and the table visibly
 * reverts. The interface is the receipt.
 *
 * Every write goes through `useSavePreferences` with a **complete `shortcuts` map** —
 * `PreferencesService` replaces the field rather than merging into it — and that map names
 * only the actions the reader has changed. Never a copy of the defaults: those are derived
 * from the registry by `DEFAULT_BINDINGS` at read time, so a key improved in a later
 * release still reaches every account that never spoke about it.
 *
 * The three things §6.5 leaves open are decided in `shortcut-rows.ts`, next to the code
 * that acts on them: which actions get a row (all of them), what a capture means (add,
 * not replace), and how a standing refusal is shown (the strip below).
 */
export function ShortcutsSection() {
  const { shortcuts } = usePreferences();
  const save = useSavePreferences();
  const { keys, index, rejected } = useBindings();
  const [query, setQuery] = useState("");

  const mac = isMac();
  const write = (next: Record<string, string[]>) => save.mutate({ shortcuts: next });
  const groups = shortcutTable(keys, shortcuts, mac)
    .map((group) => ({ ...group, rows: group.rows.filter((row) => matches(row, query)) }))
    .filter((group) => group.rows.length > 0);
  const changed = Object.keys(shortcuts).length;

  return (
    <section className="flex flex-col gap-5">
      <h2 className="text-21 font-medium tracking-tight">Shortcuts</h2>

      <p className="text-11 text-faint">
        Every action in Kanso, and the keys that reach it. Only what you change is stored,
        so the keys you leave alone keep improving with the application. A key can mean two
        things on two screens — the column says where each one works — and{" "}
        <Kbd>Esc</Kbd> always closes what is open, whatever this table says.
      </p>

      <RefusedBindings
        rejected={rejected}
        isMac={mac}
        onDiscard={(refusal) =>
          write(withoutChord(shortcuts, keys, refusal.actionId, refusal.chord))
        }
      />

      <div className="flex flex-wrap items-center gap-2">
        <Input
          type="search"
          value={query}
          aria-label="Search shortcuts"
          placeholder="Search an action or a key…"
          onChange={(event) => setQuery(event.target.value)}
          className="max-w-[240px]"
        />
        <span className="flex-1 text-11 text-faint">
          {changed === 0 ? "Nothing remapped" : `${changed} action${changed > 1 ? "s" : ""} remapped`}
        </span>
        {/* Above the table, as §6.5 asks, and inert with nothing to undo: a reset that
            reads as available on a keyboard nobody has touched invites the click and then
            changes nothing, which is indistinguishable from a broken button. */}
        <Button
          type="button"
          variant="destructive"
          size="sm"
          disabled={changed === 0}
          onClick={() => write({})}
        >
          Reset everything
        </Button>
      </div>

      <table className="w-full border-collapse text-13">
        <thead>
          <tr className="border-b border-border text-11 uppercase tracking-wide text-faint">
            <th className="py-1.5 pr-3 text-left font-medium">Action</th>
            <th className="py-1.5 pr-3 text-left font-medium">Where</th>
            <th className="py-1.5 pr-3 text-left font-medium">Keys</th>
            <th className="py-1.5 text-right font-medium">
              <span className="sr-only">Change</span>
            </th>
          </tr>
        </thead>
        {groups.map((group) => (
          <tbody key={group.group}>
            <tr>
              <th
                colSpan={4}
                className="pt-4 pb-1 text-left text-11 font-medium uppercase tracking-[0.1em] text-faint"
              >
                {group.title}
              </th>
            </tr>
            {group.rows.map((row) => (
              <ShortcutTableRow
                key={row.action.id}
                row={row}
                isMac={mac}
                refuse={(chord) => refuseChord(chord, row.action, keys, index)}
                onAdd={(chord) => write(withChord(shortcuts, keys, row.action.id, chord))}
                onRemove={(chord) => write(withoutChord(shortcuts, keys, row.action.id, chord))}
                onReset={() => write(withoutOverride(shortcuts, row.action.id))}
              />
            ))}
          </tbody>
        ))}
      </table>

      {groups.length === 0 && (
        <span className="text-11 text-faint">No action or key matches “{query}”.</span>
      )}

      {save.isError && (
        <div className="text-11 text-urgent">
          Not saved: {(save.error as Error).message}. The keyboard went back to what is stored.
        </div>
      )}
    </section>
  );
}

function ShortcutTableRow({
  row,
  isMac,
  refuse,
  onAdd,
  onRemove,
  onReset,
}: {
  row: ShortcutRow;
  isMac: boolean;
  refuse: (chord: string) => string | undefined;
  onAdd: (chord: string) => void;
  onRemove: (chord: string) => void;
  onReset: () => void;
}) {
  return (
    <tr data-testid="shortcut-row" data-action={row.action.id} className="border-b border-border/60">
      <td className="py-1.5 pr-3">{row.action.label}</td>
      <td className="py-1.5 pr-3 text-11 text-faint">{row.where}</td>
      <td className="py-1.5 pr-3">
        {row.chords.length === 0 ? (
          // Said out loud rather than left blank. For the twenty-one actions the palette
          // owns this is the *default*, not a gap, and an empty cell in a table of keys
          // reads as something that failed to load.
          <span className="text-11 text-faint">Not bound</span>
        ) : (
          <span className="flex flex-wrap items-center gap-1.5">
            {row.chords.map((chord, at) => (
              <span key={chord} className="inline-flex items-center gap-0.5">
                <Kbd>{row.printed[at]}</Kbd>
                {!isLockedChord(row.action.id, chord) && (
                  <button
                    type="button"
                    aria-label={`Remove ${row.printed[at]} from ${row.action.label}`}
                    onClick={() => onRemove(chord)}
                    className="rounded-sm px-0.5 text-11 text-faint hover:text-urgent"
                  >
                    ×
                  </button>
                )}
              </span>
            ))}
          </span>
        )}
      </td>
      <td className="py-1.5 text-right">
        <span className="inline-flex items-center justify-end gap-1.5">
          <ShortcutCapture
            label={row.action.label}
            isMac={isMac}
            refuse={refuse}
            onCapture={onAdd}
          />
          <Button
            type="button"
            variant="ghost"
            size="xs"
            disabled={!row.overridden}
            aria-label={`Reset ${row.action.label} to its default`}
            onClick={onReset}
          >
            Reset
          </Button>
        </span>
      </td>
    </tr>
  );
}

/**
 * Decision three: the refusals `mergeBindings` already made, said out loud.
 *
 * A reader can arrive with a stored override that collided — an older release's key that a
 * newer action now claims, a hand-edited row, an id renamed underneath a preference. The
 * merge refused it in silence, because it runs on every screen and cannot interrupt any of
 * them, and this page is the only surface that can tell them. Without this strip the
 * symptom is a key that does nothing and a table that agrees it is not bound: two
 * consistent lies about a preference the reader still has stored.
 *
 * Each one comes with a `Discard`, which is what makes it a message and not a complaint.
 * Nothing here is applied automatically — the whole rule of §6.5 is never a silent steal,
 * and a page that resolved these by itself would be stealing on the reader's behalf.
 */
function RefusedBindings({
  rejected,
  isMac,
  onDiscard,
}: {
  rejected: readonly RejectedBinding[];
  isMac: boolean;
  onDiscard: (refusal: RejectedBinding) => void;
}) {
  if (rejected.length === 0) return null;

  return (
    <div
      data-testid="shortcut-rejected"
      className="flex flex-col gap-1.5 rounded-md border border-urgent/40 bg-urgent/5 px-3 py-2.5"
    >
      <span className="text-11 font-medium text-urgent">
        {rejected.length === 1 ? "One stored key was not applied" : `${rejected.length} stored keys were not applied`}
      </span>
      {rejected.map((refusal) => (
        <div key={`${refusal.actionId}:${refusal.chord}`} className="flex items-center gap-2 text-11">
          <Kbd>{formatChord(refusal.chord, isMac)}</Kbd>
          <span className="flex-1 text-muted-foreground">
            {actionById(refusal.actionId).label} — {refusal.reason}
          </span>
          <Button type="button" variant="ghost" size="xs" onClick={() => onDiscard(refusal)}>
            Discard
          </Button>
        </div>
      ))}
    </div>
  );
}
