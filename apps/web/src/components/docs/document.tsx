"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import type { DocBlockContent, DocBlockKind, DocPageDetail, Ticket, User } from "@/lib/api";
import { cn } from "@/lib/utils";
import { useDocBlockWrites } from "@/lib/queries";
import { useDocsUi } from "@/store/docs";
import { useUi } from "@/store/ui";
import { Kbd } from "../ui/kbd";
import { BlockBody, queriedStatuses } from "./blocks";
import { InsertMenu } from "./insert-menu";
import { LinkedTicketPrompt } from "./linked-ticket";
import { MentionPicker } from "./mention-picker";
import { editedLabel, tableOfContents } from "./outline";

/**
 * Screen 07 — a document, editable here.
 *
 * The four keys are handled on this component's own `onKeyDown` rather than through
 * `lib/actions`: `/`, `#` and `@` are characters somebody is typing, and `c` means
 * something narrower here than the composer it collides with. `lib/actions/docs.ts`
 * records that decision at the place a reader would look for the missing entries.
 */
export function DocumentView({
  detail,
  people,
  teamTickets,
  editable,
  now,
}: {
  detail: DocPageDetail;
  people: User[];
  /** Every ticket in the page's team: what `#` offers and what a query table reads. */
  teamTickets: Ticket[];
  /** The server's own answer to "may this reader write here", not a re-derivation. */
  editable: boolean;
  now?: Date;
}) {
  const { page, blocks, tickets } = detail;
  const writes = useDocBlockWrites(page.id);
  const overlay = useUi((state) => state.overlay);
  const open = useUi((state) => state.open);
  const closeOverlay = useUi((state) => state.close);
  const { anchorBlockId, mention, setAnchor, openMention, closeMention } = useDocsUi();
  const [draftTicket, setDraftTicket] = useState<string | undefined>();

  const names = Object.fromEntries(people.map((person) => [person.id, person.displayName]));
  const toc = tableOfContents(blocks);
  const needsQuery = blocks.some((block) => queriedStatuses(block.content) !== undefined);

  /**
   * `/`, `#`, `@` and `c`, read where the caret is.
   *
   * Each fires only on an empty block or with a modifier-free key at the very start of
   * one, which is the compromise a document has to make: `/` inside a sentence is a
   * slash, and a menu that opened on it would make the character untypeable.
   */
  const onKeyDown = (event: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (!editable) return;
    const target = event.currentTarget;
    const atStart = target.selectionStart === 0 && target.selectionEnd === 0;
    if (!atStart) return;

    if (event.key === "/") {
      event.preventDefault();
      open("blockInsert");
    }
    if (event.key === "#") {
      event.preventDefault();
      openMention("ticket");
    }
    if (event.key === "@") {
      event.preventDefault();
      openMention("person");
    }
    if (event.key === "c" && target.value === "") {
      event.preventDefault();
      setDraftTicket("");
    }
  };

  /**
   * The overlay lives in `store/ui.ts`, which outlives this route. Leaving `blockInsert`
   * set on the way out would put an insert menu over whatever screen came next.
   */
  useEffect(() => () => closeOverlay(), [closeOverlay]);

  const insert = (kind: DocBlockKind, content: DocBlockContent) => {
    writes.add.mutate({ kind, content, afterBlockId: anchorBlockId });
    closeOverlay();
  };

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <header className="flex items-center gap-2.5 bg-card px-5 py-3 text-12 text-faint">
        <Link href="/docs" className="hover:text-foreground">
          Documents
        </Link>
        <span aria-hidden>/</span>
        <span className="text-muted-foreground">{page.title}</span>
        <span className="flex-1" />

        {page.notionPageId && (
          <span className="inline-flex h-[22px] items-center rounded-sm bg-accent-soft px-[7px] text-11 tracking-[0.06em] text-accent-ink uppercase">
            Notion
          </span>
        )}
        {page.notionUrl && (
          <a
            href={page.notionUrl}
            target="_blank"
            rel="noreferrer"
            className="text-muted-foreground hover:text-foreground"
          >
            Open in Notion ↗
          </a>
        )}
      </header>

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-8 overflow-y-auto pt-8 lg:grid-cols-[1fr_200px] lg:pr-10">
        <div className="flex min-w-0 justify-center">
          <article className="flex w-[720px] max-w-full flex-col gap-[18px] px-5 lg:px-0">
            <h1 className="text-30 leading-[1.15] font-medium tracking-[-0.02em]">{page.title}</h1>

            {blocks.map((block, index) => (
              <BlockRow
                key={block.id}
                id={block.id}
                editable={editable}
                focused={block.id === anchorBlockId}
              >
                <BlockBody
                  block={block}
                  tickets={tickets}
                  teamTickets={needsQuery ? teamTickets : []}
                  onCommit={(content) => writes.patch.mutate({ id: block.id, content })}
                  onFocus={() => setAnchor(block.id)}
                  onKeyDown={onKeyDown}
                />

                {editable && (
                  <div className="flex shrink-0 gap-1 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100">
                    <HandleButton
                      label="Move up"
                      disabled={index === 0}
                      onClick={() => writes.move.mutate({ id: block.id, toIndex: index - 1 })}
                    >
                      ↑
                    </HandleButton>
                    <HandleButton
                      label="Move down"
                      disabled={index === blocks.length - 1}
                      onClick={() => writes.move.mutate({ id: block.id, toIndex: index + 1 })}
                    >
                      ↓
                    </HandleButton>
                    <HandleButton label="Delete block" onClick={() => writes.remove.mutate(block.id)}>
                      ×
                    </HandleButton>
                  </div>
                )}
              </BlockRow>
            ))}

            {editable && (
              <button
                data-testid="doc-add-block"
                className="flex items-center gap-2.5 self-start pt-2 pb-4 pl-7 text-13 text-faint hover:text-foreground"
                onClick={() => {
                  setAnchor(blocks.at(-1)?.id);
                  open("blockInsert");
                }}
              >
                <Kbd>/</Kbd> block, checkbox, ticket link, table…
              </button>
            )}
          </article>
        </div>

        <aside className="flex flex-col gap-2.5 pr-5 lg:pt-14 lg:pr-0">
          <span className="text-11 tracking-[0.1em] text-faint uppercase">Contents</span>
          {toc.length === 0 && <span className="text-12 text-faint">No heading yet.</span>}
          {/* An anchor, not a scroll handler: the heading block carries its own id, so
              the browser already knows how to reach it and nothing has to measure. */}
          {toc.map((entry) => (
            <a
              key={entry.id}
              data-testid="doc-toc-entry"
              href={`#${entry.id}`}
              className="truncate pl-2.5 text-12 text-muted-foreground hover:text-foreground"
            >
              {entry.text}
            </a>
          ))}

          <div className="my-2.5 h-px bg-border" />

          <span className="text-11 tracking-[0.1em] text-faint uppercase">Linked to</span>
          {tickets.length === 0 && <span className="text-12 text-faint">Nothing yet.</span>}
          {tickets.map((ticket) => (
            <Link
              key={ticket.id}
              data-testid="doc-rail-ticket"
              href={`/t/${ticket.identifier}`}
              className="truncate pl-2.5 font-mono text-12 text-muted-foreground hover:text-foreground"
              title={ticket.title}
            >
              {ticket.identifier}
            </Link>
          ))}
        </aside>
      </div>

      <footer className="flex items-center gap-3.5 bg-card px-5 py-2 text-11 text-faint">
        <span data-testid="doc-edited">{editedLabel(page, names, now)}</span>
        <span aria-hidden>·</span>
        <span>{page.notionPageId ? "Mirrored from Notion" : "Written in Kanso"}</span>
        <span className="flex-1" />
        <span>
          <Kbd>⌘K</Kbd> commands
        </span>
      </footer>

      {overlay === "blockInsert" && <InsertMenu onChoose={insert} onClose={closeOverlay} />}

      {mention && (
        <MentionPicker
          mention={mention}
          tickets={teamTickets}
          people={people}
          onPickTicket={(ticketId) => {
            writes.link.mutate({ ticketId, afterBlockId: anchorBlockId });
            closeMention();
          }}
          // A name in the text, not a stored mention — see `mention-picker.tsx`.
          onPickPerson={(person) => {
            const block = blocks.find((candidate) => candidate.id === anchorBlockId);
            if (block) {
              writes.patch.mutate({
                id: block.id,
                content: { ...block.content, text: `${block.content.text ?? ""}@${person.displayName} ` },
              });
            }
            closeMention();
          }}
          onClose={closeMention}
        />
      )}

      {draftTicket !== undefined && (
        <LinkedTicketPrompt
          title={draftTicket}
          onChange={setDraftTicket}
          onCancel={() => setDraftTicket(undefined)}
          onSubmit={() => {
            if (draftTicket.trim()) writes.createTicket.mutate(draftTicket.trim());
            setDraftTicket(undefined);
          }}
        />
      )}
    </div>
  );
}

/**
 * The hover row: the drag column on the left, the block, the handles on the right.
 *
 * The handle `⠿` is drawn and does not drag. Reordering is the two arrow buttons beside
 * it — `writes.move` takes an index, so a real drag is a gesture layer over an endpoint
 * that already exists rather than anything the server is missing. It is the one thing on
 * screen 07 this branch draws without wiring, and it says so here rather than looking
 * finished.
 */
function BlockRow({
  id,
  editable,
  focused,
  children,
}: {
  /** The block's own id, so the table of contents has somewhere to jump to. */
  id: string;
  editable: boolean;
  focused: boolean;
  children: React.ReactNode;
}) {
  return (
    <div
      id={id}
      data-testid="doc-block"
      data-focused={focused}
      className={cn("group flex items-start gap-2", !editable && "pointer-events-none")}
    >
      <span aria-hidden className="w-5 pt-3 text-right text-11 text-faint opacity-0 group-hover:opacity-100">
        ⠿
      </span>
      <div className="flex min-w-0 flex-1 items-start gap-2">{children}</div>
    </div>
  );
}

function HandleButton({
  label,
  disabled,
  onClick,
  children,
}: {
  label: string;
  disabled?: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      disabled={disabled}
      className="flex size-5 items-center justify-center rounded-sm text-faint hover:bg-accent hover:text-foreground disabled:opacity-40 disabled:hover:bg-transparent"
      onClick={onClick}
    >
      {children}
    </button>
  );
}
