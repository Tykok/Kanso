import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { randomUUID } from "node:crypto";

/**
 * Somebody else's Notion workspace, answered over HTTP.
 *
 * The import is the one part of Kanso that reads a workspace it did not create, and until
 * this file existed there was no way to walk its five screens in a browser: with no token
 * the dialog's first step prints a sentence and stops, and with a real token the pass would
 * depend on a workspace nobody else has.
 *
 * The seam is Notion's own base URL. `application.yml` already reads it from
 * `NOTION_BASE_URL`, so pointing the API container at this server puts the *real*
 * `HttpNotionClient` under test — its search-filter fallback, its cursors, its property
 * parsing — rather than a fake bound inside the application. That distinction is the whole
 * reason this lives in `e2e/` and not in `apps/api/src/main`: `FakeNotionWorkspace` is a
 * test double for the Kotlin suite and has no business shipping in a jar, and a
 * `NotionClient` bean chosen by a profile would be one more branch of production wiring
 * that only tests ever take.
 *
 * It answers the five reads the import makes and **nothing else**. Every mutating call —
 * `POST /pages`, `PATCH /pages/…`, `POST /databases`, `PATCH /data_sources/…` — is recorded
 * in [NotionWorkspaceStub.mutations] and refused, which is how `import.spec.ts` can assert
 * the promise screen 24 makes in as many words: nothing is changed in Notion at any step.
 *
 * ## The workspace it describes
 *
 * Three bases that already know about each other, which is the shape the spec is for:
 *
 * - `Équipes` — one page, `Plateforme`, with a `Parent team` relation onto itself;
 * - `Projets` — one page, `Refonte`, whose `Team` relation names `Plateforme`, plus an
 *   `Avancement` select nothing claims;
 * - `Tâches` — two pages, whose `Project` relation names `Refonte`, whose status column is
 *   called `Etat` and holds `À faire`, `En cours` and `Terminé`, whose `Assignees` column
 *   names one workspace member, and whose `Notes` column nothing claims either.
 *
 * `Etat` is the point of the branch: a select whose name matches nothing Kanso looks for, so
 * the server suggests no mapping and the reader has to say what it is — and two of whose
 * three options are words Kanso's vocabulary does not hold. The relation and people columns
 * are named in English on purpose: a workspace where *nothing* matched would exercise the
 * mapping screen and never the pre-fill or the suggestion the second step is built around,
 * and real workspaces mix the two. `Avancement` and `Notes` are unclaimed on purpose too —
 * they are what the preview names as preserved rather than dropped.
 *
 * Every id and every title carries a per-run suffix. A page is imported once and forever —
 * `notion_import_origin` has the page id as its primary key — so a stub that answered the
 * same ids twice would make the second run of this suite a test of the skipping path
 * wearing the first run's assertions.
 */

/** One base, with the two ids Notion gives a database and the one the import queries. */
export type StubBase = {
  databaseId: string;
  dataSourceId: string;
  name: string;
};

export type StubPerson = { id: string; name: string; email: string };

/**
 * What the spec needs to name: the bases, the titles of the pages inside them, and the
 * person.
 *
 * The titles are given rather than derived from the base names. They are what the
 * assertions look for on the list screen, and a spec that rebuilt them by splitting a
 * base's name would be encoding this file's naming scheme in two places.
 */
export type StubWorkspace = {
  teams: StubBase;
  projects: StubBase;
  tickets: StubBase;
  /** The one team page: the parent of nothing, and the destination of everything. */
  teamTitle: string;
  /** The one project page, linked to the team by its `Team` column. */
  projectTitle: string;
  /** `Etat = En cours`, which the reader maps onto `In progress`. */
  mappedTicketTitle: string;
  /** `Etat = Terminé`, a word Kanso's vocabulary does not hold, so it takes the default. */
  defaultedTicketTitle: string;
  person: StubPerson;
};

export type NotionWorkspaceStub = {
  /** What the workspace is called, so assertions can name a page or a base. */
  workspace: StubWorkspace;
  /** `METHOD /path` for every write this server refused. Asserted empty. */
  mutations: string[];
  close: () => Promise<void>;
};

/**
 * Where the API container reaches this server.
 *
 * A fixed port, because `NOTION_BASE_URL` is read when the container boots and this server
 * starts when the spec does — there is no moment at which one could tell the other a port
 * it chose. `host.docker.internal` is the machine running the suite as seen from inside the
 * container; `docker-compose.yml` maps it explicitly so this holds on Linux too.
 */
const STUB_PORT = Number(process.env.KANSO_NOTION_STUB_PORT ?? 8099);

/** The value `NOTION_BASE_URL` has to carry for the pass to be possible at all. */
export const STUB_BASE_URL = `http://host.docker.internal:${STUB_PORT}/v1`;

// --- Notion's own json shapes ------------------------------------------------
// Written out rather than abbreviated: `HttpNotionClient`, `ImportSchema` and
// `MappedPageReader` each read a different corner of these objects, and a helper that
// flattened them would be testing the helper.

const titleValue = (text: string) => ({
  id: "title",
  type: "title",
  title: [{ type: "text", plain_text: text, text: { content: text } }],
});

const selectValue = (name: string) => ({
  id: "etat",
  type: "select",
  select: { id: `opt-${name}`, name, color: "blue" },
});

const richTextValue = (text: string) => ({
  id: "notes",
  type: "rich_text",
  rich_text: [{ type: "text", plain_text: text, text: { content: text } }],
});

const relationValue = (...pageIds: string[]) => ({
  id: "rel",
  type: "relation",
  relation: pageIds.map((id) => ({ id })),
  has_more: false,
});

const peopleValue = (...people: StubPerson[]) => ({
  id: "ppl",
  type: "people",
  people: people.map((person) => ({
    object: "user",
    id: person.id,
    name: person.name,
    type: "person",
    person: { email: person.email },
  })),
});

const titleColumn = (name: string) => [name, { id: "title", name, type: "title", title: {} }] as const;

const selectColumn = (name: string, options: string[]) =>
  [
    name,
    {
      id: `sel-${name}`,
      name,
      type: "select",
      select: { options: options.map((option) => ({ id: `opt-${option}`, name: option, color: "blue" })) },
    },
  ] as const;

const richTextColumn = (name: string) =>
  [name, { id: `txt-${name}`, name, type: "rich_text", rich_text: {} }] as const;

/**
 * A relation column, `single_property` — declared on this side only.
 *
 * `data_source_id` is where `ImportSchema` reads a relation's other end from, and it is the
 * only place that answer exists: a page carries the ids it points at and never says which
 * base they live in.
 */
const relationColumn = (name: string, dataSourceId: string) =>
  [
    name,
    {
      id: `rel-${name}`,
      name,
      type: "relation",
      relation: { data_source_id: dataSourceId, type: "single_property", single_property: {} },
    },
  ] as const;

const peopleColumn = (name: string) =>
  [name, { id: `ppl-${name}`, name, type: "people", people: {} }] as const;

type StubPage = { id: string; properties: Record<string, unknown> };

const page = (id: string, properties: Record<string, unknown>) => ({
  object: "page",
  id,
  created_time: "2026-08-01T09:00:00.000Z",
  last_edited_time: "2026-08-01T09:00:00.000Z",
  last_edited_by: { object: "user", id: "a-human" },
  archived: false,
  in_trash: false,
  url: `https://notion.so/${id}`,
  properties,
});

/** A base as this server holds it: its ids, its schema, and its rows. */
type HeldBase = StubBase & {
  columns: Record<string, unknown>;
  pages: StubPage[];
};

/**
 * Builds the three related bases, with fresh ids and a fresh suffix on every call.
 *
 * The order matters twice: it is the order `POST /search` answers in, which is the order
 * step 2 draws its rows in, and `Projets` has to exist before `Tâches` can name its data
 * source in a relation column.
 */
function buildWorkspace(): { held: HeldBase[]; workspace: StubWorkspace } {
  const suffix = randomUUID().slice(0, 6);
  const id = (prefix: string) => `${prefix}-${randomUUID()}`;

  const teams: StubBase = {
    databaseId: id("db-teams"),
    dataSourceId: id("ds-teams"),
    name: `Équipes ${suffix}`,
  };
  const projects: StubBase = {
    databaseId: id("db-projects"),
    dataSourceId: id("ds-projects"),
    name: `Projets ${suffix}`,
  };
  const tickets: StubBase = {
    databaseId: id("db-tickets"),
    dataSourceId: id("ds-tickets"),
    name: `Tâches ${suffix}`,
  };

  const person: StubPerson = {
    id: id("user"),
    name: `Ada Lovelace ${suffix}`,
    email: `ada.${suffix}@example.test`,
  };

  const teamPage = id("page-team");
  const projectPage = id("page-project");
  const mappedTicket = id("page-ticket-progress");
  const defaultedTicket = id("page-ticket-done");

  const teamTitle = `Plateforme ${suffix}`;
  const projectTitle = `Refonte ${suffix}`;
  const mappedTicketTitle = `Corriger le login ${suffix}`;
  const defaultedTicketTitle = `Écrire la doc ${suffix}`;

  const held: HeldBase[] = [
    {
      ...teams,
      columns: Object.fromEntries([titleColumn("Nom"), relationColumn("Parent team", teams.dataSourceId)]),
      // No parent: the one team comes over at the top level, and the relation column is
      // here so that step 3 has nothing to ask about it — a resolvable link is silent.
      pages: [{ id: teamPage, properties: { Nom: titleValue(teamTitle) } }],
    },
    {
      ...projects,
      columns: Object.fromEntries([
        titleColumn("Nom"),
        selectColumn("Avancement", ["En cours", "Terminé"]),
        relationColumn("Team", teams.dataSourceId),
      ]),
      pages: [
        {
          id: projectPage,
          properties: {
            Nom: titleValue(projectTitle),
            Avancement: selectValue("En cours"),
            Team: relationValue(teamPage),
          },
        },
      ],
    },
    {
      ...tickets,
      columns: Object.fromEntries([
        titleColumn("Nom"),
        selectColumn("Etat", ["À faire", "En cours", "Terminé"]),
        richTextColumn("Notes"),
        relationColumn("Project", projects.dataSourceId),
        peopleColumn("Assignees"),
      ]),
      pages: [
        {
          id: mappedTicket,
          properties: {
            Nom: titleValue(mappedTicketTitle),
            Etat: selectValue("En cours"),
            Notes: richTextValue("Vu avec le support"),
            Project: relationValue(projectPage),
            Assignees: peopleValue(person),
          },
        },
        {
          id: defaultedTicket,
          properties: {
            Nom: titleValue(defaultedTicketTitle),
            Etat: selectValue("Terminé"),
            Project: relationValue(projectPage),
            Assignees: peopleValue(person),
          },
        },
      ],
    },
  ];

  return {
    held,
    workspace: {
      teams,
      projects,
      tickets,
      teamTitle,
      projectTitle,
      mappedTicketTitle,
      defaultedTicketTitle,
      person,
    },
  };
}

/**
 * Starts the workspace and returns it, listening on [STUB_PORT].
 *
 * Cursors are honoured even though three bases and four pages fit in one page of either
 * walk: `NotionDiscovery` counts a base by walking it, and a server that ignored
 * `start_cursor` would answer the first page forever and hang the count rather than fail it.
 */
export async function startNotionWorkspace(): Promise<NotionWorkspaceStub> {
  const { held, workspace } = buildWorkspace();
  const mutations: string[] = [];

  const server = createServer((request, response) => {
    collect(request, (body) => {
      const url = new URL(request.url ?? "/", "http://stub");
      const path = url.pathname.replace(/^\/v1/, "");
      const method = request.method ?? "GET";

      // Everything that writes, in one branch. Recorded and refused: this server exists
      // to prove the import only ever reads, and a stub that quietly answered a page
      // creation would hide exactly the defect the assertion is looking for.
      if (method === "PATCH" || (method === "POST" && !READS.has(path) && !path.endsWith("/query"))) {
        mutations.push(`${method} ${path}`);
        return json(response, 400, { code: "validation_error", message: "This workspace is read-only." });
      }

      if (method === "GET" && path === "/users/me") {
        return json(response, 200, {
          object: "user",
          id: "stub-bot",
          type: "bot",
          bot: { owner: { type: "user", user: { object: "user", id: "stub-bot" } } },
        });
      }

      if (method === "GET" && path === "/users") {
        return json(response, 200, {
          object: "list",
          results: [
            {
              object: "user",
              id: workspace.person.id,
              name: workspace.person.name,
              type: "person",
              person: { email: workspace.person.email },
            },
            // A bot is a workspace member too, and `listUsers` is documented to drop it.
            { object: "user", id: "stub-bot", name: "Kanso stub", type: "bot", bot: {} },
          ],
          next_cursor: null,
          has_more: false,
        });
      }

      if (method === "POST" && path === "/search") {
        const filter = String((body as { filter?: { value?: string } })?.filter?.value ?? "");
        // The 2025-09-03 spelling only. `HttpNotionClient` tries `data_source` first and
        // falls back to `database` on a 400, and refusing the older word is what keeps
        // that fallback honest rather than untested in both directions.
        if (filter !== "data_source") {
          return json(response, 400, {
            code: "validation_error",
            message: `Unsupported object filter '${filter}'.`,
          });
        }
        const { slice, nextCursor } = paginate(held, body);
        return json(response, 200, {
          object: "list",
          results: slice.map((base) => ({
            object: "data_source",
            id: base.dataSourceId,
            name: base.name,
            title: [{ type: "text", plain_text: base.name }],
            parent: { type: "database_id", database_id: base.databaseId },
          })),
          next_cursor: nextCursor,
          has_more: nextCursor !== null,
        });
      }

      const dataSource = path.match(/^\/data_sources\/([^/]+)(\/query)?$/);
      if (dataSource) {
        const base = held.find((candidate) => candidate.dataSourceId === dataSource[1]);
        // A 404 is `null` to the client, and both call sites have a sentence for it.
        if (!base) return json(response, 404, { code: "object_not_found", message: "No such data source." });

        if (dataSource[2]) {
          const { slice, nextCursor } = paginate(base.pages, body);
          return json(response, 200, {
            object: "list",
            results: slice.map((row) => page(row.id, row.properties)),
            next_cursor: nextCursor,
            has_more: nextCursor !== null,
          });
        }

        return json(response, 200, {
          object: "data_source",
          id: base.dataSourceId,
          title: [{ type: "text", plain_text: base.name }],
          properties: base.columns,
        });
      }

      return json(response, 404, { code: "object_not_found", message: `Nothing at ${path}.` });
    });
  });

  await new Promise<void>((resolve, reject) => {
    server.once("error", reject);
    server.listen(STUB_PORT, "0.0.0.0", resolve);
  });

  return {
    workspace,
    mutations,
    close: () => new Promise<void>((resolve) => server.close(() => resolve())),
  };
}

/** The two POSTs that read. Everything else posted here is a write. */
const READS = new Set(["/search"]);

/**
 * One page of a list, with the cursor to ask for the next.
 *
 * The cursor is the offset as a string, which is what `FakeNotionWorkspace` does for the
 * same reason: it has to be opaque to the caller and readable to whoever is debugging one.
 */
function paginate<T>(all: T[], body: unknown): { slice: T[]; nextCursor: string | null } {
  const asked = body as { page_size?: number; start_cursor?: string } | undefined;
  const size = Math.max(1, Math.min(100, asked?.page_size ?? 100));
  const from = Number(asked?.start_cursor ?? 0);
  const slice = all.slice(from, from + size);
  const next = from + slice.length;
  return { slice, nextCursor: next < all.length ? String(next) : null };
}

function collect(request: IncomingMessage, then: (body: unknown) => void): void {
  const chunks: Buffer[] = [];
  request.on("data", (chunk: Buffer) => chunks.push(chunk));
  request.on("end", () => {
    const raw = Buffer.concat(chunks).toString("utf8");
    let parsed: unknown = undefined;
    if (raw.length > 0) {
      try {
        parsed = JSON.parse(raw);
      } catch {
        parsed = undefined;
      }
    }
    then(parsed);
  });
}

function json(response: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": Buffer.byteLength(payload),
  });
  response.end(payload);
}
