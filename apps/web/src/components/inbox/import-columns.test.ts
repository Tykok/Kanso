import { describe, expect, it } from "vitest";
import {
  answeredFields,
  openFallbacks,
  suggestionsFrom,
  unmappedOptions,
  type MappedBase,
  type NotionImportSchema,
} from "./import-columns";

const SCHEMA: NotionImportSchema = {
  sourceId: "tasks",
  target: "tickets",
  columns: [
    { name: "Etat", type: "select", options: ["En cours", "Bloqué"] },
    { name: "Projet", type: "relation", options: [], relationTo: "projects" },
  ],
  fields: [
    { field: "status", candidates: ["Etat"] },
    { field: "project", candidates: ["Projet"] },
  ],
  suggestion: { columns: {}, values: {} },
  defaults: { status: "todo", project: null },
};

describe("what the columns screen derives", () => {
  it("counts the fields that have an answer, which is the header's number", () => {
    expect(answeredFields(SCHEMA, { columns: { status: "Etat" }, values: {} })).toBe(1);
  });

  it("names the options still falling on the default, which is what the warning lists", () => {
    const mapping = { columns: { status: "Etat" }, values: { status: { "En cours": "in_progress" } } };
    expect(unmappedOptions(SCHEMA, mapping, "status")).toEqual(["Bloqué"]);
  });

  it("suggests importing the base a mapped relation points at, and only while it is ignored", () => {
    const mapping = { columns: { project: "Projet" }, values: {} };
    expect(suggestionsFrom({ ...SCHEMA, suggestion: mapping }, { tasks: "tickets" })).toEqual([
      { sourceId: "projects", target: "projects" },
    ]);
    expect(suggestionsFrom({ ...SCHEMA, suggestion: mapping }, { tasks: "tickets", projects: "projects" })).toEqual([]);
  });

  it("asks for a fallback only where the link cannot be resolved, and stops asking once it can", () => {
    const tasks = (columns: Record<string, string>): MappedBase => ({
      schema: SCHEMA,
      mapping: { columns, values: {} },
    });
    const kept = { tasks: "tickets", projects: "projects" } as const;

    expect(openFallbacks(tasks({}), kept, [tasks({})])).toEqual(["projectId", "teamId"]);
    const mapped = tasks({ project: "Projet" });
    expect(openFallbacks(mapped, kept, [mapped])).toEqual([]);
    expect(openFallbacks(mapped, { tasks: "tickets" }, [mapped])).toEqual(["projectId", "teamId"]);
  });

  /**
   * `ImportLinks` keys a relation's ends by target — `adopted[parentTarget]` — so a
   * `project` relation into a base kept as *tickets* resolves to nothing and is counted as
   * dropped. Reading "kept at all" instead would ask for no fallback and warn about
   * nothing, and every ticket would land in one auto-named project with no sign why.
   */
  it("counts a relation into a base kept as the wrong kind as unresolvable", () => {
    const mapped: MappedBase = { schema: SCHEMA, mapping: { columns: { project: "Projet" }, values: {} } };
    const everythingIsATicket = { tasks: "tickets", projects: "tickets" } as const;

    expect(openFallbacks(mapped, everythingIsATicket, [mapped])).toEqual(["projectId", "teamId"]);
    expect(suggestionsFrom({ ...SCHEMA, suggestion: mapped.mapping }, everythingIsATicket)).toEqual([
      { sourceId: "projects", target: "projects" },
    ]);
  });

  /**
   * The other direction: `resolveOneToOne` also reads the parent's inverse column, and a
   * `single_property` relation exists on one side only — so a workspace carrying the link
   * on the projects base alone resolves, and asking for a fallback there would be the
   * screen inventing a question the server does not have.
   */
  it("resolves a link the parent's own column carries, with nothing on the child", () => {
    const tasks: MappedBase = { schema: SCHEMA, mapping: { columns: {}, values: {} } };
    const projects: MappedBase = {
      schema: {
        sourceId: "projects",
        target: "projects",
        columns: [{ name: "Tâches", type: "relation", options: [], relationTo: "tasks" }],
        fields: [{ field: "tickets", candidates: ["Tâches"] }],
        suggestion: { columns: {}, values: {} },
        defaults: {},
      },
      mapping: { columns: { tickets: "Tâches" }, values: {} },
    };
    const kept = { tasks: "tickets", projects: "projects" } as const;

    expect(openFallbacks(tasks, kept, [tasks, projects])).toEqual([]);
    // The same column on a base kept as documents carries nothing: `adopted[PROJECTS]`
    // holds none of its pages, so the relation has no end to resolve to.
    expect(openFallbacks(tasks, { tasks: "tickets", projects: "documents" }, [tasks, projects])).toEqual([
      "projectId",
      "teamId",
    ]);
  });
});
