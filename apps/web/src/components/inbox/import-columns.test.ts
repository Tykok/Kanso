import { describe, expect, it } from "vitest";
import {
  answeredFields,
  openFallbacks,
  suggestionsFrom,
  unmappedOptions,
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
    const kept = { tasks: "tickets", projects: "projects" } as const;
    expect(openFallbacks(SCHEMA, { columns: {}, values: {} }, kept)).toEqual(["projectId", "teamId"]);
    expect(openFallbacks(SCHEMA, { columns: { project: "Projet" }, values: {} }, kept)).toEqual([]);
    expect(openFallbacks(SCHEMA, { columns: { project: "Projet" }, values: {} }, { tasks: "tickets" })).toEqual([
      "projectId",
      "teamId",
    ]);
  });
});
