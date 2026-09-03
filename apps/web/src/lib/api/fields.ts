import { request, type CustomFieldValue } from "./core";

/**
 * What a custom field may hold — `CustomFieldType` in `domain/CustomField.kt`, closed there
 * by an enum and in the database by `custom_fields_type_chk`.
 *
 * Four values, and the migration that created them (`V35`) carries the argument for why
 * `date`, `multi_select` and `user` are not among them. A fifth arriving here without a
 * renderer below would draw a field nobody can fill, so the two lists are the same length on
 * purpose: `CONTROLS` in `ticket-fields.tsx` is exhaustive over this union and TypeScript
 * refuses a missing branch.
 */
export type CustomFieldType = "text" | "number" | "boolean" | "select";

/** Team-scoped, like a label: two teams may both define `Severity`, and neither wins. */
export type CustomField = {
  id: string;
  teamId: string;
  name: string;
  type: CustomFieldType;
  /**
   * A value that exists may not be taken away. Deliberately *not* "a ticket must have one" —
   * `V35` records why enforcing it on creation would break every existing integration the
   * day somebody ticked the box.
   */
  required: boolean;
  /** Non-empty exactly when [type] is `select`. */
  options: string[];
  /**
   * How many tickets already hold a value. Derived on read by the server, stored nowhere, and
   * here for one gesture: deleting a definition cascades to its values, so the confirmation
   * has to be able to say what it is about to destroy.
   */
  valueCount: number;
};

export type CustomFieldBody = {
  name: string;
  type: CustomFieldType;
  required: boolean;
  options: string[];
};

export const fieldsApi = {
  teamFields: (teamId: string) => request<CustomField[]>(`/api/teams/${teamId}/fields`),

  defineField: (teamId: string, body: CustomFieldBody) =>
    request<CustomField>(`/api/teams/${teamId}/fields`, {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /**
   * The type travels back but cannot change — the server refuses a different one rather than
   * ignoring it, because a screen that accepts an edit and does not make it is worse than one
   * that says no. See `CustomFieldRepository.update`.
   */
  redefineField: (fieldId: string, body: CustomFieldBody) =>
    request<CustomField>(`/api/fields/${fieldId}`, {
      method: "PUT",
      body: JSON.stringify(body),
    }),

  /** Takes the values with it. `valueCount` is what the confirmation prints first. */
  deleteField: (fieldId: string) =>
    request<void>(`/api/fields/${fieldId}`, { method: "DELETE" }),

  ticketFields: (ticketId: string) =>
    request<Record<string, CustomFieldValue>>(`/api/tickets/${ticketId}/fields`),

  /**
   * Only the fields named are touched, and a `null` clears one — *unlike* `/labels` beside
   * it, which replaces the whole set.
   *
   * The difference is the shape of the two screens. A pill row knows every label a ticket
   * should wear; a field panel is a form where somebody edited one input, and a whole-set
   * replace would turn "I changed the severity" into "clear every other field" whenever this
   * client was built against a definition list older than the team's.
   */
  setTicketFields: (ticketId: string, values: Record<string, CustomFieldValue | null>) =>
    request<Record<string, CustomFieldValue>>(`/api/tickets/${ticketId}/fields`, {
      method: "PUT",
      body: JSON.stringify(values),
    }),
};
