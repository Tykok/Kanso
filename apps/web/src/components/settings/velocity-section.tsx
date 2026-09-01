"use client";

import { useState } from "react";
import { Input } from "@/components/ui/input";
import { useMe, usePreferences, useSavePreferences, useVelocity } from "@/lib/queries";
import { velocityCaption } from "@/lib/velocity";
import { SettingsField, SettingsNote } from "./field";

const DECLARED_HINT =
  "Points per working day. Your own estimate of your pace, used to date work until Kanso " +
  "has measured cycles of yours to use instead.";

/**
 * The declared velocity, and — the half that matters — the sentence saying whether it is
 * the number Kanso is actually planning with.
 *
 * A field with no caption beside it would be the worst version of this feature: two
 * numbers answering the same question, no way to tell which one a date came from, and a
 * person adjusting the one that stopped being consulted two cycles ago. So the caption is
 * not conditional and not an icon — `velocityCaption` returns a sentence for all three
 * states, including the one where there is no velocity at all.
 *
 * Its own section rather than a row under Appearance: this is a fact about how you work,
 * not about how Kanso looks, and it is the only preference here whose meaning depends on
 * a team's history.
 */
export function VelocitySection() {
  const me = useMe();
  const preferences = usePreferences();
  const save = useSavePreferences();

  // A cycle is one team's calendar, so this number is only meaningful against one. The
  // default team if there is one, otherwise the first — and if there is no team at all the
  // measurement cannot exist, which the caption below says outright rather than spinning.
  const teamId = preferences.defaultTeamId ?? me.data?.teamIds[0];
  const velocity = useVelocity(teamId);

  // Local, so typing does not fire a save per keystroke; committed on blur and on Enter.
  // `undefined` means "showing whatever is stored", which is what lets an optimistic save
  // repaint the field without the draft fighting it.
  const [draft, setDraft] = useState<string | undefined>(undefined);
  const stored = preferences.declaredVelocity;
  const shown = draft ?? (stored === undefined ? "" : String(stored));

  const commit = () => {
    if (draft === undefined) return;
    const trimmed = draft.trim();
    setDraft(undefined);
    // An emptied field withdraws the declaration. `unset` names it because JSON cannot
    // tell an omitted key from an explicit null, and withdrawing has to be possible.
    if (trimmed === "") {
      if (stored !== undefined) save.mutate({ unset: ["declaredVelocity"] });
      return;
    }
    const rate = Number(trimmed);
    if (!Number.isFinite(rate) || rate <= 0 || rate === stored) return;
    save.mutate({ declaredVelocity: rate });
  };

  const caption = velocity.data ? velocityCaption(velocity.data) : null;

  return (
    <section className="flex flex-col gap-6">
      <h2 className="text-21 font-medium tracking-tight">Velocity</h2>

      <div className="flex flex-col gap-4">
        <SettingsField label="Declared velocity" hint={DECLARED_HINT}>
          <Input
            className="w-24"
            type="number"
            min={0.01}
            max={100}
            step={0.25}
            inputMode="decimal"
            aria-label="Declared velocity in points per working day"
            placeholder="—"
            value={shown}
            onChange={(event) => setDraft(event.target.value)}
            onBlur={commit}
            onKeyDown={(event) => {
              if (event.key === "Enter") event.currentTarget.blur();
              if (event.key === "Escape") setDraft(undefined);
            }}
          />
        </SettingsField>

        {/*
         * Never an empty box. Each of the three states — no team, not answered yet, and an
         * answer — says something, because a field that renders blank reads as a bug and a
         * date with no stated origin gets believed.
         */}
        <div className="flex flex-col gap-1 rounded-lg bg-background p-3">
          {!teamId ? (
            <span className="text-12 text-muted-foreground">
              You are in no team yet, so there are no cycles to measure. Until then a declared
              velocity is the only thing Kanso could date work with.
            </span>
          ) : velocity.isError ? (
            <span className="text-12 text-urgent">
              Could not read your velocity: {(velocity.error as Error).message}
            </span>
          ) : !caption ? (
            <span className="text-12 text-faint">Reading your cycles…</span>
          ) : (
            <>
              <span className="text-12 text-foreground">{caption.inForce}</span>
              {caption.reference && (
                <span className="text-12 text-muted-foreground">{caption.reference}</span>
              )}
            </>
          )}
        </div>

        {save.isError && (
          <SettingsNote error>
            Not saved: {(save.error as Error).message}. The field went back to what is stored.
          </SettingsNote>
        )}
      </div>
    </section>
  );
}
