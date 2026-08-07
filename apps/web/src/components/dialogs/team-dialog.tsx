"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { ApiError, api, type Team } from "@/lib/api";
import { keys } from "@/lib/queries";
import { DialogFrame, Field } from "./field";

type TeamErrors = { key?: string; parent?: string; general?: string };

/**
 * The API names the offending input in the sentence it returns, not in a field map.
 * The sentence is therefore what decides where the message lands. Anything
 * unrecognised stays at the foot of the dialog rather than under a field that has
 * nothing to do with it.
 */
function route(error: unknown): TeamErrors {
  if (!(error instanceof ApiError)) {
    return { general: error instanceof Error ? error.message : "The team was not saved." };
  }
  const detail = error.detail.toLowerCase();
  if (error.status === 409 && detail.includes("key") && detail.includes("taken")) {
    return { key: error.detail };
  }
  if (error.status === 409 && detail.includes("cycle")) {
    return { parent: error.detail };
  }
  return { general: error.detail };
}

/**
 * A team's descendants, itself included. A parent taken from in there would make a
 * cycle; the server refuses it with a 409, and this only keeps the impossible choices
 * out of the list. The message under the field handles the case where the tree moved
 * since the list was drawn.
 */
function subtreeIds(teams: Team[], rootId: string): Set<string> {
  const inside = new Set<string>([rootId]);
  let grew = true;
  while (grew) {
    grew = false;
    for (const team of teams) {
      if (team.parentTeamId && inside.has(team.parentTeamId) && !inside.has(team.id)) {
        inside.add(team.id);
        grew = true;
      }
    }
  }
  return inside;
}

/**
 * The form is a component of its own so its drafts start from the saved value at
 * mount, with no effect to put them back afterwards — the same pattern as
 * `TitleEditor` in `tickets.tsx`, and for the same reason: a team renamed by someone
 * else mid-typing does not wipe what is being written.
 */
function TeamForm({
  teams,
  team,
  defaultParentId,
  onClose,
}: {
  teams: Team[];
  team?: Team;
  defaultParentId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(team?.name ?? "");
  const [key, setKey] = useState(team?.key ?? "");
  const [parent, setParent] = useState(defaultParentId);

  const save = useMutation({
    mutationFn: (body: { name: string; key?: string; parentTeamId?: string }) =>
      team ? api.updateTeam(team.id, body) : api.createTeam(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      // Reparenting: projects are drawn under the team, so their place changes.
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      onClose();
    },
  });

  const errors: TeamErrors = save.error ? route(save.error) : {};

  const candidates = useMemo(() => {
    const banned = team ? subtreeIds(teams, team.id) : new Set<string>();
    return teams
      .filter((row) => !banned.has(row.id) && !row.archived)
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [teams, team]);

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    const prefix = key.trim().toUpperCase();
    save.mutate({
      name: trimmed,
      // Left empty, the server derives it from the name (`TeamService.resolveKey`).
      key: prefix ? prefix : undefined,
      parentTeamId: parent ? parent : undefined,
    });
  };

  return (
    <DialogFrame
      title={team ? `Edit ${team.name}` : "New team"}
      onClose={onClose}
      onSubmit={submit}
      submitLabel={team ? "Save" : "Create"}
      pending={save.isPending}
      error={errors.general}
    >
      <Field label="Name">
        <input
          autoFocus
          placeholder="Core"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </Field>

      <Field
        label="Key"
        error={errors.key}
        hint="Two to eight characters. It prefixes every ticket of the team: KAN-42. Left empty, the server derives it from the name."
      >
        <input
          placeholder="KAN"
          value={key}
          aria-invalid={errors.key ? true : undefined}
          onChange={(event) => setKey(event.target.value)}
        />
      </Field>

      <Field label="Parent team" error={errors.parent}>
        <select
          value={parent}
          aria-invalid={errors.parent ? true : undefined}
          onChange={(event) => setParent(event.target.value)}
        >
          <option value="">— none, a root team —</option>
          {candidates.map((row) => (
            <option key={row.id} value={row.id}>
              {row.name}
            </option>
          ))}
        </select>
      </Field>
    </DialogFrame>
  );
}

export function TeamDialog({
  id,
  parentTeamId,
  onClose,
}: {
  id?: string;
  parentTeamId?: string;
  onClose: () => void;
}) {
  // Archived teams are included: editing a team must not silently lose it a parent
  // that happens to be archived.
  const teams = useQuery({ queryKey: keys.teams(true), queryFn: () => api.teams(true) });

  if (!teams.data) {
    return (
      <DialogFrame
        title={id ? "Edit team" : "New team"}
        onClose={onClose}
        onSubmit={onClose}
        submitLabel={id ? "Save" : "Create"}
        pending
      >
        <div className="empty">Loading…</div>
      </DialogFrame>
    );
  }

  const team = id ? teams.data.find((row) => row.id === id) : undefined;

  if (id && !team) {
    return (
      <DialogFrame
        title="Edit team"
        onClose={onClose}
        onSubmit={onClose}
        submitLabel="Close"
        error="That team no longer exists."
      >
        <div className="empty">It was removed while this dialog was opening.</div>
      </DialogFrame>
    );
  }

  return (
    <TeamForm
      teams={teams.data}
      team={team}
      defaultParentId={team ? (team.parentTeamId ?? "") : (parentTeamId ?? "")}
      onClose={onClose}
    />
  );
}
