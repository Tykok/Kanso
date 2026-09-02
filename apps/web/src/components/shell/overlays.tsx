"use client";

import { useMemo } from "react";
import { usePathname, useRouter } from "next/navigation";
import { Composer } from "@/components/composer";
import { DispositionDialog } from "@/components/dialogs/disposition-dialog";
import { ProjectDialog } from "@/components/dialogs/project-dialog";
import { SaveViewDialog } from "@/components/dialogs/save-view-dialog";
import { TeamDialog } from "@/components/dialogs/team-dialog";
import { ImportDialog } from "@/components/inbox/import-dialog";
import { CommandPalette, DetailPanel, HelpOverlay } from "@/components/overlays";
import type { PaletteCommand } from "@/components/search/results";
import { SettingsPanel } from "@/components/settings/panel";
import { availableActions, type ActionContext } from "@/lib/actions";
import { isMac } from "@/lib/platform";
import { hintFor } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import { useUi } from "@/store/ui";

/**
 * Every overlay and every shared dialog, mounted once.
 *
 * This is not a de-duplication. `app/page.tsx` and `views/shell.tsx` each mounted their
 * own copies, and `OrganiseShell` mounted **none at all** — so on `/cycles/[number]`,
 * `/triage`, `/views`, `/views/[id]`, `/workload` and `/inbox`, a long list of controls
 * that are visibly there were dead clicks:
 *
 *  - the brand menu's `Settings`, `Keyboard shortcuts` and `Command palette`,
 *  - the column's `+ New team` and `+ New project`,
 *  - every `⋯` on a team or project row — rename, archive, delete, favourite,
 *  - `+ Save the view` on a saved view, whose own comment says "the integration pass
 *    mounts it".
 *
 * And worse than inert. `useUi` is a module-level store, so a click on one of those left
 * `dialog.kind` set with nobody drawing it; navigating to `/` afterwards made the Team
 * dialog appear out of nowhere, about a team the reader had picked minutes earlier on
 * another screen. Mounting these here is what makes the store's value always drawn
 * wherever it can be set.
 *
 * Three overlays are deliberately still page-local, and for one reason each: `filter`
 * needs the filters it is composing (the list's own, or a saved view's stored ones),
 * `conflict` needs the notification row it is about, and `blockInsert` needs the caret it
 * is inserting at. None of those is a value the shell holds, and mounting them here would
 * mean threading page data through the shell to get it back.
 */
export function ShellOverlays({
  ctx,
  commands,
}: {
  ctx: ActionContext;
  /** The page's own rows, when it has some — the dependency picker's candidate list. */
  commands?: PaletteCommand[];
}) {
  const router = useRouter();
  const pathname = usePathname();
  const { overlay, dialog, scope, close, setScope } = useUi();

  /**
   * What the palette lists when the page has not said otherwise: everything the registry
   * permits, plus the teams.
   *
   * The teams are rows from the server, so no static registry can enumerate them — which
   * is why this list is assembled rather than read. Asked for a predecessor instead,
   * `app/page.tsx` publishes its own candidates and this is not used at all: same
   * overlay, same filtering, same keys, so `d` costs nobody a new mental model.
   */
  /**
   * The reader's own keys, not the registry's defaults.
   *
   * This was the last display surface in the app still printing `hintOf` — and the palette
   * is the worst place to be a version behind, because it is where somebody goes precisely
   * when they cannot remember a key. `useBindings()` is the same merge the dispatcher
   * resolves against, so a remapped chord is printed by whichever door the reader opens.
   */
  const { keys } = useBindings();

  const fallback = useMemo<PaletteCommand[]>(
    () => [
      ...availableActions(ctx).map((action) => ({
        id: action.id,
        label: action.label,
        hint: hintFor(action, keys, isMac()),
        run: () => action.run(ctx),
      })),
      ...ctx.teams.map((team) => ({
        id: `view.team.${team.id}`,
        label: `View team: ${team.name}`,
        run: () => {
          setScope({ kind: "team", id: team.id });
          close();
        },
      })),
    ],
    [ctx, keys, setScope, close],
  );

  const selected = ctx.selected;

  return (
    <>
      {overlay === "composer" && <Composer scope={scope} onClose={close} />}
      {overlay === "palette" && (
        <CommandPalette commands={commands ?? fallback} onClose={close} />
      )}
      {overlay === "help" && <HelpOverlay onClose={close} />}
      {overlay === "settings" && <SettingsPanel onClose={close} />}
      {overlay === "detail" && selected && (
        <DetailPanel
          ticket={selected}
          projects={ctx.projects}
          onPatch={(body) => ctx.patchTicket({ id: selected.id, ...body })}
          onDelete={() => {
            ctx.deleteTicket(selected.id);
            close();
            // A ticket deleted from its own page leaves the reader on an address that no
            // longer resolves, so the panel takes them off it. `views/shell.tsx` did the
            // same, and pushed rather than went back on purpose: the history behind a
            // ticket page is often another drawing of the ticket just deleted.
            if (pathname !== "/") router.push("/");
          }}
          onClose={close}
        />
      )}

      {dialog.kind === "team" && (
        <TeamDialog id={dialog.id} parentTeamId={dialog.parentTeamId} onClose={close} />
      )}
      {dialog.kind === "project" && (
        <ProjectDialog id={dialog.id} teamId={dialog.teamId} onClose={close} />
      )}
      {dialog.kind === "importMap" && <ImportDialog onClose={close} />}
      {dialog.kind === "saveView" && <SaveViewDialog onClose={close} />}
      {dialog.kind === "disposition" && (
        <DispositionDialog target={dialog.target} severity={dialog.severity} onClose={close} />
      )}
    </>
  );
}
