"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";

/**
 * The first team, named where the import stops for the want of one.
 *
 * An instance a moment old has no team, and `NotionImportService` refuses a plan that
 * writes anything but teams without one. Both screens that lead into the import used to
 * express that refusal the same way — a disabled Next, and a `<select>` holding
 * "— choose a team —" and nothing else. A dropdown that opens onto nothing does not read
 * as a missing row; it reads as a broken control, and there is no way from there to find
 * out which of the two it is. So the block is one component, drawn by the wizard's card
 * before the dialog opens and by the plan screen in the place the destination would have
 * been.
 *
 * It writes a row, which is the one thing the dialog otherwise keeps for its confirmation.
 * That promise is about the workspace's content — nothing is read out of Notion and
 * written here — and a team somebody typed the name of is theirs either way.
 */
export function ImportFirstTeam() {
  const queryClient = useQueryClient();
  const [name, setName] = useState("");

  const create = useMutation({
    mutationFn: (teamName: string) => api.createTeam({ name: teamName }),
    onSuccess: () => {
      // By prefix, the way `team-dialog` does it: the key carries the archived toggle.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      setName("");
    },
  });

  return (
    <div className="flex flex-col gap-2">
      <span className="text-11 text-faint">
        No team yet. A Notion database of teams imports on its own; tickets and projects
        need a team to land in — one without a team is a draft, with no identifier to print
        and in no list. Import your teams base first, or name a team here.
      </span>

      <label className="flex flex-col gap-1">
        <span className="text-11 uppercase tracking-wide text-faint">First team</span>
        <input
          className="w-full max-w-[220px]"
          autoComplete="off"
          value={name}
          placeholder="Design"
          onChange={(event) => setName(event.target.value)}
        />
      </label>

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="button"
          disabled={create.isPending}
          onClick={() => {
            // Refused here rather than by the server, which refuses it too: a blank name
            // costs a round trip to learn nothing.
            const trimmed = name.trim();
            if (trimmed) create.mutate(trimmed);
          }}
        >
          {create.isPending ? "Creating…" : "Create the team"}
        </button>
        {create.error && (
          <span className="text-12 text-urgent">{actionErrorMessage(create.error)}</span>
        )}
      </div>
    </div>
  );
}
