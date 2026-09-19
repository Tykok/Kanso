import { describe, expect, it } from "vitest";

import { buildRows, spansTeams, type Row } from "./rows";
import type { TimelineProject, TimelineTicket, TimelineView } from "@/lib/api";

const ticket = (over: Partial<TimelineTicket> = {}): TimelineTicket => ({
  id: "t1",
  identifier: "KAN-1",
  title: "Work",
  status: "todo",
  teamKey: "KAN",
  critical: false,
  late: false,
  slipping: false,
  context: false,
  editable: true,
  ...over,
});

const project = (over: Partial<TimelineProject> = {}): TimelineProject => ({
  id: "p1",
  name: "Basket",
  ...over,
});

const view = (over: Partial<TimelineView> = {}): TimelineView => ({
  projects: [],
  tickets: [],
  dependencies: [],
  truncated: false,
  hasMore: false,
  ...over,
});

const kinds = (rows: Row[]) => rows.map((row) => row.kind);

describe("spansTeams", () => {
  it("is false for one team", () => {
    expect(spansTeams([ticket(), ticket({ id: "t2" })])).toBe(false);
  });

  it("is true the moment a second appears", () => {
    expect(spansTeams([ticket(), ticket({ id: "t2", teamKey: "SUP" })])).toBe(true);
  });
});

describe("buildRows", () => {
  it("draws no team heading when the scope is one team", () => {
    const rows = buildRows(
      view({ projects: [project()], tickets: [ticket({ projectId: "p1" })] }),
    );
    expect(kinds(rows)).toEqual(["project", "ticket"]);
  });

  it("draws a heading per team when the scope spans two", () => {
    const rows = buildRows(
      view({
        projects: [project(), project({ id: "p2", name: "Support" })],
        tickets: [
          ticket({ projectId: "p1" }),
          ticket({ id: "t2", teamKey: "SUP", projectId: "p2" }),
        ],
      }),
    );
    expect(kinds(rows)).toEqual(["team", "project", "ticket", "team", "project", "ticket"]);
  });

  it("keeps project-less tickets unheaded, after the projects of their own team", () => {
    const rows = buildRows(
      view({ projects: [project()], tickets: [ticket({ projectId: "p1" }), ticket({ id: "t2" })] }),
    );
    expect(kinds(rows)).toEqual(["project", "ticket", "ticket"]);
    expect(rows[2]).toMatchObject({ kind: "ticket", ticket: { id: "t2" } });
  });

  /**
   * The orphan rule the screen has always had. Falling out of the grouping would drop the
   * ticket from the chart entirely, which is worse than an unheaded row.
   */
  it("keeps a ticket whose project the response did not carry", () => {
    const rows = buildRows(view({ projects: [], tickets: [ticket({ projectId: "archived" })] }));
    expect(kinds(rows)).toEqual(["ticket"]);
  });

  it("gives a project with no tickets on this page its own row", () => {
    const rows = buildRows(view({ projects: [project()], tickets: [] }));
    expect(kinds(rows)).toEqual(["project"]);
  });

  it("draws no heading for a team with nothing on this page", () => {
    const rows = buildRows(
      view({
        projects: [],
        tickets: [ticket(), ticket({ id: "t2", teamKey: "SUP" })],
      }),
    );
    // Two teams, two headings, one orphan each — and no third heading for a team that
    // contributed nothing to this page.
    expect(kinds(rows)).toEqual(["team", "ticket", "team", "ticket"]);
  });

  /**
   * The row that used to vanish. A shared project is filed under whichever team's ticket
   * named it first, so a ticket from the other team pointing at that same project was in
   * no project group of its own and was not an orphan either. The e2e suite caught it;
   * this is the cheap version of that test.
   */
  it("never drops a ticket whose project was grouped under another team", () => {
    const rows = buildRows(
      view({
        projects: [project({ id: "shared", name: "Shared" })],
        tickets: [
          ticket({ id: "mine", projectId: "shared" }),
          ticket({ id: "theirs", teamKey: "SUP", projectId: "shared" }),
        ],
      }),
    );
    const ids = rows.flatMap((row) => (row.kind === "ticket" ? [row.ticket.id] : []));
    expect(ids).toContain("mine");
    expect(ids).toContain("theirs");
  });

  it("is empty for no view at all rather than throwing", () => {
    expect(buildRows(undefined)).toEqual([]);
  });
});
