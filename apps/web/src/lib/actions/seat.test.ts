import { describe, expect, it } from "vitest";
import { ACTIONS, actionById, permits, type ActionContext } from "./index";

/**
 * The registry, classified — and the classification is the test.
 *
 * `Action.writes` decides what a read-only seat is offered, and the failure mode is not a
 * wrong flag on an action somebody thought about; it is a *new* action nobody thought
 * about. So both lists below are written out in full and the first assertion demands that
 * every id in `ACTIONS` appears in exactly one of them. Adding an action to the registry
 * without deciding whether it writes fails here, with a message that says so.
 *
 * This is the client-side twin of `ReadOnlySeatLeakTest`, which does the same thing to the
 * API's route table. Neither is a permission — the server is — but a menu of eight things
 * that all come back 403 is a lie, and this is what stops the ninth from joining them.
 */
const WRITES = [
  "ticket.create",
  "ticket.rename",
  "ticket.archive",
  "ticket.delete",
  "ticket.status.backlog",
  "ticket.status.todo",
  "ticket.status.in_progress",
  "ticket.status.in_review",
  "ticket.status.done",
  "ticket.status.canceled",
  "ticket.priority.none",
  "ticket.priority.low",
  "ticket.priority.medium",
  "ticket.priority.high",
  "ticket.priority.urgent",
  "team.create",
  "team.rename",
  "team.archive",
  "team.unarchive",
  "team.delete",
  "project.create",
  "project.edit",
  "project.archive",
  "project.unarchive",
  "project.delete",
  "timeline.shiftEarlier",
  "timeline.shiftLater",
  "timeline.shrinkEnd",
  "timeline.growEnd",
  "timeline.schedule",
  "timeline.unschedule",
  "timeline.link",
  "timeline.unlink",
  "notion.import",
  "organise.saveView",
];

/**
 * Everything else, and three of them are worth defending.
 *
 * `favourite.toggle` writes a row and is still here: it changes one person's sidebar, and
 * `ReadOnlySeat.OWN_SCREEN` lets a reader do exactly that. Hiding it would take away
 * something the API is happy to answer.
 *
 * `organise.groupBy` and `organise.sortBy` rearrange the list on screen; nothing is
 * posted. The one in that family that *is* a write is `organise.saveView`, because saving
 * a view is the moment a private arrangement becomes the team's.
 *
 * The timeline's `zoom` and `today` are the same argument as grouping — they move the
 * viewport — while `shift`, `grow`, `shrink` and `schedule` move the *plan*, and those are
 * above.
 */
const READS = [
  "ticket.open",
  "ticket.moveDown",
  "ticket.moveUp",
  "view.all",
  "view.filter",
  "timeline.zoomOut",
  "timeline.zoomIn",
  "timeline.today",
  "app.palette",
  "app.settings",
  "app.help",
  "app.logout",
  "board.columnLeft",
  "board.columnRight",
  "board.moveDown",
  "board.moveUp",
  "board.open",
  "organise.addFilter",
  "organise.groupBy",
  "organise.sortBy",
  "organise.select",
  "organise.selectRange",
  "organise.clearSelection",
  "favourite.toggle",
];

/**
 * `permits` short-circuits on the seat before it consults `when`, so a writing action is
 * refused without any field of the context being read. That is what lets this stand in
 * for a context: anything it touched would be a field the seat check should not have
 * needed.
 */
const seatOnly = (canWrite: boolean) => ({ canWrite }) as unknown as ActionContext;

describe("the read-only seat, against the action registry", () => {
  it("classifies every action in the registry, and nothing that is not in it", () => {
    const registered = ACTIONS.map((action) => action.id).sort();
    const classified = [...WRITES, ...READS].sort();

    expect(classified, "an action was added without deciding whether it writes").toEqual(registered);
  });

  it("flags exactly the writing actions, and no others", () => {
    for (const id of WRITES) {
      expect(actionById(id).writes, `${id} changes the team's data and must say so`).toBe(true);
    }
    for (const id of READS) {
      expect(actionById(id).writes ?? false, `${id} does not write, so it must not be flagged`).toBe(false);
    }
  });

  it("offers a reader none of them, whatever their scope or selection would allow", () => {
    const reader = seatOnly(false);
    for (const id of WRITES) {
      expect(permits(actionById(id), reader), `${id} was offered to a read-only seat`).toBe(false);
    }
  });

  it("still offers them to a seat that writes, so the gate is the seat and not the flag", () => {
    // Without this the suite above would pass on a `permits` that refused everything, which
    // is a different bug and a much louder one.
    const writer = { canWrite: true, scope: { kind: "all" } } as unknown as ActionContext;
    expect(permits(actionById("ticket.create"), writer)).toBe(true);
  });
});
