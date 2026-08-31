"use client";

import { type Dispatch, type SetStateAction, useEffect, useMemo } from "react";
import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import {
  notionImportApi,
  notionPeopleApi,
  type NotionImportPlanRow,
  type NotionPeopleView,
} from "@/lib/api";
import { usePeople } from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import { Button } from "@/components/ui/button";
import { isSuggested, preselectedAccount, seenPeopleRows, type PersonRow } from "./import-people";

const EMPTY_VIEW: NotionPeopleView = { available: true, people: [] };

/**
 * Step 4: who the people the mapped columns name are, in Kanso.
 *
 * One row per person `peopleSeen` reports for this plan — not the whole workspace, which
 * is `settings/notion-people-section.tsx`'s job. Pre-filled from the standing
 * correspondence `notionPeopleApi.view()` already knows, so a person matched once, here or
 * in settings, is never asked about again; a row with only a guess carries the word
 * "suggested" so keeping it is a decision instead of a default nobody noticed.
 *
 * Nothing here writes. [people] reaches `NotionPeople.link` only once the import is
 * confirmed, through `notionImportApi.confirm`'s own `people` field — this step only
 * fills the map [onPeople] holds.
 */
export function StepPeople({
  plan,
  people,
  onPeople,
  onNext,
  onBack,
  pending,
  error,
}: {
  plan: NotionImportPlanRow[];
  people: Record<string, string | null>;
  onPeople: Dispatch<SetStateAction<Record<string, string | null>>>;
  onNext: () => void;
  onBack: () => void;
  pending: boolean;
  error?: string;
}) {
  const seen = useQuery({
    queryKey: ["notion-import-people-seen", plan],
    queryFn: () => notionImportApi.peopleSeen(plan),
    retry: false,
  });
  const known = useQuery({
    queryKey: ["notion-people"],
    queryFn: notionPeopleApi.view,
    retry: false,
  });
  const members = usePeople();

  const rows = useMemo<PersonRow[]>(
    () => seenPeopleRows(seen.data ?? [], known.data ?? EMPTY_VIEW),
    [seen.data, known.data],
  );

  /**
   * Seeds every row's pre-fill into [people] once, the same guard `import-step-columns.tsx`
   * uses for a base's mapping: a key already present is a reader's own answer (or an
   * earlier pass of this same seed) and is never overwritten.
   */
  useEffect(() => {
    onPeople((current) => {
      const missing = rows.filter((row) => current[row.id] === undefined);
      if (missing.length === 0) return current;
      const patch = Object.fromEntries(missing.map((row) => [row.id, preselectedAccount(row) || null]));
      return { ...current, ...patch };
    });
  }, [rows, onPeople]);

  const loading = seen.isLoading || known.isLoading;
  const sortedMembers = useMemo(
    () => [...(members.data ?? [])].sort((a, b) => a.displayName.localeCompare(b.displayName)),
    [members.data],
  );

  return (
    <>
      <div className="flex flex-col gap-1.5">
        <span className="text-15 font-medium">Who these people are</span>
        <span className="text-12 text-muted-foreground">
          Matching a Notion person to a Kanso account here is filled in once and holds for
          every later import; anyone left unmatched leaves their rows unassigned rather than
          guessed at. For the rest of the workspace, open Notion people under Connections in{" "}
          <Link href="/settings" className="underline">
            settings
          </Link>
          .
        </span>
      </div>

      {known.data && !known.data.available && (
        <div className="flex flex-col gap-1 rounded-md bg-warning/15 px-3 py-3 text-12 text-status-progress">
          <span className="font-medium">No suggestions from the workspace</span>
          <span>{known.data.reason}</span>
        </div>
      )}

      {loading && <span className="text-12 text-faint">Reading who these pages name…</span>}
      {seen.isError && <span className="text-12 text-urgent">{actionErrorMessage(seen.error)}</span>}
      {!loading && !seen.isError && rows.length === 0 && (
        <span className="text-12 text-faint">
          The mapped columns are empty on every page kept, so there is nobody to match.
        </span>
      )}

      {!loading && rows.length > 0 && (
        <div className="flex flex-col gap-0.5 text-12">
          {rows.map((row) => {
            // A stored `null` is "left unmatched" and has to win over the pre-fill; only a
            // key genuinely absent (the seed has not run yet) falls back to it.
            const stored = people[row.id];
            const value = stored !== undefined ? (stored ?? "") : preselectedAccount(row);
            return (
              <div
                key={row.id}
                className="grid h-[30px] grid-cols-[1fr_190px] items-center gap-3 rounded-sm bg-background px-3"
              >
                <span className="truncate">
                  {row.name ?? row.id}
                  {isSuggested(row) && <span className="pl-1.5 text-11 text-faint">suggested</span>}
                </span>
                <select
                  value={value}
                  onChange={(event) =>
                    onPeople((current) => ({ ...current, [row.id]: event.target.value || null }))
                  }
                >
                  <option value="">Unmatched</option>
                  {sortedMembers.map((member) => (
                    <option key={member.id} value={member.id}>
                      {member.displayName}
                    </option>
                  ))}
                </select>
              </div>
            );
          })}
        </div>
      )}

      <div className="flex items-center gap-2.5">
        <Button disabled={pending} onClick={onNext}>
          Preview the import
        </Button>
        <Button variant="outline" onClick={onBack}>
          Back
        </Button>
      </div>
      {error && <span className="text-12 text-urgent">{error}</span>}
    </>
  );
}
