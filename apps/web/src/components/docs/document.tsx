"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import type { DocBlock, DocBlockContent, DocBlockKind, DocPageDetail, Ticket, User } from "@/lib/api";
import { heldByOther, refusalMessage } from "@/lib/doc-locks";
import { cn } from "@/lib/utils";
import { useDocBlockWrites, useDocViewers, useRefreshDocPage } from "@/lib/queries";
import { useDocsUi } from "@/store/docs";
import { useUi } from "@/store/ui";
import { TopbarSlot, useReportError } from "../shell/topbar-slot";
import { BlockLockBadge } from "./block-lock-badge";
import { DocPresence } from "./presence";
import { useBlockLock } from "./use-block-lock";
import { FavouriteStar } from "../favourites";
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
  meId,
  now,
}: {
  detail: DocPageDetail;
  people: User[];
  /** Every ticket in the page's team: what `#` offers and what a query table reads. */
  teamTickets: Ticket[];
  /** The server's own answer to "may this reader write here", not a re-derivation. */
  editable: boolean;
  /** Who is reading this, so the roster can leave them out of their own presence. */
  meId?: string;
  now?: Date;
}) {
  const { page, blocks, tickets } = detail;
  const writes = useDocBlockWrites(page.id);
  const reportError = useReportError();

  /**
   * `KAN-25`. Everybody on the page except this reader — they know they are here, and a
   * chip for themselves would make "is anybody else here" a matter of counting to two.
   *
   * Filtered here rather than in `DocPresence` or on the server: the endpoint's answer is
   * the whole truth about the page, which is what makes it assertable, and the roster the
   * *server* holds is the one an e2e spec reads back over HTTP.
   */
  const viewers = (useDocViewers(page.id).data ?? []).filter((viewer) => viewer.userId !== meId);

  /**
   * The refusal, in the one strip this shell has for a failure with no dialog to land in.
   *
   * `useReportError` and not a toast, because that is where every other refused write on
   * every other route already goes — and because the bug it was written for was a refusal
   * that went *nowhere*. `refusalMessage` guarantees this is never called with silence:
   * a 409 it cannot parse still arrives as the server's own sentence, which names the
   * holder too.
   */
  const lock = useBlockLock(reportError);

  // A lock lapsing is the one change on this screen no event announces — see
  // `useRefreshDocPage`. The badge counting down to it is what asks.
  const refreshPage = useRefreshDocPage(page.id);
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

  /**
   * A block's text, on its way to the server, with the one refusal it can meet.
   *
   * The lock is drawn *before* anybody types, so this 409 is the narrow race rather than
   * the common case: the block was free when the page was last drawn and somebody claimed
   * it in between. It still has to say who — a keystroke that vanishes with no explanation
   * is the same bug as a menu entry that does nothing, and this repository has shipped
   * that one twice.
   */
  const commit = (block: DocBlock, content: DocBlockContent) =>
    writes.patch.mutate(
      { id: block.id, content },
      { onError: (error) => reportError(refusalMessage(error, new Date())) },
    );

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      {/*
        * The three controls this page has of its own, in the one bar the shell draws.
        *
        * This was a `<header>` here, and it drew `Documents / {title}` beside them — the
        * fourth of the four page headers the shell replaced, and the one the pass missed
        * because it is a component rather than a route. The result was two bars stacked on
        * every document, both saying the same two words, and `Documents` reachable as two
        * different links: `18-documents.spec.ts` found it as a strict-mode violation and a
        * reader found it as a wasted 44px. The trail is `breadcrumbOf`'s now — `/docs/[id]`
        * publishes the title as its leaf — and the header claimed a parent for `Documents`
        * that `lib/nav.ts` deliberately does not give it.
        */}
      <TopbarSlot>
        {/* The only way to pin a document: this route hands its keys to the caret, where
            `s` is a letter somebody is typing, and it mounts no command palette. */}
        <FavouriteStar target={{ kind: "doc", id: page.id }} label={page.title} />
        <span className="flex-1" />

        {/* Who else is here. Before the Notion chip because it is the one thing in this
            bar that changes while somebody is looking at it. */}
        <DocPresence viewers={viewers} />

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
      </TopbarSlot>

      <div className="grid min-h-0 flex-1 grid-cols-1 gap-8 overflow-y-auto pt-8 lg:grid-cols-[1fr_200px] lg:pr-10">
        <div className="flex min-w-0 justify-center">
          <article className="flex w-[720px] max-w-full flex-col gap-[18px] px-5 lg:px-0">
            <h1 className="text-30 leading-[1.15] font-medium tracking-[-0.02em]">{page.title}</h1>

            {blocks.map((block, index) => {
              // Whoever has this block, if it is not this reader. `undefined` in both the
              // cases that mean "type here": nobody holds it, or they do.
              const held = heldByOther(block, meId);
              // Any block on the page held by anybody else refuses a *reorder*, because
              // `setOrder` rewrites every position — `DocBlockLockService` argues it, and
              // the arrows are disabled here so the refusal is visible before it is tried.
              const pageHeld = blocks.some((other) => heldByOther(other, meId));

              return (
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
                    onCommit={(content) => commit(block, content)}
                    onFocus={() => {
                      setAnchor(block.id);
                      // Claimed on focus rather than on the first keystroke: the point is
                      // to stop the *second* person starting, and by the first keystroke
                      // they have already begun typing something they will lose.
                      if (editable && !held) lock.hold(block.id);
                    }}
                    onBlur={lock.release}
                    onKeyDown={onKeyDown}
                    locked={Boolean(held)}
                  />

                  {held && <BlockLockBadge lock={held} onFreed={refreshPage} />}

                  {editable && (
                    <div className="flex shrink-0 gap-1 opacity-0 group-hover:opacity-100 group-focus-within:opacity-100">
                      <HandleButton
                        label="Move up"
                        disabled={index === 0 || pageHeld}
                        onClick={() => writes.move.mutate({ id: block.id, toIndex: index - 1 })}
                      >
                        ↑
                      </HandleButton>
                      <HandleButton
                        label="Move down"
                        disabled={index === blocks.length - 1 || pageHeld}
                        onClick={() => writes.move.mutate({ id: block.id, toIndex: index + 1 })}
                      >
                        ↓
                      </HandleButton>
                      <HandleButton
                        label="Delete block"
                        disabled={Boolean(held)}
                        onClick={() => writes.remove.mutate(block.id)}
                      >
                        ×
                      </HandleButton>
                    </div>
                  )}
                </BlockRow>
              );
            })}

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
