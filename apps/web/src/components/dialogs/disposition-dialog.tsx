"use client";

import { useMemo, useState, type ReactNode } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ApiError,
  api,
  type DispositionChoice,
  type DispositionCounts,
  type DispositionPlan,
  type Team,
} from "@/lib/api";
import { keys, useContents } from "@/lib/queries";
import { useUi } from "@/store/ui";
import { DialogFrame, Field } from "./field";

/**
 * The drift 409 carries the fresh counts in the problem body. They are read
 * defensively: a body without them is an ordinary 409, which must show as an error
 * rather than reopen the dialog on zeroes.
 */
function countsFrom(error: unknown): DispositionCounts | undefined {
  if (!(error instanceof ApiError) || error.status !== 409) return undefined;
  const body = error.body;
  if (typeof body !== "object" || body === null || !("counts" in body)) return undefined;
  const raw = (body as { counts: unknown }).counts;
  if (typeof raw !== "object" || raw === null) return undefined;
  const record = raw as Record<string, unknown>;
  const read = (field: string): number | undefined =>
    typeof record[field] === "number" ? (record[field] as number) : undefined;
  const subTeams = read("subTeams");
  const projects = read("projects");
  const tickets = read("tickets");
  if (subTeams === undefined || projects === undefined || tickets === undefined) return undefined;
  return { subTeams, projects, tickets };
}

/** A team's descendants, itself included: never a valid destination. */
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
 * One category and its choice. Radio buttons rather than a segmented control: the
 * arrows walk the group for free, and the group's name is readable by a screen reader
 * without extra work.
 */
function ChoiceRow({
  group,
  count,
  noun,
  keepLabel,
  takeLabel,
  value,
  onChange,
  children,
}: {
  group: string;
  count: number;
  noun: string;
  keepLabel: string;
  takeLabel: string;
  value: DispositionChoice;
  onChange: (value: DispositionChoice) => void;
  children?: ReactNode;
}) {
  return (
    <div className="disposition-row">
      <div className="disposition-count">
        {count} {noun}
      </div>
      <div className="disposition-cell">
        <div className="disposition-choices" role="radiogroup" aria-label={noun}>
          <label className="disposition-choice">
            <input
              type="radio"
              name={group}
              checked={value === "keep"}
              onChange={() => onChange("keep")}
            />
            <span>{keepLabel}</span>
          </label>
          <label className="disposition-choice">
            <input
              type="radio"
              name={group}
              checked={value === "take"}
              onChange={() => onChange("take")}
            />
            <span>{takeLabel}</span>
          </label>
        </div>
        {value === "keep" && children}
      </div>
    </div>
  );
}

/**
 * One component for both verbs.
 *
 * Archiving and deleting ask exactly the same questions — what becomes of what this
 * holds? — and part only on what confirms them: one button on one side, the name
 * retyped on the other, and a 409 on counts that moved only on the side that does not
 * come back.
 *
 * The renumbering warning, on the other hand, shows in both: moving a ticket renames
 * it for good, whether the team it left was archived or deleted.
 */
export function DispositionDialog({
  target,
  severity,
  onClose,
}: {
  target: { kind: "team" | "project"; id: string };
  severity: "archive" | "delete";
  onClose: () => void;
}) {
  const queryClient = useQueryClient();
  const scope = useUi((state) => state.scope);
  const setScope = useUi((state) => state.setScope);

  const teams = useQuery({ queryKey: keys.teams(true), queryFn: () => api.teams(true) });
  const projects = useQuery({
    queryKey: keys.projects(true),
    queryFn: () => api.projects({ includeArchived: true }),
    enabled: target.kind === "project",
  });
  // `useContents`, not a second copy of it: that hook carries `gcTime: 0` as well as
  // `staleTime: 0`, and without the eviction a dialog reopened inside five minutes
  // paints the counts of the previous visit — and archiving sends no counts, so the
  // server has nothing to catch it with.
  const contents = useContents(target.kind, target.id);

  const [subTeams, setSubTeams] = useState<DispositionChoice>("keep");
  const [projectChoice, setProjectChoice] = useState<DispositionChoice>("keep");
  const [ticketChoice, setTicketChoice] = useState<DispositionChoice>("keep");
  const [destination, setDestination] = useState("");
  const [confirmation, setConfirmation] = useState("");
  const [problem, setProblem] = useState<"destination" | "confirmation" | null>(null);

  const isTeam = target.kind === "team";
  const subject = isTeam
    ? teams.data?.find((row) => row.id === target.id)
    : projects.data?.find((row) => row.id === target.id);
  const name = subject?.name;
  const sourceKey = isTeam ? teams.data?.find((row) => row.id === target.id)?.key : undefined;

  const destinations = useMemo(() => {
    if (!isTeam || !teams.data) return [];
    const banned = subtreeIds(teams.data, target.id);
    return teams.data
      .filter((row) => !banned.has(row.id) && !row.archived)
      .sort((a, b) => a.name.localeCompare(b.name));
  }, [isTeam, teams.data, target.id]);

  const run = useMutation({
    mutationFn: async (plan: DispositionPlan): Promise<void> => {
      if (target.kind === "team") {
        if (severity === "delete") await api.deleteTeam(target.id, plan);
        else await api.archiveTeam(target.id, plan);
        return;
      }
      if (severity === "delete") await api.deleteProject(target.id, plan);
      else await api.archiveProject(target.id, plan);
    },
    onSuccess: () => {
      // The list would otherwise keep filtering on something that is no longer there.
      if (scope.kind !== "all" && scope.kind === target.kind && scope.id === target.id) {
        setScope({ kind: "all" });
      }
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      queryClient.invalidateQueries({ queryKey: ["projects"] });
      queryClient.invalidateQueries({ queryKey: ["tickets"] });
      onClose();
    },
    onError: (error) => {
      if (!countsFrom(error)) return;
      // The dialog reopens on the truth, and consent starts over: whoever agreed to
      // destroy 47 tickets did not agree to destroy 50. The 409 carries only the
      // counts for the plan that was sent, so both readings are asked for again
      // rather than written from it — flipping the radio afterwards has to be right
      // too.
      contents.refetch();
      setConfirmation("");
      setProblem(null);
    },
  });

  // What the chosen plan will actually reach. The sub-teams radio is the only thing
  // that moves it: keep, and the sub-trees leave intact; take, and everything under
  // them is what is being destroyed, archived and renumbered.
  const counts: DispositionCounts | undefined =
    contents.data && (subTeams === "take" ? contents.data.subtree : contents.data.direct);
  const drift = run.error ? countsFrom(run.error) : undefined;
  const footerError = run.error && !drift ? (run.error as Error).message : null;

  if (!counts || name === undefined) {
    return (
      <DialogFrame
        title={severity === "delete" ? "Delete" : "Archive"}
        onClose={onClose}
        onSubmit={onClose}
        submitLabel={severity === "delete" ? "Delete" : "Archive"}
        submitDanger={severity === "delete"}
        pending
        error={contents.error ? (contents.error as Error).message : null}
      >
        <div className="empty">Counting what this holds…</div>
      </DialogFrame>
    );
  }

  const verb = severity === "delete" ? "Delete" : "Archive";
  const takeLabel = severity === "delete" ? "Delete with it" : "Archive with it";
  const empty = counts.subTeams === 0 && counts.projects === 0 && counts.tickets === 0;
  const needsDestination = isTeam && counts.tickets > 0 && ticketChoice === "keep";
  const movingTickets = needsDestination && destination !== "";
  const destinationKey = destinations.find((row) => row.id === destination)?.key;

  const submit = () => {
    if (needsDestination && !destination) {
      setProblem("destination");
      return;
    }
    if (severity === "delete" && confirmation.trim() !== name) {
      setProblem("confirmation");
      return;
    }
    setProblem(null);
    run.mutate({
      subTeams,
      projects: projectChoice,
      tickets: ticketChoice,
      ticketsTargetTeamId: needsDestination ? destination : undefined,
      // Deletion compares; archiving ignores, because it comes back.
      counts: severity === "delete" ? counts : undefined,
    });
  };

  return (
    <DialogFrame
      title={`${verb} “${name}”`}
      onClose={onClose}
      onSubmit={submit}
      submitLabel={verb}
      submitDanger={severity === "delete"}
      pending={run.isPending}
      error={footerError}
    >
      {drift && (
        <div className="disposition-drift" role="alert">
          The contents changed while this was open, so nothing was deleted. The numbers below are
          the current ones — read them again before confirming.
        </div>
      )}

      {empty ? (
        <p className="disposition-lede">
          This {isTeam ? "team" : "project"} holds nothing.{" "}
          {severity === "delete"
            ? "Deleting it removes it for good."
            : "Archiving it only hides it; Show archived brings it back."}
        </p>
      ) : (
        <>
          <p className="disposition-lede">This {isTeam ? "team" : "project"} contains:</p>

          {isTeam && counts.subTeams > 0 && (
            <ChoiceRow
              group="disposition-sub-teams"
              count={counts.subTeams}
              noun="sub-teams"
              keepLabel="Keep active, under the grandparent"
              takeLabel={takeLabel}
              value={subTeams}
              onChange={setSubTeams}
            />
          )}

          {isTeam && counts.projects > 0 && (
            <ChoiceRow
              group="disposition-projects"
              count={counts.projects}
              noun="projects"
              keepLabel="Keep active, under the parent team"
              takeLabel={takeLabel}
              value={projectChoice}
              onChange={setProjectChoice}
            />
          )}

          {counts.tickets > 0 && (
            <ChoiceRow
              group="disposition-tickets"
              count={counts.tickets}
              noun="tickets"
              keepLabel={isTeam ? "Keep active, move to another team" : "Keep active, without a project"}
              takeLabel={takeLabel}
              value={ticketChoice}
              onChange={setTicketChoice}
            >
              {isTeam && (
                <Field
                  label="Destination team"
                  error={problem === "destination" ? "Choose the team these tickets move to." : null}
                >
                  <select
                    value={destination}
                    aria-invalid={problem === "destination" ? true : undefined}
                    onChange={(event) => {
                      setDestination(event.target.value);
                      setProblem(null);
                    }}
                  >
                    <option value="">— pick a team —</option>
                    {destinations.map((row) => (
                      <option key={row.id} value={row.id}>
                        {row.name}
                      </option>
                    ))}
                  </select>
                </Field>
              )}
            </ChoiceRow>
          )}

          {movingTickets && (
            <div className="disposition-warning" role="note">
              <strong>
                ⚠ The {counts.tickets} tickets will be renumbered.
              </strong>
              <span>
                Every <code>{sourceKey}-…</code> identifier becomes a <code>{destinationKey}-…</code>{" "}
                one. Existing links stop resolving.
              </span>
            </div>
          )}
        </>
      )}

      {severity === "delete" ? (
        <Field
          label={`Type ${name} to confirm`}
          error={problem === "confirmation" ? `Type the name exactly: ${name}` : null}
        >
          <input
            autoFocus
            value={confirmation}
            aria-invalid={problem === "confirmation" ? true : undefined}
            onChange={(event) => {
              setConfirmation(event.target.value);
              setProblem(null);
            }}
          />
        </Field>
      ) : (
        <p className="disposition-note">Reversible from Show archived, at the foot of the sidebar.</p>
      )}
    </DialogFrame>
  );
}
