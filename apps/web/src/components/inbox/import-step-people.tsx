"use client";

import { useEffect, useMemo } from "react";
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
import { buildAssignments, isSuggested, preselectedAccount, seenPeopleRows, type PersonRow } from "./import-people";

const EMPTY_VIEW: NotionPeopleView = { available: true, people: [] };

/**
 * Who the people the mapped columns name are, in Kanso.
 *
 * The other half of the folded panel `import-details.tsx` builds — see `StepColumns` for
 * the argument against a Next of its own here.
 *
 * One row per person `peopleSeen` reports for this plan — not the whole workspace, which
 * is `settings/notion-people-section.tsx`'s job. Pre-filled from the standing
 * correspondence `notionPeopleApi.view()` already knows, so a person matched once, here or
 * in settings, is never asked about again; a row with only a guess carries the word
 * "suggested" until the reader has actually looked at it — see [buildAssignments].
 *
 * [edits] holds only what the reader has touched, never a seeded pre-fill: `<select>`
 * shows the guess so it can be accepted, but nothing here writes it in until the reader
 * has. It is lifted into `import-dialog.tsx` rather than kept as this component's own
 * state — `Back` from the confirmation unmounts everything folded under `ImportPlan`,
 * this component included, and a local `edits` would restart empty on the way back,
 * silently dropping a match the reader had already made. [onPeople] is told the computed
 * write on every change, so the plan screen's own Preview button always sees the latest
 * map without this component needing to reach back into the shell's own state. Nothing
 * here writes for real either way — `NotionPeople.link` runs only once the import is
 * confirmed, through `notionImportApi.confirm`'s `people` field.
 */
export function StepPeople({
  plan,
  edits,
  onEdit,
  onPeople,
  onLoading,
}: {
  plan: NotionImportPlanRow[];
  edits: Record<string, string | null>;
  onEdit: (id: string, value: string | null) => void;
  onPeople: (people: Record<string, string | null>) => void;
  /** `peopleSeen`'s query key is the plan itself, so a target or column change re-keys it —
   *  reported as `isFetching` rather than `isLoading` so the shell sees that refetch too,
   *  not only the first one. */
  onLoading: (loading: boolean) => void;
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

  const assignments = useMemo(() => buildAssignments(rows, edits), [rows, edits]);

  /**
   * Tells the shell the write on every change to [rows] or [edits] — including the first,
   * so an untouched row's already-confirmed link is still carried into the request even if
   * the reader never opens this panel again before clicking through.
   */
  useEffect(() => {
    onPeople(assignments);
  }, [assignments, onPeople]);

  const busy = seen.isFetching || known.isFetching;
  useEffect(() => {
    onLoading(busy);
  }, [busy, onLoading]);

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

      {known.isError && (
        <span className="text-12 text-urgent">{actionErrorMessage(known.error)}</span>
      )}
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
            const touched = row.id in edits;
            const value = touched ? (edits[row.id] ?? "") : preselectedAccount(row);
            return (
              <div
                key={row.id}
                className="grid h-[30px] grid-cols-[1fr_190px] items-center gap-3 rounded-sm bg-background px-3"
              >
                <span className="truncate">
                  {row.name ?? row.id}
                  {isSuggested(row) && !touched && (
                    <span className="pl-1.5 text-11 text-faint">suggested</span>
                  )}
                </span>
                <select
                  value={value}
                  onChange={(event) => onEdit(row.id, event.target.value || null)}
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
    </>
  );
}
