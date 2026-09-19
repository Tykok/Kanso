"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { api } from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { useTeams } from "@/lib/queries";
import { TextField } from "./fields";

/**
 * Screen 24's way in, from the wizard rather than from the app it has not opened yet.
 *
 * The dialog itself is mounted by `notion-step.tsx`, outside the `FormCard`'s `<form>`,
 * and this card only asks for it — which is the whole reason [onOpen] exists instead of
 * the state living here. `import-step-two`'s Next is a shadcn `Button`, and a `<button>`
 * with no `type` inside a form is a submit: opened inside the wizard's form, the second
 * step of the import would advance the wizard.
 *
 * The rest of this file is one condition. An instance a moment old has no team, and
 * `NotionImportService` refuses a plan that writes anything but teams without one —
 * `import-step-two` refuses it first, by disabling Next, which on a fresh install is a
 * grey button and no sentence. The reason is worth stating where somebody can act on it:
 * a ticket with no team is a draft in Postgres, with no `KAN-142` to print and in no
 * team-scoped list, so "import it anyway and sort it out later" is not an option that was
 * left out — it is one that produces rows nobody can find.
 *
 * Two ways out, both offered: a Notion database of teams imports on its own, needing no
 * destination at all, and otherwise a team is named here. The team is created by this card
 * and not by the dialog on purpose — the dialog's own promise is that nothing is written
 * before its fifth step.
 */
export function NotionImportCard({ onOpen }: { onOpen: () => void }) {
  const queryClient = useQueryClient();
  const teams = useTeams();
  const [name, setName] = useState("");

  // Archived teams are filtered out by `import-dialog` before it offers the list, so a
  // count that included them would promise a destination the picker never shows.
  const destinations = (teams.data ?? []).filter((team) => !team.archived);

  const create = useMutation({
    mutationFn: (teamName: string) => api.createTeam({ name: teamName }),
    onSuccess: () => {
      // By prefix, the way `team-dialog` does it: the key carries the archived toggle.
      queryClient.invalidateQueries({ queryKey: ["teams"] });
      setName("");
    },
  });

  return (
    <div className="flex flex-col gap-2.5">
      <span className="text-13 font-medium">Import from Notion</span>

      <span className="text-11 text-faint">
        Databases scattered around the workspace — tasks, projects, teams — read once and
        written into Kanso. Nothing in Notion is changed, and a base imported twice is
        skipped rather than duplicated.
      </span>

      {destinations.length === 0 && !teams.isPending && (
        <>
          <span className="text-11 text-faint">
            No team yet. A Notion database of teams imports on its own; tickets and
            projects need a team to land in — one without a team is a draft, with no
            identifier to print and in no list. Import your teams base first, or name a
            team here.
          </span>

          <TextField
            label="First team"
            value={name}
            placeholder="Design"
            onChange={(event) => setName(event.target.value)}
          />

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="button"
              disabled={create.isPending}
              onClick={() => {
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
        </>
      )}

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="button" onClick={onOpen}>
          Import from Notion
        </button>
        <span className="text-11 text-faint">Five steps, and nothing written until the last.</span>
      </div>
    </div>
  );
}
