"use client";

import { useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { notionPeopleApi } from "@/lib/api";
import { keys, usePeople } from "@/lib/queries";
import { actionErrorMessage } from "@/lib/errors";
import { isSuggested, preselectedAccount } from "@/components/inbox/import-people";
import { SettingsInline, SettingsNote } from "./field";

const QUERY_KEY = ["notion-people"];

/** A row's current selection: the reader's own edit if there is one, else the pre-fill. */
function selection(edits: Record<string, string | null>, id: string, fallback: string): string {
  return id in edits ? (edits[id] ?? "") : fallback;
}

/**
 * The standing correspondence between Notion workspace members and Kanso accounts —
 * `users.notion_person_id`, matched by [NotionPeople] and read by screen 24's step 4 to
 * pre-fill the people its own mapped columns met. This is where the rest of the workspace
 * gets matched, once, ahead of any particular import — and once the mirror writes the
 * `people` property, the only place that link is made or broken outside one.
 *
 * The read is open to anyone who can see the app, the same as `NotionPeopleController`
 * itself says — matching identities is not a secret. Saving is configurator-only on the
 * server, so a member sees the same rows with no control that would refuse: the same shape
 * `ConnectionsSection` uses for its own fields.
 *
 * [edits] holds only what the reader has actually touched; a row nobody touched still
 * saves its pre-fill (`preselectedAccount`) when the button is pressed — the pre-fill is a
 * default, not a rule nobody can see, the same promise step 3's column selects make.
 */
export function NotionPeopleSection({ canConfigure }: { canConfigure: boolean }) {
  const queryClient = useQueryClient();
  const view = useQuery({ queryKey: QUERY_KEY, queryFn: notionPeopleApi.view, retry: false });
  const members = usePeople();
  const [edits, setEdits] = useState<Record<string, string | null>>({});

  const rows = view.data?.people ?? [];

  const save = useMutation({
    mutationFn: () => {
      const assignments = Object.fromEntries(
        rows.map((match) => [
          match.notion.id,
          match.notion.id in edits ? edits[match.notion.id] : (preselectedAccount(match) || null),
        ]),
      );
      return notionPeopleApi.link(assignments);
    },
    onSuccess: (next) => {
      queryClient.setQueryData(QUERY_KEY, next);
      queryClient.invalidateQueries({ queryKey: keys.people });
      setEdits({});
    },
  });

  const sortedMembers = [...(members.data ?? [])].sort((a, b) =>
    a.displayName.localeCompare(b.displayName),
  );

  return (
    <div className="flex flex-col gap-2.5 rounded-lg bg-card p-4">
      <span className="text-13 font-medium">Notion people</span>

      {view.isLoading && <SettingsNote>Reading the workspace’s members…</SettingsNote>}
      {view.isError && <SettingsNote error>{actionErrorMessage(view.error)}</SettingsNote>}

      {view.data && !view.data.available && <SettingsNote error>{view.data.reason}</SettingsNote>}

      {view.data?.available && (
        <>
          <SettingsNote>
            Matching a Notion person to a Kanso account here is what lets an import — and
            eventually the live mirror — put their work on the right account, without
            asking again.
          </SettingsNote>

          {rows.length === 0 && <SettingsNote>Notion has nobody in this workspace yet.</SettingsNote>}

          {rows.length > 0 && (
            <ul className="flex flex-col gap-px overflow-hidden rounded-md border border-border">
              {rows.map((match) => {
                const fallback = preselectedAccount(match);
                const current = selection(edits, match.notion.id, fallback);
                return (
                  <li
                    key={match.notion.id}
                    className="flex items-center gap-3 border-b border-border bg-background px-3 py-2 last:border-b-0"
                  >
                    <span className="flex min-w-0 flex-1 flex-col gap-px leading-tight">
                      {match.notion.name ?? match.notion.id}
                      {isSuggested(match) && <span className="text-11 text-faint"> suggested</span>}
                      {match.notion.email && <SettingsNote>{match.notion.email}</SettingsNote>}
                    </span>
                    {canConfigure ? (
                      <select
                        value={current}
                        disabled={save.isPending}
                        onChange={(event) =>
                          setEdits((prev) => ({
                            ...prev,
                            [match.notion.id]: event.target.value || null,
                          }))
                        }
                      >
                        <option value="">Unmatched</option>
                        {sortedMembers.map((member) => (
                          <option key={member.id} value={member.id}>
                            {member.displayName}
                          </option>
                        ))}
                      </select>
                    ) : (
                      <span className="text-12 text-muted-foreground">
                        {sortedMembers.find((member) => member.id === current)?.displayName ?? "Unmatched"}
                      </span>
                    )}
                  </li>
                );
              })}
            </ul>
          )}

          {canConfigure && rows.length > 0 && (
            <SettingsInline>
              <button
                className="button button-primary"
                disabled={save.isPending}
                onClick={() => save.mutate()}
              >
                Save
              </button>
            </SettingsInline>
          )}
          {save.isError && <SettingsNote error>{actionErrorMessage(save.error)}</SettingsNote>}
        </>
      )}
    </div>
  );
}
