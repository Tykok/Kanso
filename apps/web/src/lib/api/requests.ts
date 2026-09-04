/**
 * The Notion bases Kanso siphons requests out of.
 *
 * Its own slice rather than a section of `inbox.ts`, for the reason `RequestBaseController`
 * is its own controller: screen 24 reads a workspace once, on somebody's say-so, and stops.
 * A requests base is a standing arrangement a poller re-reads every thirty seconds, and
 * `V37` keeps the two apart in the schema on a stronger version of that same argument.
 *
 * Discovery is deliberately *not* here. The picker these three feed reuses
 * `notionImportApi.sources`, which `KAN-55` settled stays open to any member — so the two
 * halves of the screen come from two slices with two different audiences, which is the
 * split said in the import graph rather than only in a comment.
 */

import { request } from "./core";

/**
 * One registered base, shaped by `dev.kanso.api.RequestBaseResponse`.
 *
 * Three non-null `String`s on the server, so three non-optional fields here — worth saying
 * rather than leaving to look accidental, because the other spelling is a lie the wire
 * cannot catch. Jackson writes an absent value as *absent*, never as `null`: a field typed
 * `string | null` here would arrive `undefined`, every `=== null` guard would read straight
 * past it, and the screen would print whatever `undefined` formats as. That is exactly how
 * a "Last used Invalid Date" reached a screen two batches ago.
 *
 * There is no team *name* on it, and none is wanted: the server answers ids because ids are
 * the whole content of the setting — `SyncAdminController.detail` makes the same reading —
 * and a screen that has to draw a team picker already holds the list that names one.
 */
export type NotionRequestBase = {
  dataSourceId: string;
  databaseId: string;
  teamId: string;
};

export const requestBasesApi = {
  /** Every wired base. The configurator's, like the two below it. */
  list: () => request<NotionRequestBase[]>("/api/admin/notion/requests"),

  /**
   * Wires a base to one team's queue, or re-points a wired one at another team.
   *
   * Both ids travel because the base has both and they answer different questions: the data
   * source is what the siphon queries, the database is what the mirror check compares
   * against. `RequestBaseService.register` refuses a base that is either one of the mirror's
   * own, and the caller has to print that refusal — it is the one mistake a person can make
   * here that would quietly duplicate every ticket in the instance.
   */
  register: (body: { dataSourceId: string; databaseId: string; teamId: string }) =>
    request<NotionRequestBase>("/api/admin/notion/requests", {
      method: "POST",
      body: JSON.stringify(body),
    }),

  /** Stops the siphon. The tickets it already made are ordinary tickets and stay. */
  unregister: (dataSourceId: string) =>
    request<void>(`/api/admin/notion/requests/${encodeURIComponent(dataSourceId)}`, {
      method: "DELETE",
    }),
};
