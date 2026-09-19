import { request } from "./core";
import type { EffortPoints, TicketPriority } from "./core";

/**
 * A shape a ticket can start in. `teamId` null means Kanso ships it and every team may use
 * it; set means it is that team's own. The picker draws its two groups off this field and
 * nothing else.
 */
export type TicketTemplate = {
  id: string;
  teamId: string | null;
  name: string;
  summary: string | null;
  body: TemplateBody;
  categories: string[];
};

/**
 * Every key optional, and an absent one means *do not pre-fill this* — which is not the same
 * as an empty string. `title: ""` starts the field blank; an absent `title` leaves the
 * composer's own seeding alone. `TemplateBodyCodec` keeps the two apart on the server and
 * this type must not collapse them, so no key here gets a default.
 */
export type TemplateBody = {
  title?: string;
  description?: string;
  priority?: TicketPriority;
  estimate?: EffortPoints;
  /** Label *names*, lower-cased by the server. Resolved to ids per team, never stored as ids. */
  labels?: string[];
  /**
   * Custom field name to a JSON scalar, in the type the field will read it as. Not all
   * strings: `FieldValueCodec.validate` refuses `"true"` for a boolean field on purpose, so
   * a stringified value would make every checkbox in a template unresolvable.
   */
  fields?: Record<string, string | number | boolean>;
};

/** What this template means in one particular team, and what it could not mean there. */
export type ResolvedTemplate = {
  title: string | null;
  description: string | null;
  priority: TicketPriority | null;
  estimate: EffortPoints | null;
  labelIds: string[];
  /** Field *id* to the value the server normalised — never the raw one from the body. */
  fieldValues: Record<string, string | number | boolean>;
  /**
   * Names that matched nothing in this team. Printed by the composer rather than swallowed:
   * a template that half-works must not be indistinguishable from one that works.
   */
  unresolved: { labels: string[]; fields: string[] };
};

export type TemplateBodyRequest = {
  name: string;
  summary?: string;
  body: TemplateBody;
  categories: string[];
};

export const templatesApi = {
  /**
   * With a team: the instance catalogue plus that team's own. Without: the instance
   * catalogue alone, which is what the settings screen asks for and what a draft gets.
   */
  list: (teamId?: string) =>
    request<TicketTemplate[]>(
      teamId ? `/api/tickets/templates?teamId=${teamId}` : "/api/tickets/templates",
    ),

  resolve: (id: string, teamId: string | undefined) =>
    request<ResolvedTemplate>(
      teamId
        ? `/api/tickets/templates/${id}/resolved?teamId=${teamId}`
        : `/api/tickets/templates/${id}/resolved`,
    ),

  create: (body: TemplateBodyRequest & { teamId?: string }) =>
    request<TicketTemplate>("/api/tickets/templates", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  update: (id: string, body: TemplateBodyRequest) =>
    request<TicketTemplate>(`/api/tickets/templates/${id}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  remove: (id: string) => request<void>(`/api/tickets/templates/${id}`, { method: "DELETE" }),
};
