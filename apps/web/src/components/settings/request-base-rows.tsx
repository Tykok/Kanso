"use client";

import { type NotionImportSource, type NotionRequestBase } from "@/lib/api";
import { pageCount } from "@/components/inbox/import-targets";

/**
 * The two row shapes `request-bases.tsx` draws, split off so that file stays re-readable.
 *
 * The settings page's own comment sets the threshold and the precedent: it keeps
 * `NotionPeopleSection` out of `ConnectionsSection` because that file is "already 375 lines
 * about a different subject". This file's parent reached the same number, and these two rows
 * are the part of it that is about *drawing a row* rather than about what the screen decides.
 *
 * They are also where every width defect on this screen would live, which is a second reason
 * to keep them together: the two rules below are the same rule, and reading them side by side
 * is how the next row added here gets them right.
 */

/**
 * A base that is wired, and the queue it feeds.
 *
 * [name] is `undefined` when the workspace no longer offers this base — the ordinary cause
 * being that it has since become one of the mirror's own, which discovery filters out — and
 * the id is then printed instead. Ugly on purpose: a raw data source id where a name belongs
 * is how somebody works out that this base wants re-pointing.
 */
export function WiredRow({
  base,
  name,
  teamName,
  onStop,
  stopping,
}: {
  base: NotionRequestBase;
  name?: string;
  teamName: string;
  onStop: () => void;
  stopping: boolean;
}) {
  return (
    <div
      data-testid="wired-request-base"
      className="flex items-center gap-2.5 rounded-sm bg-background px-3 py-1.5"
    >
      {/* `min-w-0` and `truncate` together, because a Notion database can be called anything
          and neither half works alone: a flex item defaults to `min-width: auto`, so
          `truncate` on it has nothing to truncate into and the row grows until the team
          beside it is pushed off the card. */}
      <span className="min-w-0 flex-1 truncate">
        {name ?? <span className="font-mono text-11 text-faint">{base.dataSourceId}</span>}
      </span>
      {/* `shrink-0` for `Similar`'s reason in `triage-view.tsx`: a cell that may shrink is
          allowed to break after its arrow, and this is the one that must not — it is the
          answer to "whose queue". */}
      <span className="shrink-0 text-muted-foreground">→ {teamName}</span>
      <button type="button" className="button shrink-0" disabled={stopping} onClick={onStop}>
        Stop
      </button>
    </div>
  );
}

/**
 * One database in the workspace.
 *
 * A `<button>` for a configurator and a plain row for everybody else, rather than one
 * disabled button: a disabled control is still a control, and it promises a member that
 * selecting a base would do something for them. What they are shown instead is the list,
 * which is the half `KAN-55` says is theirs.
 */
export function SourceRow({
  source,
  wiredTo,
  selectable,
  selected,
  onSelect,
}: {
  source: NotionImportSource;
  /** The team's name when this base is already wired, `undefined` when it is not. */
  wiredTo?: string;
  selectable: boolean;
  selected: boolean;
  onSelect: () => void;
}) {
  /*
   * `grid-cols-[1fr_auto]` with `min-w-0` on the name, not a flex row: the right-hand cell
   * holds a page count and possibly a team name, and a `1fr` track is the one that gives that
   * cell its content width and makes the name truncate into whatever is left. A long Notion
   * database title is the ordinary case here, not the edge one.
   */
  const shape = "grid min-h-[30px] grid-cols-[1fr_auto] items-center gap-3 rounded-sm px-3";
  const body = (
    <>
      <span className="min-w-0 truncate">{source.name}</span>
      <span className="shrink-0 text-muted-foreground">
        {wiredTo !== undefined && <span className="text-status-done">wired → {wiredTo} · </span>}
        {pageCount(source.pages, source.pagesExact)}
      </span>
    </>
  );

  if (!selectable) {
    return (
      <div data-testid="request-base-option" className={`${shape} bg-background`}>
        {body}
      </div>
    );
  }

  return (
    <button
      type="button"
      data-testid="request-base-option"
      aria-pressed={selected}
      onClick={onSelect}
      className={
        selected
          ? `${shape} bg-accent-soft text-left shadow-[inset_2px_0_0_var(--primary)]`
          : `${shape} bg-background text-left hover:bg-accent`
      }
    >
      {body}
    </button>
  );
}
