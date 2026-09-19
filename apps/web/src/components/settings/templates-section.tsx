"use client";

import { useState } from "react";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  EFFORT_POINTS,
  TICKET_PRIORITIES,
  type TemplateBody,
  type TicketTemplate,
} from "@/lib/api";
import {
  useCreateTemplate,
  useDeleteTemplate,
  useMe,
  useTeams,
  useTemplates,
  useUpdateTemplate,
} from "@/lib/queries";
import { mayWrite } from "@/lib/seat";
import { PRIORITY_LABELS } from "@/lib/status";
import { SettingsNote } from "./field";

/**
 * Labels and custom fields are typed as *names* here, not picked from a list.
 *
 * That is not a shortcut around building a picker. An instance template is written before
 * any team exists, so there is no list to pick from; and a team template that stored ids
 * would have to be either cascaded when a label is deleted — silently losing part of the
 * template — or block the deletion. `TemplateResolver` turns the name into a row at the
 * moment somebody uses the template, and says so when it cannot.
 */
const asNames = (raw: string): string[] =>
  raw
    .split(",")
    .map((name) => name.trim())
    .filter((name) => name.length > 0);

/** `Severity = major`, one per line. Values stay text; the server types them per field. */
const asFields = (raw: string): Record<string, string> =>
  Object.fromEntries(
    raw
      .split("\n")
      .map((line) => line.split("="))
      .filter((parts) => parts.length >= 2 && parts[0].trim().length > 0)
      .map((parts) => [parts[0].trim(), parts.slice(1).join("=").trim()]),
  );

const fieldLines = (fields: TemplateBody["fields"]): string =>
  Object.entries(fields ?? {})
    .map(([name, value]) => `${name} = ${String(value)}`)
    .join("\n");

type Draft = {
  name: string;
  summary: string;
  categories: string;
  title: string;
  description: string;
  priority: string;
  estimate: string;
  labels: string;
  fields: string;
};

const emptyDraft = (): Draft => ({
  name: "",
  summary: "",
  categories: "",
  title: "",
  description: "",
  priority: "",
  estimate: "",
  labels: "",
  fields: "",
});

const draftOf = (template: TicketTemplate): Draft => ({
  name: template.name,
  summary: template.summary ?? "",
  categories: template.categories.join(", "),
  title: template.body.title ?? "",
  description: template.body.description ?? "",
  priority: template.body.priority ?? "",
  estimate: template.body.estimate === undefined ? "" : String(template.body.estimate),
  labels: (template.body.labels ?? []).join(", "),
  fields: fieldLines(template.body.fields),
});

/**
 * An absent key means *do not pre-fill this*, and an empty string means *start blank*.
 *
 * The form cannot tell the two apart — a cleared input is `""` either way — so this reads
 * the empty box as the absence, which is the commoner intent by far. A template that wants
 * to blank a field the composer would otherwise seed is not expressible here, and is not
 * worth a second control on every row to express.
 */
const bodyOf = (draft: Draft): TemplateBody => ({
  ...(draft.title ? { title: draft.title } : {}),
  ...(draft.description ? { description: draft.description } : {}),
  ...(draft.priority ? { priority: draft.priority as TemplateBody["priority"] } : {}),
  ...(draft.estimate ? { estimate: Number(draft.estimate) as TemplateBody["estimate"] } : {}),
  ...(draft.labels ? { labels: asNames(draft.labels) } : {}),
  ...(draft.fields ? { fields: asFields(draft.fields) } : {}),
});

/**
 * The catalogue, at both levels.
 *
 * Kanso's templates are an instance administrator's; a team's are anybody who may edit that
 * team, which `TicketAccess.requireTeam` already grants an instance admin too. So this
 * screen is offered to members as well, and the only thing gated inside it is the *Kanso*
 * group — which is the same shape `GithubSection` uses for its admin-only half.
 *
 * There is no category management screen, and there should not be one: a category exists
 * exactly as long as some template names it, so a list of them has no independent existence
 * to administer.
 */
export function TemplatesSection() {
  const me = useMe();
  const teams = useTeams();
  const rows = teams.data ?? [];
  const [chosen, setChosen] = useState<string>();
  const team = rows.find((candidate) => candidate.id === chosen) ?? rows[0];

  const templates = useTemplates(team?.id);
  const create = useCreateTemplate();
  const update = useUpdateTemplate();
  const remove = useDeleteTemplate();

  const [editing, setEditing] = useState<string>();
  const [draft, setDraft] = useState<Draft>(emptyDraft());
  const [level, setLevel] = useState<"team" | "instance">("team");

  const role = me.data?.user.instanceRole;
  const canConfigure = role === "owner" || role === "admin";
  const writable = mayWrite(me.data?.user.instanceRole);
  const all = templates.data ?? [];
  const shipped = all.filter((template) => template.teamId === null);
  const own = all.filter((template) => template.teamId !== null);

  if (!team) {
    return (
      <SettingsNote>No team on this instance yet, so no team templates to write.</SettingsNote>
    );
  }

  const set = (patch: Partial<Draft>) => setDraft((current) => ({ ...current, ...patch }));

  const startNew = (at: "team" | "instance") => {
    setEditing("new");
    setLevel(at);
    setDraft(emptyDraft());
  };

  const startEdit = (template: TicketTemplate) => {
    setEditing(template.id);
    setLevel(template.teamId === null ? "instance" : "team");
    setDraft(draftOf(template));
  };

  const save = () => {
    const payload = {
      name: draft.name,
      summary: draft.summary || undefined,
      body: bodyOf(draft),
      categories: asNames(draft.categories),
    };
    if (editing === "new") {
      create.mutate(
        { ...payload, ...(level === "team" ? { teamId: team.id } : {}) },
        { onSuccess: () => setEditing(undefined) },
      );
    } else if (editing) {
      update.mutate({ id: editing, body: payload }, { onSuccess: () => setEditing(undefined) });
    }
  };

  const row = (template: TicketTemplate) => (
    <div key={template.id} className="flex items-center gap-2 py-1">
      <span className="text-13 text-foreground">{template.name}</span>
      {template.summary && <span className="text-11 text-faint">{template.summary}</span>}
      {template.categories.map((category) => (
        <span
          key={category}
          className="rounded-full border border-border px-1.5 text-11 text-faint"
        >
          {category}
        </span>
      ))}
      {writable && (template.teamId !== null || canConfigure) && (
        <span className="ml-auto flex gap-1">
          <Button type="button" size="sm" variant="ghost" onClick={() => startEdit(template)}>
            Edit
          </Button>
          <Button
            type="button"
            size="sm"
            variant="ghost"
            onClick={() => remove.mutate(template.id)}
          >
            Delete
          </Button>
        </span>
      )}
    </div>
  );

  return (
    <section className="flex flex-col gap-6">
      <h2 className="text-21 font-medium tracking-tight">Ticket templates</h2>

      <label className="flex items-center gap-2 text-13">
        <span className="text-muted-foreground">Team</span>
        <select
          className="rounded-md border border-border bg-card px-2 py-1 text-13"
          value={team.id}
          onChange={(event) => setChosen(event.target.value)}
        >
          {rows.map((candidate) => (
            <option key={candidate.id} value={candidate.id}>
              {candidate.name}
            </option>
          ))}
        </select>
      </label>

      <div className="flex flex-col gap-1">
        <h3 className="text-13 font-medium">Kanso</h3>
        <SettingsNote>
          Shipped with the instance and offered in every team. Editable by an administrator.
        </SettingsNote>
        {shipped.map(row)}
        {canConfigure && (
          <Button type="button" size="sm" variant="ghost" onClick={() => startNew("instance")}>
            Add a Kanso template
          </Button>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <h3 className="text-13 font-medium">{team.name}</h3>
        {own.length === 0 && <SettingsNote>This team has written none of its own.</SettingsNote>}
        {own.map(row)}
        {writable && (
          <Button type="button" size="sm" variant="ghost" onClick={() => startNew("team")}>
            Add a template for {team.name}
          </Button>
        )}
      </div>

      {editing && (
        <div className="flex flex-col gap-2 rounded-md border border-border p-3">
          <Input
            placeholder="Name"
            value={draft.name}
            onChange={(e) => set({ name: e.target.value })}
          />
          <Input
            placeholder="One line under the name"
            value={draft.summary}
            onChange={(e) => set({ summary: e.target.value })}
          />
          <Input
            placeholder="Categories, comma separated"
            value={draft.categories}
            onChange={(e) => set({ categories: e.target.value })}
          />
          <Input
            placeholder="Title the composer starts with"
            value={draft.title}
            onChange={(e) => set({ title: e.target.value })}
          />
          <textarea
            className="h-40 w-full resize-y rounded-md border border-border bg-card p-2 text-13"
            placeholder="Description skeleton"
            value={draft.description}
            onChange={(e) => set({ description: e.target.value })}
          />
          <div className="flex gap-2">
            <select
              className="rounded-md border border-border bg-card px-2 py-1 text-13"
              value={draft.priority}
              onChange={(e) => set({ priority: e.target.value })}
            >
              <option value="">No priority</option>
              {TICKET_PRIORITIES.map((priority) => (
                <option key={priority} value={priority}>
                  {PRIORITY_LABELS[priority]}
                </option>
              ))}
            </select>
            <select
              className="rounded-md border border-border bg-card px-2 py-1 text-13"
              value={draft.estimate}
              onChange={(e) => set({ estimate: e.target.value })}
            >
              <option value="">No estimate</option>
              {EFFORT_POINTS.map((points) => (
                <option key={points} value={points}>
                  {points} {points === 1 ? "pt" : "pts"}
                </option>
              ))}
            </select>
          </div>
          <Input
            placeholder="Labels by name, comma separated"
            value={draft.labels}
            onChange={(e) => set({ labels: e.target.value })}
          />
          <textarea
            className="h-20 w-full resize-y rounded-md border border-border bg-card p-2 text-13"
            placeholder="Custom fields, one per line: Severity = major"
            value={draft.fields}
            onChange={(e) => set({ fields: e.target.value })}
          />
          <SettingsNote>
            Labels and fields are matched by name when somebody uses the template. A name this
            team does not have is said out loud in the composer rather than dropped.
          </SettingsNote>
          <div className="flex gap-2">
            <Button type="button" size="sm" onClick={save} disabled={!draft.name.trim()}>
              Save
            </Button>
            <Button type="button" size="sm" variant="ghost" onClick={() => setEditing(undefined)}>
              Cancel
            </Button>
          </div>
        </div>
      )}
    </section>
  );
}
