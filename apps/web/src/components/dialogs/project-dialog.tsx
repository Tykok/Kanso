"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiError,
  api,
  dayValue,
  fromDayValue,
  type Project,
  type ProjectBody,
  type Team,
  type User,
} from "@/lib/api";
import { keys } from "@/lib/queries";
import { DialogFrame, Field } from "./field";

/**
 * `ProjectStatus`, server side (`domain/Model.kt`). Written here rather than in
 * `api.ts`: this dialog is the only place that offers the choice; everywhere else the
 * value is read back off the row.
 */
const PROJECT_STATUSES = ["planned", "in_progress", "paused", "completed", "canceled"] as const;

const PROJECT_STATUS_LABELS: Record<(typeof PROJECT_STATUSES)[number], string> = {
  planned: "Planned",
  in_progress: "In progress",
  paused: "Paused",
  completed: "Completed",
  canceled: "Canceled",
};

type ProjectErrors = { team?: string; general?: string };

/**
 * The only "team"-mentioning message this dialog can provoke today is
 * `ProjectService.requireTeam` (`ProjectService.kt:212-213`): `"No team $id"`, always
 * a 400 (a stale option in the Team dropdown pointing at a team removed since the
 * list was drawn). Gated on that status too, not just the word, so a later message
 * that happens to mention "team" for an unrelated reason cannot be misrouted here.
 */
function route(error: unknown): ProjectErrors {
  if (!(error instanceof ApiError)) {
    return { general: error instanceof Error ? error.message : "The project was not saved." };
  }
  if (error.status === 400 && error.detail.toLowerCase().includes("team")) {
    return { team: error.detail };
  }
  return { general: error.detail };
}

function ProjectForm({
  teams,
  users,
  project,
  defaultTeamId,
  onClose,
}: {
  teams: Team[];
  users: User[];
  project?: Project;
  defaultTeamId: string;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const [name, setName] = useState(project?.name ?? "");
  const [status, setStatus] = useState(project?.status ?? "planned");
  const [lead, setLead] = useState(project?.leadUserId ?? "");
  // Held as the `YYYY-MM-DD` the input speaks, not as an instant: the bounds a
  // project poses are days, and converting one to a moment and back is where a
  // timezone gets the chance to move it.
  const [startDay, setStartDay] = useState(dayValue(project?.start));
  const [endDay, setEndDay] = useState(dayValue(project?.end));
  const [team, setTeam] = useState(defaultTeamId);

  const save = useMutation({
    mutationFn: (body: ProjectBody) =>
      project ? api.updateProject(project.id, body) : api.createProject(body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      onClose();
    },
  });

  const errors = save.error ? route(save.error) : {};

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) return;
    save.mutate({
      name: trimmed,
      status,
      start: fromDayValue(startDay),
      end: fromDayValue(endDay),
      leadUserId: lead ? lead : undefined,
      // Clearing the team makes the project transverse: the PUT simply omits `teamId`.
      teamId: team ? team : undefined,
    });
  };

  return (
    <DialogFrame
      title={project ? `Edit ${project.name}` : "New project"}
      onClose={onClose}
      onSubmit={submit}
      submitLabel={project ? "Save" : "Create"}
      pending={save.isPending}
      error={errors.general}
    >
      <Field label="Name">
        <input
          autoFocus
          placeholder="Login rework"
          value={name}
          onChange={(event) => setName(event.target.value)}
        />
      </Field>

      <Field label="Status">
        <select value={status} onChange={(event) => setStatus(event.target.value)}>
          {PROJECT_STATUSES.map((value) => (
            <option key={value} value={value}>
              {PROJECT_STATUS_LABELS[value]}
            </option>
          ))}
        </select>
      </Field>

      <Field
        label="Team"
        error={errors.team}
        hint="No team makes the project transverse: it shows in the root Projects section and any team's tickets may point at it."
      >
        <select
          value={team}
          aria-invalid={errors.team ? true : undefined}
          onChange={(event) => setTeam(event.target.value)}
        >
          <option value="">— none, a transverse project —</option>
          {[...teams]
            .filter((row) => !row.archived)
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((row) => (
              <option key={row.id} value={row.id}>
                {row.name}
              </option>
            ))}
        </select>
      </Field>

      <Field label="Lead">
        <select value={lead} onChange={(event) => setLead(event.target.value)}>
          <option value="">— nobody —</option>
          {[...users]
            .sort((a, b) => a.displayName.localeCompare(b.displayName))
            .map((user) => (
              <option key={user.id} value={user.id}>
                {user.displayName}
              </option>
            ))}
        </select>
      </Field>

      <Field label="Start date">
        <input type="date" value={startDay} onChange={(event) => setStartDay(event.target.value)} />
      </Field>

      <Field label="End date">
        <input type="date" value={endDay} onChange={(event) => setEndDay(event.target.value)} />
      </Field>
    </DialogFrame>
  );
}

export function ProjectDialog({
  id,
  teamId,
  onClose,
}: {
  id?: string;
  teamId?: string;
  onClose: () => void;
}) {
  const teams = useQuery({ queryKey: keys.teams(true), queryFn: () => api.teams(true) });
  const projects = useQuery({
    queryKey: keys.projects(true),
    queryFn: () => api.projects({ includeArchived: true }),
  });
  const users = useQuery({ queryKey: keys.users, queryFn: api.users });

  if (!teams.data || !projects.data) {
    return (
      <DialogFrame
        title={id ? "Edit project" : "New project"}
        onClose={onClose}
        onSubmit={onClose}
        submitLabel={id ? "Save" : "Create"}
        pending
      >
        <div className="empty">Loading…</div>
      </DialogFrame>
    );
  }

  const project = id ? projects.data.find((row) => row.id === id) : undefined;

  if (id && !project) {
    return (
      <DialogFrame
        title="Edit project"
        onClose={onClose}
        onSubmit={onClose}
        submitLabel="Close"
        error="That project no longer exists."
      >
        <div className="empty">It was removed while this dialog was opening.</div>
      </DialogFrame>
    );
  }

  return (
    <ProjectForm
      teams={teams.data}
      users={users.data ?? []}
      project={project}
      defaultTeamId={project ? (project.teamId ?? "") : (teamId ?? "")}
      onClose={onClose}
    />
  );
}
