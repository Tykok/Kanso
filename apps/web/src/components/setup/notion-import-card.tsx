"use client";

import { ImportFirstTeam } from "@/components/inbox/import-first-team";
import { useTeams } from "@/lib/queries";

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
 * destination at all, and otherwise a team is named here — by [ImportFirstTeam], which
 * step 2 of the dialog now draws as well. This card is not the only way in: the settings
 * screen opens the same dialog with nothing in front of it, and that path reached the dead
 * Next with none of this said.
 */
export function NotionImportCard({ onOpen }: { onOpen: () => void }) {
  const teams = useTeams();

  // Archived teams are filtered out by `import-dialog` before it offers the list, so a
  // count that included them would promise a destination the picker never shows.
  const destinations = (teams.data ?? []).filter((team) => !team.archived);

  return (
    <div className="flex flex-col gap-2.5">
      <span className="text-13 font-medium">Import from Notion</span>

      <span className="text-11 text-faint">
        Databases scattered around the workspace — tasks, projects, teams — read once and
        written into Kanso. Nothing in Notion is changed, and a base imported twice is
        skipped rather than duplicated.
      </span>

      {destinations.length === 0 && !teams.isPending && <ImportFirstTeam />}

      <div className="flex flex-wrap items-center gap-2">
        <button type="button" className="button" onClick={onOpen}>
          Import from Notion
        </button>
        <span className="text-11 text-faint">Five steps, and nothing written until the last.</span>
      </div>
    </div>
  );
}
