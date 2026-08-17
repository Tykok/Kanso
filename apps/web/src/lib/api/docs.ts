/**
 * Slice B's client: the documents written in Kanso, screens 07 and 22.
 *
 * `/api/docs` on its own is still the `notion_docs` index — one row per Notion page a
 * relation has to be able to point at. Everything here lives under `/folders`,
 * `/pages`, `/blocks` and `/templates`, and the two are not the same object: a
 * `DocPage` is a page written here.
 */

import { API_URL, ApiError, getDevUser, type Ticket } from "./core";

export const DOC_BLOCK_KINDS = [
  "paragraph",
  "heading",
  "numbered_list",
  "checkbox",
  "callout",
  "ticket_link",
  "table",
] as const;

export type DocBlockKind = (typeof DOC_BLOCK_KINDS)[number];

/**
 * A block's payload, shaped by its kind: `{ text }` for a paragraph, `{ text, checked }`
 * for a checkbox, `{ columns, rows }` for a table. Deliberately not a union of seven
 * interfaces — the server refuses a shape that does not match its kind, and restating
 * that here would be a second vocabulary to keep in step with the `CHECK` in `V9`.
 */
export type DocBlockContent = Record<string, unknown>;

export type DocBlock = {
  id: string;
  pageId: string;
  position: number;
  kind: DocBlockKind;
  content: DocBlockContent;
  /** From `doc_block_tickets`. A ticket link block carries exactly one. */
  ticketIds: string[];
};

export type DocFolder = {
  id: string;
  teamId: string;
  parentId?: string;
  name: string;
  position: number;
};

export type DocPage = {
  id: string;
  teamId: string;
  folderId?: string;
  title: string;
  authorId?: string;
  /** Who touched it last — what the footer prints beside how long ago. */
  editedById?: string;
  /** Absent for a page written here; set when it mirrors one written in Notion. */
  notionPageId?: string;
  notionUrl?: string;
  createdAt: string;
  updatedAt: string;
};

/**
 * The whole of screen 07 in one response. `tickets` is the "Lié à" rail *and* what every
 * ticket link block renders its status pill from — resolved server-side and live, which
 * is the only reason a link is worth more than typing `KAN-142` into a paragraph.
 */
export type DocPageDetail = {
  page: DocPage;
  blocks: DocBlock[];
  tickets: Ticket[];
};

export type DocTemplate = {
  id: string;
  slug: string;
  name: string;
  summary: string;
  blocks: { kind: DocBlockKind; content: DocBlockContent }[];
};

export type LinkedTicket = { ticket: Ticket; block: DocBlock };

/**
 * The same fetch wrapper `core.ts` has, because `core.ts` keeps its own private.
 *
 * Not worth an exported helper on a file this branch may not edit: the shape of a Kanso
 * request is four lines and one of them is the dev-user header. Worth hoisting the day
 * the second slice needs it — noted for the integration pass rather than done here.
 */
async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const devUser = getDevUser();
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    credentials: "include",
    headers: {
      "Content-Type": "application/json",
      ...(devUser ? { "X-Kanso-User": devUser } : {}),
      ...init.headers,
    },
  });

  if (response.status === 204) return undefined as T;
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new ApiError(response.status, problem?.detail ?? response.statusText, problem);
  }
  return response.json() as Promise<T>;
}

const body = (value: unknown) => JSON.stringify(value);

export const docsApi = {
  // --- the tree ------------------------------------------------------------

  folders: (teamId?: string) =>
    request<DocFolder[]>(`/api/docs/folders${teamId ? `?teamId=${teamId}` : ""}`),

  createFolder: (input: { teamId: string; parentId?: string; name: string }) =>
    request<DocFolder>("/api/docs/folders", { method: "POST", body: body(input) }),

  renameFolder: (id: string, name: string) =>
    request<DocFolder>(`/api/docs/folders/${id}`, { method: "PATCH", body: body({ name }) }),

  deleteFolder: (id: string) => request<void>(`/api/docs/folders/${id}`, { method: "DELETE" }),

  // --- pages ---------------------------------------------------------------

  /** Newest edit first — screen 22's "recently changed" is this list, unfiltered. */
  pages: (opts: { teamId?: string; folderId?: string; limit?: number } = {}) => {
    const search = new URLSearchParams();
    if (opts.teamId) search.set("teamId", opts.teamId);
    if (opts.folderId) search.set("folderId", opts.folderId);
    if (opts.limit) search.set("limit", String(opts.limit));
    const query = search.toString();
    return request<DocPage[]>(`/api/docs/pages${query ? `?${query}` : ""}`);
  },

  page: (id: string) => request<DocPageDetail>(`/api/docs/pages/${id}`),

  createPage: (input: {
    teamId: string;
    folderId?: string;
    title: string;
    templateSlug?: string;
  }) => request<DocPageDetail>("/api/docs/pages", { method: "POST", body: body(input) }),

  /** An absent field is unchanged; `unset` clears one. Same rule as a ticket patch. */
  patchPage: (id: string, input: { title?: string; folderId?: string; unset?: string[] }) =>
    request<DocPageDetail>(`/api/docs/pages/${id}`, { method: "PATCH", body: body(input) }),

  deletePage: (id: string) => request<void>(`/api/docs/pages/${id}`, { method: "DELETE" }),

  // --- blocks --------------------------------------------------------------

  addBlock: (
    pageId: string,
    input: { kind: DocBlockKind; content: DocBlockContent; afterBlockId?: string },
  ) => request<DocBlock>(`/api/docs/pages/${pageId}/blocks`, { method: "POST", body: body(input) }),

  patchBlock: (id: string, content: DocBlockContent) =>
    request<DocBlock>(`/api/docs/blocks/${id}`, { method: "PATCH", body: body({ content }) }),

  /** Answers with the whole page: a move renumbers every block in it. */
  moveBlock: (id: string, toIndex: number) =>
    request<DocBlock[]>(`/api/docs/blocks/${id}/position`, {
      method: "PUT",
      body: body({ toIndex }),
    }),

  deleteBlock: (id: string) => request<void>(`/api/docs/blocks/${id}`, { method: "DELETE" }),

  // --- the gestures inside a document --------------------------------------

  /** `#` — a ticket that already exists. */
  linkTicket: (pageId: string, ticketId: string, afterBlockId?: string) =>
    request<DocBlock>(`/api/docs/pages/${pageId}/tickets`, {
      method: "POST",
      body: body({ ticketId, afterBlockId }),
    }),

  /**
   * `c` — a new ticket, already attached. One request on purpose: the whole point of
   * the gesture is that the link cannot be forgotten, and two requests can forget it.
   */
  createLinkedTicket: (pageId: string, title: string) =>
    request<LinkedTicket>(`/api/docs/pages/${pageId}/tickets/new`, {
      method: "POST",
      body: body({ title }),
    }),

  templates: () => request<DocTemplate[]>("/api/docs/templates"),
};
