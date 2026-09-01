import { describe, expect, it } from "vitest";
import { EFFORT_POINTS, type TicketPriority } from "@/lib/api";
import {
  chosenEstimate,
  composerEmptyReason,
  isComposableTeam,
  newTicketBody,
} from "./composer";

describe("isComposableTeam", () => {
  // All four combinations of the two reasons a team can be unofferable. Exactly one
  // is `true`: neither archived, nor refused by the server's own rule.
  const CASES: { archived: boolean; editable: boolean; expected: boolean }[] = [
    { archived: false, editable: true, expected: true },
    { archived: false, editable: false, expected: false },
    { archived: true, editable: true, expected: false },
    { archived: true, editable: false, expected: false },
  ];

  it("is true only for a team that is both active and editable", () => {
    for (const { archived, editable, expected } of CASES) {
      expect(
        isComposableTeam({ archived, editable }),
        `archived=${archived} editable=${editable}`,
      ).toBe(expected);
    }
  });
});

describe("composerEmptyReason", () => {
  // Zero teams is the fresh-instance case and the every-team-archived case at once —
  // `api.teams(false)` already excludes archived rows, so both land on the same count.
  // Anything above zero means a team exists and the actor is the reason none of them
  // are composable.
  const CASES: { teamCount: number; expected: "no-teams" | "not-editable" }[] = [
    { teamCount: 0, expected: "no-teams" },
    { teamCount: 1, expected: "not-editable" },
    { teamCount: 5, expected: "not-editable" },
  ];

  it("separates 'nothing exists' from 'nothing will take yours'", () => {
    for (const { teamCount, expected } of CASES) {
      expect(composerEmptyReason(teamCount), `teamCount=${teamCount}`).toBe(expected);
    }
  });
});

describe("chosenEstimate", () => {
  // The empty option is the case the whole guard exists for: `Number("")` is `0`, and a
  // `0` on the wire is a ticket somebody sized at nothing rather than one nobody sized.
  it("reads the empty option as unsized, never as zero", () => {
    expect(chosenEstimate("")).toBeUndefined();
  });

  it("passes every point on the scale through unchanged", () => {
    for (const points of EFFORT_POINTS) {
      expect(chosenEstimate(String(points)), `points=${points}`).toBe(points);
    }
  });

  // The select cannot offer any of these — its options are built from `EFFORT_POINTS` —
  // so this pins the boundary against everything that is not the select: a hand-edited
  // DOM, a later caller, a scale the server grows before this file does. Unsized is the
  // answer rather than a throw, because the composer's job is still to file the ticket.
  const OFF_SCALE = ["0", "4", "7", "21", "-1", "1.5", "abc", " "];

  it("refuses anything the scale does not name", () => {
    for (const value of OFF_SCALE) {
      expect(chosenEstimate(value), `value=${JSON.stringify(value)}`).toBeUndefined();
    }
  });
});

describe("newTicketBody", () => {
  const FORM = {
    teamId: "team-1",
    title: "Ship the composer",
    priority: "none" as TicketPriority,
    projectId: "",
    assigneeId: "",
    estimate: "",
  };

  // The caution this ticket is built around: `c` is the fast path, and a ticket born on
  // it without anybody touching the new control has to reach the server carrying no size
  // at all — `undefined`, not the `0` the velocity screens would then sum.
  it("sends no estimate at all when the control was never touched", () => {
    const body = newTicketBody(FORM);
    expect(body.estimate).toBeUndefined();
    // What `JSON.stringify` does to an undefined value is the real wire behaviour, and
    // the difference between an absent field and a present zero is the whole ticket.
    expect(JSON.parse(JSON.stringify(body))).not.toHaveProperty("estimate");
  });

  it("carries a chosen point value into the request body", () => {
    expect(newTicketBody({ ...FORM, estimate: "5" }).estimate).toBe(5);
    expect(JSON.parse(JSON.stringify(newTicketBody({ ...FORM, estimate: "13" })))).toMatchObject({
      estimate: 13,
    });
  });

  // Sizing must not be able to break creation: an off-scale value files the ticket
  // unsized rather than sending something `tickets_estimate_chk` would refuse outright.
  it("still files the ticket when the estimate is off-scale", () => {
    const body = newTicketBody({ ...FORM, estimate: "7" });
    expect(body.estimate).toBeUndefined();
    expect(body.title).toBe("Ship the composer");
    expect(body.teamId).toBe("team-1");
  });

  // The other four fields are untouched by this ticket, and are pinned here so that
  // lifting `submit`'s body out of the component cannot have quietly altered one.
  it("leaves an empty project and assignee absent rather than blank", () => {
    const body = newTicketBody(FORM);
    expect(body.projectId).toBeUndefined();
    expect(body.assigneeIds).toBeUndefined();
    expect(newTicketBody({ ...FORM, projectId: "p1", assigneeId: "u1" })).toMatchObject({
      projectId: "p1",
      assigneeIds: ["u1"],
    });
  });
});
