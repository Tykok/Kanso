"use client";

import { useMemo, useRef, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  EFFORT_POINTS,
  TICKET_PRIORITIES,
  api,
  type EffortPoints,
  type Project,
  type Team,
  type TicketPriority,
  type User,
} from "@/lib/api";
import { creationSeed } from "@/lib/creation-seed";
import { keys, useMe } from "@/lib/queries";
import { PRIORITY_LABELS } from "@/lib/status";
import { cn } from "@/lib/utils";
import type { Scope } from "@/store/ui";
import { Button } from "./ui/button";
import { Kbd } from "./ui/kbd";
import { Backdrop } from "./overlays";

/** The bordered, 12px chip every context selector draws as — team, project,
 *  priority, assignee alike. A `<select>` still underneath: swapping it for a
 *  menu-driven picker would trade a native, fully keyboard-operable control for
 *  one this task would have to reimplement, for a screen that has not asked for it. */
const CHIP_SELECT =
  "min-w-0 flex-1 basis-32 rounded-md border border-border bg-card px-2.5 py-1 text-12 text-muted-foreground";

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
 * Which sentence an empty composable list owes the reader. `composable.length === 0`
 * is reached three ways and only one of them is about the actor: a fresh instance with
 * no team yet, and an instance where every team is archived, both surface here as
 * `teamCount === 0` — `api.teams(false)` already excludes archived rows, so the second
 * case is indistinguishable from the first by the time this runs. Anything else means
 * a team exists and simply will not take a ticket from this actor, which is the one
 * case the old, single sentence was actually about.
 */
export function composerEmptyReason(teamCount: number): "no-teams" | "not-editable" {
  return teamCount === 0 ? "no-teams" : "not-editable";
}

/**
 * The estimate select's value as the wire's `estimate`. Absent stays absent: `Number("")`
 * is `0`, and `0` on this field is not "unsized" but "somebody sized this at nothing" —
 * a number the cycle and workload screens would go on to sum. The membership test is what
 * keeps the two apart, and it costs nothing to let it also refuse a value that never came
 * from the select at all. The detail panel and the ticket page get away with
 * `Number(value) as EffortPoints` because they branch on truthiness first, and because
 * they only ever clear an estimate that already exists; creation is the one surface where
 * "nothing chosen" is the overwhelmingly common answer.
 */
export function chosenEstimate(value: string): EffortPoints | undefined {
  const points = Number(value);
  return (EFFORT_POINTS as readonly number[]).includes(points)
    ? (points as EffortPoints)
    : undefined;
}

/**
 * What [submit] posts, lifted out of the component because the interesting half of it is
 * what it leaves out. Three of these fields are absent rather than empty when nobody
 * chose one, and for `estimate` that difference is the ticket: a backlog of honestly
 * unsized tickets is readable, and one full of zeros is not.
 *
 * `title` arrives trimmed — the caller has already refused an empty one, so the trim is
 * a precondition here rather than a step.
 */
export function newTicketBody(form: {
  teamId: string;
  title: string;
  priority: TicketPriority;
  projectId: string;
  assigneeId: string;
  estimate: string;
}) {
  return {
    teamId: form.teamId,
    title: form.title,
    priority: form.priority,
    estimate: chosenEstimate(form.estimate),
    projectId: form.projectId ? form.projectId : undefined,
    assigneeIds: form.assigneeId ? [form.assigneeId] : undefined,
  };
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
  // Deliberately not seeded, and deliberately a string rather than an `EffortPoints`:
  // the empty string is the select's own "no choice", and every ticket starts there.
  const [estimate, setEstimate] = useState("");
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
    create.mutate(
      newTicketBody({ teamId, title: trimmed, priority, projectId, assigneeId, estimate }),
    );
  };

  const teamName = teams.find((team) => team.id === teamId)?.name;

  return (
    <Backdrop onClose={onClose} panelClassName="w-[640px]">
      <div className="flex items-center gap-2.5 px-4 py-2.5 text-11 text-faint">
        {teamName && (
          <span className="rounded-sm bg-accent px-1.5 py-0.5 text-11 text-muted-foreground">
            {teamName}
          </span>
        )}
        <span>New ticket</span>
      </div>

      <div className="px-4 pt-1 pb-4">
        <input
          className="w-full border-none bg-transparent p-0 text-21 font-medium tracking-tight text-foreground outline-none placeholder:text-faint"
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
      </div>

      <div className="flex flex-wrap items-center gap-1.5 bg-background px-4 py-2.5">
        <select
          ref={teamRef}
          // "Ticket team", not "Team": a `<select>`'s accessible name folds in its
          // options, so a bare "Team" collides across regions with the sidebar's
          // "New team" button. Named at the source rather than worked around in the
          // test's locator.
          aria-label="Ticket team"
          aria-invalid={blocked && !teamId ? true : undefined}
          className={cn(CHIP_SELECT, "aria-invalid:border-destructive")}
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
          className={CHIP_SELECT}
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
          className={CHIP_SELECT}
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
          className={CHIP_SELECT}
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

        {/* Last of the five, and last on purpose. The other four answer "where does this
            belong", which is known at the moment somebody types the title; sizing is a
            decision a team takes later, in refinement, so it is the one chip that must
            never stand between the fast path and anything. Tab from the title still
            reaches the team first, ↵ still creates without any of this being touched,
            and leaving it alone sends no `estimate` at all — see [chosenEstimate].

            The same dressed-down `<select>` as its four neighbours, built from the same
            `EFFORT_POINTS` the detail panel and the ticket page offer: one vocabulary,
            now on all three surfaces. No entry in `lib/actions/`, and not for want of a
            free key: the registry is a *list* keyboard, and `page.tsx` returns from its
            handler before `resolveShortcut` whenever a dialog is open, so an action
            registered for this could never fire. `1`–`6` are the selected ticket's
            status besides. The native select's own arrows and typeahead are the
            affordance, which is what the other four chips already rely on. */}
        <select
          aria-label="Estimate in points"
          className={CHIP_SELECT}
          value={estimate}
          disabled={create.isPending}
          onChange={(event) => setEstimate(event.target.value)}
        >
          <option value="">No estimate</option>
          {EFFORT_POINTS.map((points) => (
            // The unit is in the option, not beside the chip: unlabelled in a row of
            // four other chips, a bare "3" says nothing about what it counts.
            <option key={points} value={points}>
              {points} {points === 1 ? "pt" : "pts"}
            </option>
          ))}
        </select>

        <Button type="button" size="sm" disabled={create.isPending} onClick={submit}>
          Create
        </Button>
      </div>

      <div className="flex items-center gap-2 border-t border-border px-4 py-2 text-11 text-faint">
        <Kbd>↵</Kbd> <span>create</span> <Kbd>esc</Kbd> <span>cancel</span>
        {blocked && !teamId && (
          <span className="text-urgent" role="alert">
            Pick a team first.
          </span>
        )}
        {create.isError && (
          <span className="text-urgent" role="alert">
            {(create.error as Error).message}
          </span>
        )}
        {create.isPending && <span className="ml-auto">saving…</span>}
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
    // Not an empty select: that reads as a loading bug rather than the honest answer.
    // And the honest answer is not always about this person — `composerEmptyReason`
    // is what keeps a fresh instance, or one where every team is archived, from being
    // told a permissions story that has nothing to do with either.
    const reason = composerEmptyReason(teams.data.length);
    return (
      <Backdrop onClose={onClose}>
        <div className="empty">
          {reason === "no-teams"
            ? "There is no team yet to file a ticket into."
            : "No team will accept a ticket from you yet."}
        </div>
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
