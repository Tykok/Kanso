"use client";

import { dayValue, type Project } from "@/lib/api";
import { STATUS_LABELS } from "@/lib/status";
import { SyncBadge, TicketIdentifier } from "../pills";
import { Kbd } from "../ui/kbd";
import type { SearchRow } from "./results";

/**
 * The drawing's right-hand column: 300px, the hover fill, and enough of the highlighted
 * row to decide without opening it. "Un aperçu à droite pour trancher sans ouvrir" is the
 * caption's own justification, and it is the reason the pane shows the description excerpt
 * rather than only the metadata a list row already carries.
 *
 * Nothing here is a control. A preview that could be acted on would be a second way to
 * edit a ticket reachable from a search field, which is one more than this application
 * has anywhere else.
 */
export function SearchPreview({ row, projects }: { row?: SearchRow; projects: Project[] }) {
  if (!row) {
    return (
      <aside className="hidden w-[300px] shrink-0 flex-col gap-3 bg-accent p-[18px] text-11 text-faint md:flex">
        Nothing highlighted.
      </aside>
    );
  }

  return (
    <aside
      data-testid="search-preview"
      className="hidden w-[300px] shrink-0 flex-col gap-3 overflow-hidden bg-accent p-[18px] md:flex"
    >
      {row.kind === "ticket" && (
        <>
          <div className="flex items-center gap-2">
            <TicketIdentifier ticket={row.ticket} className="font-mono text-11 text-faint" />
            <span className="flex-1" />
            <SyncBadge mirror={row.ticket.mirror} />
          </div>
          <span className="text-15 font-medium tracking-tight text-pretty">{row.ticket.title}</span>
          <dl className="m-0 grid grid-cols-[64px_1fr] gap-2 text-11 text-muted-foreground">
            <dt className="text-faint">Status</dt>
            <dd className="m-0">{STATUS_LABELS[row.ticket.status]}</dd>
            <dt className="text-faint">Project</dt>
            <dd className="m-0">
              {projects.find((project) => project.id === row.ticket.projectId)?.name ?? "—"}
            </dd>
            <dt className="text-faint">Due</dt>
            <dd className="m-0">{row.ticket.due ? dayValue(row.ticket.due) : "—"}</dd>
          </dl>
          {row.ticket.description && (
            // Clamped rather than truncated at a character count: the pane is 300px wide
            // and where a sentence ends is a layout question, not a string one.
            <p className="m-0 line-clamp-6 text-12 leading-relaxed text-muted-foreground text-pretty">
              {row.ticket.description}
            </p>
          )}
        </>
      )}

      {row.kind === "doc" && (
        <>
          <span className="text-11 text-faint">Notion page</span>
          <span className="text-15 font-medium tracking-tight text-pretty">
            {row.doc.title ?? row.doc.notionPageId}
          </span>
          <span className="text-12 break-all text-muted-foreground">{row.doc.url}</span>
        </>
      )}

      {row.kind === "command" && (
        <>
          <span className="text-11 text-faint">Command</span>
          <span className="text-15 font-medium tracking-tight text-pretty">{row.command.label}</span>
          {row.command.hint && (
            <span className="text-12 text-muted-foreground">
              <Kbd>{row.command.hint}</Kbd> from anywhere
            </span>
          )}
        </>
      )}

      <span className="mt-auto text-11 text-faint">
        Preview — <Kbd>↵</Kbd> to open
      </span>
    </aside>
  );
}
