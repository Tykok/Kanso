"use client";

import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  TICKET_PRIORITIES,
  api,
  type Project,
  type Team,
  type TicketPriority,
  type User,
} from "@/lib/api";
import { creationSeed } from "@/lib/creation-seed";
import { keys, useMe } from "@/lib/queries";
import type { Scope } from "@/store/ui";
import { Backdrop } from "./overlays";

const PRIORITY_LABELS: Record<TicketPriority, string> = {
  none: "No priority",
  low: "Low",
  medium: "Medium",
  high: "High",
  urgent: "Urgent",
};

/**
 * Whether the composer may offer [team] in its select. `editable` is the server's
 * answer from `TicketAccess.editableTeams` on the same rule `TicketService.create`
 * checks before writing — the client has no membership graph of its own to re-derive
 * it from, and a second implementation of that rule in the browser is exactly what
 * this project has spent two increments avoiding. Archived is checked here too rather
 * than left to the caller: an archived team is never a legal destination regardless of
 * who is editable there.
 */
export function isComposableTeam(team: Pick<Team, "archived" | "editable">): boolean {
  return !team.archived && team.editable;
}

/**
 * One title field, Enter creates — the speed that made this worth building. Below it,
 * the target: team, project, priority and assignee, prefilled from the current scope,
 * clickable and reachable with Tab.
 */
function ComposerForm({
  teams,
  projects,
  users,
  meId,
  scope,
  onClose,
}: {
  teams: Team[];
  projects: Project[];
  users: User[];
  meId?: string;
  scope: Scope;
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const teamRef = useRef<HTMLSelectElement>(null);

  // One rule for what a new thing inherits from where it was created, shared with the
  // team and project dialogs: `creationSeed`'s `ticket` branch is this form's column
  // of the spec's table. Validated against the lists actually fetched, not taken on
  // trust. Read once, in the initialisers: the form mounts with the data already in
  // hand, and re-seeding later would overwrite what somebody has changed.
  //
  // `seed.blocked` is the same condition as `!seed.teamId` and is not read here: the
  // form asks before it complains, so the error under the footer belongs to a submit
  // attempt, not to opening the composer.
  const [seed] = useState(() => creationSeed(scope, teams, projects).ticket);

  const [title, setTitle] = useState("");
  const [teamId, setTeamId] = useState(seed.teamId);
  const [projectId, setProjectId] = useState(seed.projectId);
  const [priority, setPriority] = useState<TicketPriority>("none");
  const [assigneeId, setAssigneeId] = useState(meId ?? "");
  const [blocked, setBlocked] = useState(false);

  /**
   * A ticket always belongs to a team; a project does not. The list offered is
   * therefore the chosen team's, plus every team-less project — the only two places a
   * project a ticket may point at can live. No SQL constraint ties
   * `tickets.project_id` to `tickets.team_id`, and this spec adds none: this bounds
   * what the interface offers, nothing more.
   */
  const projectOptions = useMemo(
    () =>
      projects
        .filter((project) => !project.archived && (!project.teamId || project.teamId === teamId))
        .sort((a, b) => a.name.localeCompare(b.name)),
    [projects, teamId],
  );

  const create = useMutation({
    mutationFn: api.createTicket,
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
      // The team's counter moved, and it is on display in the sidebar.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      onClose();
    },
  });

  /**
   * The defect this replaces: `page.tsx` fell back to `teams.data[0]` when the view
   * was "All tickets", so a ticket filed from there landed in whichever team sorted
   * first, with nobody having chosen it. With no team resolved, creation stops and
   * points at the selector.
   */
  const submit = () => {
    if (create.isPending) return;
    const trimmed = title.trim();
    if (!trimmed) return;
    if (!teamId) {
      setBlocked(true);
      teamRef.current?.focus();
      return;
    }
    create.mutate({
      teamId,
      title: trimmed,
      priority,
      projectId: projectId ? projectId : undefined,
      assigneeIds: assigneeId ? [assigneeId] : undefined,
    });
  };

  return (
    <Backdrop onClose={onClose}>
      <input
        className="composer-input"
        autoFocus
        placeholder="New ticket…"
        value={title}
        disabled={create.isPending}
        onChange={(event) => setTitle(event.target.value)}
        onKeyDown={(event) => {
          event.stopPropagation();
          if (event.key === "Enter") {
            event.preventDefault();
            submit();
          }
          if (event.key === "Escape") onClose();
        }}
      />

      <div className="composer-context">
        <select
          ref={teamRef}
          // "Ticket team", not "Team": a `<select>`'s accessible name folds in its
          // options, so a bare "Team" collides across regions with the sidebar's
          // "New team" button. Named at the source rather than worked around in the
          // test's locator.
          aria-label="Ticket team"
          aria-invalid={blocked && !teamId ? true : undefined}
          value={teamId}
          disabled={create.isPending}
          onChange={(event) => {
            const next = event.target.value;
            setTeamId(next);
            setBlocked(false);
            // A project of the team just left is no longer a legal home; a team-less
            // project always is.
            const chosen = projects.find((row) => row.id === projectId);
            if (chosen?.teamId && chosen.teamId !== next) setProjectId("");
          }}
        >
          <option value="">Team…</option>
          {[...teams]
            .sort((a, b) => a.name.localeCompare(b.name))
            .map((team) => (
              <option key={team.id} value={team.id}>
                {team.name}
              </option>
            ))}
        </select>

        <select
          aria-label="Project"
          value={projectId}
          disabled={create.isPending}
          onChange={(event) => setProjectId(event.target.value)}
        >
          <option value="">No project</option>
          {projectOptions.map((project) => (
            <option key={project.id} value={project.id}>
              {project.name}
            </option>
          ))}
        </select>

        <select
          aria-label="Priority"
          value={priority}
          disabled={create.isPending}
          onChange={(event) => setPriority(event.target.value as TicketPriority)}
        >
          {TICKET_PRIORITIES.map((value) => (
            <option key={value} value={value}>
              {PRIORITY_LABELS[value]}
            </option>
          ))}
        </select>

        <select
          aria-label="Assignee"
          value={assigneeId}
          disabled={create.isPending}
          onChange={(event) => setAssigneeId(event.target.value)}
        >
          <option value="">Unassigned</option>
          {[...users]
            .sort((a, b) => a.displayName.localeCompare(b.displayName))
            .map((user) => (
              <option key={user.id} value={user.id}>
                {user.displayName}
              </option>
            ))}
        </select>
      </div>

      <div className="composer-footer">
        <kbd>↵</kbd> create <kbd>esc</kbd> cancel
        {blocked && !teamId && (
          <span className="error" role="alert">
            Pick a team first.
          </span>
        )}
        {create.isError && (
          <span className="error" role="alert">
            {(create.error as Error).message}
          </span>
        )}
        {create.isPending && <span style={{ marginLeft: "auto" }}>saving…</span>}
      </div>
    </Backdrop>
  );
}

export function Composer({ scope, onClose }: { scope: Scope; onClose: () => void }) {
  const teams = useQuery({ queryKey: keys.teams(false), queryFn: () => api.teams(false) });
  const projects = useQuery({ queryKey: keys.projects(false), queryFn: () => api.projects() });
  const users = useQuery({ queryKey: keys.users, queryFn: api.users });
  const me = useMe();

  /**
   * The form is only mounted once the lists are in hand, so its initial values are
   * the right ones on the very first render — the same pattern as `TitleEditor` in
   * `tickets.tsx`. Prefilling them afterwards would take an effect, which would
   * overwrite whatever somebody had already changed.
   */
  if (!teams.data || !projects.data) {
    return (
      <Backdrop onClose={onClose}>
        <div className="empty">Loading…</div>
      </Backdrop>
    );
  }

  // Filtered once, here, rather than inside the form: `creationSeed` and the select
  // must agree on what is offered, and computing it twice is how they would drift.
  const composable = teams.data.filter(isComposableTeam);
  if (composable.length === 0) {
    return (
      <Backdrop onClose={onClose}>
        {/* Not an empty select: that reads as a loading bug rather than the honest
            answer, which is that no team on this instance will currently accept a
            ticket from this person. */}
        <div className="empty">No team will accept a ticket from you yet.</div>
      </Backdrop>
    );
  }

  return (
    <ComposerForm
      teams={composable}
      projects={projects.data}
      users={users.data ?? []}
      meId={me.data?.user.id}
      scope={scope}
      onClose={onClose}
    />
  );
}
