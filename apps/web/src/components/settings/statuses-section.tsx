"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { useRenameStatus, useReorderStatuses, useTeams } from "@/lib/queries";
import { CATEGORY_LABELS } from "@/lib/statuses";
import { SettingsNote } from "./field";

/**
 * A team's own words for its work, and the order it reads them in — `KAN-28`.
 *
 * Two writes and no third. Adding and removing a status is `KAN-90`: it makes
 * `Ticket.status` unrepresentable as an enum on the server, so the six keys are fixed here
 * and what a team owns is the word and the sequence. The screen says so rather than
 * leaving somebody hunting for an `Add` button that is not there.
 *
 * The category is drawn beside each word and cannot be edited. It is Kanso's reading of a
 * status — what the burndown, the cycle report and the roadmap consult instead of the word
 * — and a team renaming `done` to `Résolu` should be able to see that it still counts as
 * finished. Editing it would let a team make every chart in the product wrong.
 *
 * Reordering is two buttons and not a drag. A drag needs a pointer, and this list is six
 * rows somebody touches once: `Move Résolu up` is a thing a keyboard and a screen reader
 * can both do, and the board's drag exists for the surface where dragging is the gesture.
 */
export function StatusesSection() {
  const teams = useTeams();
  const rows = teams.data ?? [];
  const [chosen, setChosen] = useState<string>();
  const team = rows.find((candidate) => candidate.id === chosen) ?? rows[0];

  if (!team) {
    return (
      <SettingsNote>No team yet. A team&apos;s words arrive with the team.</SettingsNote>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      {rows.length > 1 && (
        <label className="flex items-center gap-2 text-13">
          <span className="text-muted-foreground">Team</span>
          <select
            className="h-7 rounded-sm border border-border bg-background px-2 text-13"
            value={team.id}
            onChange={(event) => setChosen(event.target.value)}
          >
            {rows.map((candidate) => (
              <option key={candidate.id} value={candidate.id}>
                {candidate.name}
              </option>
            ))}
          </select>
        </label>
      )}

      {/* Keyed on the team, so switching teams rebuilds the rows rather than leaving one
          team's draft word in another team's field. */}
      <StatusRows key={team.id} teamId={team.id} statuses={team.statuses} />

      <SettingsNote>
        The six are Kanso&apos;s. A team names them and orders them; adding a seventh is
        not here yet.
      </SettingsNote>
    </div>
  );
}

function StatusRows({
  teamId,
  statuses,
}: {
  teamId: string;
  statuses: NonNullable<ReturnType<typeof useTeams>["data"]>[number]["statuses"];
}) {
  const rename = useRenameStatus(teamId);
  const reorder = useReorderStatuses(teamId);
  const [refusal, setRefusal] = useState<string>();

  const commit = (key: string, was: string, typed: string) => {
    const label = typed.trim();
    setRefusal(undefined);
    // Nothing typed, or nothing changed: a rename that writes the word already there is a
    // request whose only effect is a refetch.
    if (label === "" || label === was) return;
    // The comparison `team_statuses_label_uniq` makes, so the sentence arrives without a
    // round trip. The server still refuses it if this check is ever wrong — this is the
    // faster copy of the rule, not the rule.
    if (statuses.some((row) => row.key !== key && row.label.toLowerCase() === label.toLowerCase())) {
      setRefusal(`This team already has a status called "${label.toLowerCase()}"`);
      return;
    }
    rename.mutate({ key, label });
  };

  /** The whole order, because the server refuses a partial one — `TeamStatusService`. */
  const move = (index: number, by: -1 | 1) => {
    const keys = statuses.map((row) => row.key);
    const [key] = keys.splice(index, 1);
    keys.splice(index + by, 0, key);
    reorder.mutate(keys);
  };

  return (
    <div className="flex flex-col gap-1">
      {statuses.map((status, index) => (
        <div
          key={status.key}
          data-testid="status-row"
          className="grid grid-cols-[1fr_110px_auto] items-center gap-3 rounded-sm bg-card px-2.5 py-1.5"
        >
          <Input
            defaultValue={status.label}
            aria-label={`Name of ${status.label}`}
            maxLength={40}
            onBlur={(event) => commit(status.key, status.label, event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter") event.currentTarget.blur();
              // Escape puts the stored word back and gives up, rather than committing
              // whatever was half-typed when somebody changed their mind.
              if (event.key === "Escape") {
                event.currentTarget.value = status.label;
                event.currentTarget.blur();
              }
            }}
          />
          <span className="text-11 text-faint uppercase tracking-[0.05em]">
            {CATEGORY_LABELS[status.category]}
          </span>
          <span className="flex items-center gap-1">
            {index > 0 && (
              <Button
                size="xs"
                variant="ghost"
                aria-label={`Move ${status.label} up`}
                onClick={() => move(index, -1)}
              >
                ↑
              </Button>
            )}
            {index < statuses.length - 1 && (
              <Button
                size="xs"
                variant="ghost"
                aria-label={`Move ${status.label} down`}
                onClick={() => move(index, 1)}
              >
                ↓
              </Button>
            )}
          </span>
        </div>
      ))}

      {/* The client's refusal and the server's, in one place: whichever spoke last is what
          a reader sees, and both are about the word that was just typed. */}
      {(refusal ?? rename.error) && (
        <span role="alert" className="text-11 text-urgent">
          {refusal ?? (rename.error as Error | null)?.message}
        </span>
      )}
    </div>
  );
}
