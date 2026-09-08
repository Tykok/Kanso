"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  useAddStatus,
  useRemoveStatus,
  useRenameStatus,
  useReorderStatuses,
  useTeams,
} from "@/lib/queries";
import { STATUS_CATEGORIES, type StatusCategory } from "@/lib/api";
import { statusKeyOf } from "@/lib/status-key";
import { CATEGORY_LABELS } from "@/lib/statuses";
import { SettingsNote } from "./field";

/**
 * A team's own words for its work, the order it reads them in, and which of them exist.
 *
 * `KAN-28` landed the word and the sequence and said an `Add` button was not there yet.
 * `KAN-90` put it there, once `Ticket.status` stopped being an enum on the server.
 *
 * **The category is chosen once, when the word is added, and never afterwards.** It is
 * what the burndown, the cycle report and the roadmap consult instead of the word, so
 * moving a status between categories would move what all three count, with no edit to any
 * of them and no line in any feed. A team that got it wrong removes the status — which is
 * a gesture that says where its tickets go — and adds it again. On an existing row it is
 * drawn and not editable, so a team renaming `done` to `Résolu` can see it still counts as
 * finished.
 *
 * Reordering is two buttons and not a drag. A drag needs a pointer, and this list is a
 * handful of rows somebody touches once: `Move Résolu up` is a thing a keyboard and a
 * screen reader can both do, and the board's drag exists for the surface where dragging is
 * the gesture. The order is not cosmetic since `KAN-90` — it is what `PrTransition` reads
 * as progress and what every grouped page stacks by.
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

      {/* Keyed on the team for the same reason: a half-typed seventh word must not follow
          the reader into another team's list. */}
      <AddStatus key={`add-${team.id}`} teamId={team.id} statuses={team.statuses} />

      <SettingsNote>
        The six are what a team starts with. It names them, orders them, adds its own and
        removes the ones it does not use — a removal says where that status&apos;s tickets
        go, and a team keeps at least one.
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
  const remove = useRemoveStatus(teamId);
  const [refusal, setRefusal] = useState<string>();
  /** Which row is asking where its tickets go. One at a time: it is a question, not a mode. */
  const [removing, setRemoving] = useState<string>();

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

  const [destination, setDestination] = useState<string>();

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
            {/* Hidden on the last row rather than shown refused: the server would answer
                "A team keeps at least one status", and a control that is only ever going
                to say no is better not drawn. */}
            {statuses.length > 1 && (
              <Button
                size="xs"
                variant="ghost"
                aria-label={`Remove ${status.label}`}
                onClick={() => {
                  setRefusal(undefined);
                  setRemoving(removing === status.key ? undefined : status.key);
                }}
              >
                ×
              </Button>
            )}
          </span>

          {/* The destination, asked inline under the row it is about.
              Always asked, even for a status holding nothing — the client does not know
              the count, and a dialog that sometimes appears is a control nobody learns.
              The server accepts an absent `into` exactly when the status is empty, so
              answering it for an empty one costs a word and is never wrong. */}
          {removing === status.key && (
            <div className="col-span-3 flex flex-wrap items-center gap-2 pt-1 text-12">
              <span className="text-muted-foreground">
                Move its tickets to
              </span>
              <select
                className="h-7 rounded-sm border border-border bg-background px-2 text-13"
                aria-label={`Where the tickets in ${status.label} go`}
                defaultValue={statuses.find((row) => row.key !== status.key)?.key}
                onChange={(event) => setDestination(event.target.value)}
              >
                {statuses
                  .filter((row) => row.key !== status.key)
                  .map((row) => (
                    <option key={row.key} value={row.key}>
                      {row.label}
                    </option>
                  ))}
              </select>
              {/* `Move and remove`, and not `Remove <word>` a second time: the row's own
                  × already carries that name, and two controls with one accessible name
                  doing two different things is a screen reader reading a fork in the
                  road as a repeat. Measured — the test could not tell them apart. */}
              <Button
                size="xs"
                onClick={() => {
                  const into =
                    destination ?? statuses.find((row) => row.key !== status.key)?.key;
                  remove.mutate({ key: status.key, into });
                  setRemoving(undefined);
                }}
              >
                Move and remove
              </Button>
              <Button size="xs" variant="ghost" onClick={() => setRemoving(undefined)}>
                Keep it
              </Button>
            </div>
          )}
        </div>
      ))}

      {/* The client's refusal and the server's, in one place: whichever spoke last is what
          a reader sees, and both are about the word that was just typed. */}
      {(refusal ?? rename.error ?? remove.error) && (
        <span role="alert" className="text-11 text-urgent">
          {refusal ??
            ((rename.error ?? remove.error) as Error | null)?.message}
        </span>
      )}
    </div>
  );
}

/**
 * A seventh word, and the one question it has to answer: what does it mean.
 *
 * The category is a `<select>` with no default selected value beyond the first, and it is
 * required by the request rather than defaulted server-side — `TeamStatusService.add` will
 * not let it change afterwards, so this is the only moment anybody can choose it.
 *
 * The duplicate check runs on the *key*, not the label, and that is the half worth having
 * locally: two different words can fold to one key — `En cours` and `en-cours` — and that
 * collision is the primary key's, which arrives from the driver with no sentence.
 * `statusKeyOf` is the same derivation the server makes, pinned equal by a table on each
 * side. This is the faster copy of the rule, never the rule: the server still refuses.
 */
function AddStatus({
  teamId,
  statuses,
}: {
  teamId: string;
  statuses: NonNullable<ReturnType<typeof useTeams>["data"]>[number]["statuses"];
}) {
  const add = useAddStatus(teamId);
  const [label, setLabel] = useState("");
  const [category, setCategory] = useState<StatusCategory>("unstarted");
  const [refusal, setRefusal] = useState<string>();

  const submit = () => {
    const word = label.trim();
    setRefusal(undefined);
    if (word === "") return;

    const key = statusKeyOf(word);
    if (key === null) {
      setRefusal("A status needs a letter or a digit in its name");
      return;
    }
    if (statuses.some((row) => row.label.toLowerCase() === word.toLowerCase())) {
      setRefusal(`This team already has a status called "${word.toLowerCase()}"`);
      return;
    }
    if (statuses.some((row) => row.key === key)) {
      setRefusal(`This team already has a status called "${key}"`);
      return;
    }

    add.mutate({ label: word, category }, { onSuccess: () => setLabel("") });
  };

  return (
    <div className="flex flex-col gap-1">
      <div
        data-testid="add-status"
        className="grid grid-cols-[1fr_110px_auto] items-center gap-3 rounded-sm border border-dashed border-border px-2.5 py-1.5"
      >
        <Input
          value={label}
          aria-label="Name of the status to add"
          placeholder="A word for this team's work"
          maxLength={40}
          onChange={(event) => setLabel(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") submit();
            if (event.key === "Escape") {
              setLabel("");
              setRefusal(undefined);
            }
          }}
        />
        <select
          className="h-7 rounded-sm border border-border bg-background px-2 text-13"
          aria-label="What the status means"
          value={category}
          onChange={(event) => setCategory(event.target.value as StatusCategory)}
        >
          {STATUS_CATEGORIES.map((value) => (
            <option key={value} value={value}>
              {CATEGORY_LABELS[value]}
            </option>
          ))}
        </select>
        <Button size="xs" onClick={submit} disabled={label.trim() === ""}>
          Add
        </Button>
      </div>

      {(refusal ?? add.error) && (
        <span role="alert" className="text-11 text-urgent">
          {refusal ?? (add.error as Error | null)?.message}
        </span>
      )}
    </div>
  );
}
