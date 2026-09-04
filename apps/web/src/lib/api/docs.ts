/**
 * Slice B's client: the documents written in Kanso, screens 07 and 22.
 *
 * `/api/docs` on its own is still the `notion_docs` index — one row per Notion page a
 * relation has to be able to point at. Everything here lives under `/folders`,
 * `/pages`, `/blocks` and `/templates`, and the two are not the same object: a
 * `DocPage` is a page written here.
 */

import { request, type Ticket } from "./core";

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

/**
 * Who is holding a block, and when it lets go — `KAN-25`.
 *
 * `freesAt` is an instant, not a number of seconds: a tab that was backgrounded for a
 * minute has to draw a countdown that is still right, and a duration computed on the
 * server goes stale in flight.
 */
export type DocBlockLock = {
  userId: string;
  displayName: string;
  freesAt: string;
  /** When the claim began. A renewal moves `freesAt` and leaves this alone. */
  takenAt: string;
};

/** Somebody with the page open. No timestamp: there is no row and nothing to compare. */
export type DocViewer = { userId: string; displayName: string };

export type DocBlock = {
  id: string;
  pageId: string;
  position: number;
  kind: DocBlockKind;
  content: DocBlockContent;
  /** From `doc_block_tickets`. A ticket link block carries exactly one. */
  ticketIds: string[];
  /**
   * The live lock, or **absent**.
   *
   * `?:` and not `| null`, and the difference has shipped a bug here before: Jackson omits
   * nulls, so an unlocked block has no `lockedBy` key at all. A type saying `| null` would
   * type-check against a value that never arrives and put `Invalid Date` on screen — which
   * is exactly what "Last used" did.
   *
   * Only ever a live one. The server filters `expires_at > clock_timestamp()`, so there is
   * no `held` flag to check and no lapsed holder to reason about.
   */
  lockedBy?: DocBlockLock;
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

  // --- the lock, and who is watching ---------------------------------------

  /**
   * Takes the block, or renews a claim this tab already has — one call for both, because
   * the server decides them in one statement.
   *
   * Throws `ApiError` with `status === 409` when somebody else has it; the problem document
   * carries `holder` and `freesAt`, which `lockRefusal` in `lib/doc-locks.ts` reads.
   */
  takeLock: (blockId: string) =>
    request<DocBlockLock>(`/api/docs/blocks/${blockId}/lock`, { method: "PUT" }),

  releaseLock: (blockId: string) =>
    request<void>(`/api/docs/blocks/${blockId}/lock`, { method: "DELETE" }),

  /**
   * Who has the page open. Read once on mount; every change after it arrives on the
   * page's viewers topic — see `DocumentController.viewers` for why the read exists at all
   * rather than the first broadcast being trusted.
   */
  viewers: (pageId: string) => request<DocViewer[]>(`/api/docs/pages/${pageId}/viewers`),

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
