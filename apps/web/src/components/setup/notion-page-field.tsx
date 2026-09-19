"use client";

import { useQuery } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { messageFor } from "./fields";
import { pageIdFrom, pageLabel, pickerNotice, sameId } from "./page-picker";

/**
 * The parent page, chosen instead of typed — as one block shared by the wizard and the
 * settings screen, the way `notion-connect.tsx` is.
 *
 * It replaces a field that asked for "32 hex characters from the page URL": the last
 * manual step of the old setup, and the one most likely to be got wrong, because a
 * plausible id for a page the integration cannot see failed at bootstrap rather than at
 * save. A search answers only pages the integration *can* write under, so a choice made
 * here is a choice that works.
 *
 * The raw field stays, behind a link and shown automatically when there is nothing to
 * pick. Notion's search is eventually consistent: it can omit a page shared ten seconds
 * ago, and a picker that cannot offer that page with no way past it is worse than the
 * field it replaced.
 */
export function NotionPageField({
  value,
  onChange,
  token,
  disabled,
}: {
  value: string;
  onChange: (pageId: string) => void;
  /** A token typed but not yet saved, so the list works before the step is saved. */
  token?: string;
  disabled?: boolean;
}) {
  /**
   * Which field is on screen. `auto` is the answer the search implies; the link sets one
   * of the other two, and has to be able to override `auto` in both directions — a link
   * that reads "choose from the list instead" and then leaves the raw field up is a dead
   * control, and it would be dead in exactly the case the escape hatch was opened for.
   */
  const [mode, setMode] = useState<"auto" | "raw" | "list">("auto");

  const search = useQuery({
    // Deliberately not keyed on the token: a secret has no business in a cache key, and
    // the answer to "which pages can this integration see" is the same question either
    // way. A token typed after the first read is picked up by Reload.
    queryKey: ["notion-parent-pages"],
    queryFn: () => api.notionPages({ token: token?.trim() || undefined }),
    retry: false,
    enabled: !disabled,
  });

  const pages = search.data?.pages ?? [];
  const notice = pickerNotice({
    loading: search.isLoading,
    error: search.isError ? messageFor(search.error) : undefined,
    answer: search.data,
  });

  const chosen = pages.find((page) => sameId(page.id, value));

  /**
   * The raw field comes up by itself when there is nothing to choose from, and when what
   * is already saved is not in the list — a select cannot represent a value it has no
   * option for, and drawing "choose a page…" over a configured instance would be a lie.
   * `disabled` is the environment-managed case: nothing was searched, so the only honest
   * thing to show is the id the environment set.
   */
  const raw =
    disabled ||
    mode === "raw" ||
    (mode === "auto" && !!search.data && (pages.length === 0 || (value.length > 0 && !chosen)));

  return (
    <div className="flex flex-col gap-1.5">
      <span className="text-11 uppercase tracking-wide text-faint">Parent page</span>

      {raw ? (
        <input
          className="w-full"
          autoComplete="off"
          spellCheck={false}
          // The wizard's card is a real form, and this is the setting the step exists to
          // write: the same guard the field it replaced carried.
          required={!disabled}
          disabled={disabled}
          value={value}
          placeholder="Page id, or paste the page URL and Kanso takes the id out of it"
          onChange={(event) => onChange(pageIdFrom(event.target.value))}
        />
      ) : (
        <select
          className="w-full"
          required={!disabled}
          disabled={disabled || pages.length === 0}
          value={chosen?.id ?? ""}
          onChange={(event) => onChange(event.target.value)}
        >
          <option value="">Choose the page Kanso creates its databases under…</option>
          {pages.map((page) => (
            <option key={page.id} value={page.id}>
              {pageLabel(page)}
            </option>
          ))}
        </select>
      )}

      {search.isLoading && <span className="text-11 text-faint">Reading the pages Kanso can see…</span>}

      {/*
       * Said as a sentence rather than as an empty list. The same choice `import-plan.tsx`
       * makes for its own "cannot read this workspace" sentence, for a stronger reason
       * here: an empty list is a *diagnosis* — the integration exists and nobody has shared
       * a page with it — and that is exactly what the old field could not tell anybody.
       */}
      {notice && (
        <div className="flex flex-col gap-1 rounded-md bg-warning/15 px-3 py-3 text-12 text-status-progress">
          <span className="font-medium">{notice.heading}</span>
          <span>{notice.body}</span>
        </div>
      )}

      {!disabled && (
        <div className="flex flex-wrap items-center gap-2.5 text-11">
          <button
            type="button"
            className="button"
            disabled={search.isFetching}
            onClick={() => search.refetch()}
          >
            {search.isFetching ? "Reading…" : "Reload the list"}
          </button>

          {pages.length > 0 && (
            <button
              type="button"
              className="underline text-faint hover:text-foreground"
              onClick={() => setMode(raw ? "list" : "raw")}
            >
              {raw ? "Choose from the list instead" : "Enter a page id instead"}
            </button>
          )}

          {chosen?.url && (
            <a className="underline text-faint hover:text-foreground" href={chosen.url} target="_blank" rel="noreferrer">
              Open it in Notion
            </a>
          )}
        </div>
      )}

      <span className="text-11 text-faint">
        Kanso creates its four databases under this page. Only pages shared with the
        integration are listed — connecting through Notion shares the ones you pick there.
      </span>
    </div>
  );
}
