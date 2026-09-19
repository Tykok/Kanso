"use client";

import { useMemo, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  notionImportApi,
  requestBasesApi,
  type NotionImportSource,
  type NotionRequestBase,
} from "@/lib/api";
import { actionErrorMessage } from "@/lib/errors";
import { useTeams } from "@/lib/queries";
import { SourceRow, WiredRow } from "./request-base-rows";
import { SettingsInline, SettingsNote } from "./field";

/**
 * Wiring a Notion « Demandes » base to a team's queue, with the mouse.
 *
 * `KAN-21` built the siphon and its three routes and said out loud that it had left this
 * screen out, so until now the one act the whole feature turns on — *this* base, *that*
 * queue — was a `curl`. For a feature whose sentence is "a salesperson drops their request
 * in Notion", the person left in a terminal was the administrator.
 *
 * ## Two questions, two audiences, and that is the shape of the screen
 *
 * **Which database** is discovery, and discovery is open to every member: `KAN-55` arbitrated
 * that in as many words — "la dérogation est la bonne règle" — because a non-configurator
 * running an import is the flow `NotionPeople.link`'s dispensation was written to permit.
 * So the workspace list below is drawn for anybody who opens this, and it is drawn from the
 * *same* query key screen 24's plan screen uses, because it is the same question asked
 * twice.
 *
 * **Whose queue** is a setting, and settings are the configurator's: `RequestBaseService`
 * makes the argument — choosing a base to browse is not choosing one to wire the instance
 * to permanently — and `requireInstanceAdmin` enforces it on all three routes. So the team
 * picker, the button and the wired list appear for a configurator and are replaced by a
 * sentence for everyone else.
 *
 * Neither half is re-decided here. A screen that hid the workspace from a member would
 * contradict `KAN-55` silently, and one that offered the button to a member would draw a
 * control whose only outcome is a 403.
 *
 * ## Why a base names a team at all
 *
 * `V37`'s header has the whole argument and `KAN-21`'s delivery comment repeats it: a
 * ticket with no team never enters `TriageRepository.queue`, `TriageService.similar`
 * answers nothing without a board to search, and `V20` makes it a private draft. A
 * team-less requests base would be a dead letter box with a trigram index on it. Hence the
 * second picker, and hence the button that stays disabled until it is answered.
 */
export function RequestBases({ canConfigure }: { canConfigure: boolean }) {
  const client = useQueryClient();

  /**
   * The workspace, on screen 24's own query key.
   *
   * Deliberately shared rather than namespaced: the import dialog and this screen ask the
   * server the identical question, and two keys would walk the workspace twice for one
   * answer — a network read per base, counted page by page. `retry: false` because an
   * instance with no token is a state to print, not a failure to retry.
   */
  const discovered = useQuery({
    queryKey: ["notion-import-sources"],
    queryFn: notionImportApi.sources,
    retry: false,
  });

  /**
   * What is wired, asked for only by a reader entitled to the answer — the shape
   * `useSyncDetail(canConfigure)` already uses on the neighbouring section, and for its
   * reason: a 403 in the background of a settings page is noise, and a member has nothing
   * to do with the ids of a base they cannot change.
   */
  const registered = useQuery({
    queryKey: ["notion-request-bases"],
    queryFn: requestBasesApi.list,
    enabled: canConfigure,
    retry: false,
  });

  const teams = useTeams();
  const [selected, setSelected] = useState<string | null>(null);
  const [teamId, setTeamId] = useState("");

  const sources = useMemo<NotionImportSource[]>(() => discovered.data?.sources ?? [], [discovered.data]);
  const bases = useMemo<NotionRequestBase[]>(() => registered.data ?? [], [registered.data]);

  /**
   * Where a queue can be. Archived teams are out: a base pointed at one would file every
   * request into a board nobody opens, which is the one destination worse than none.
   *
   * Derived rather than copied into state by an effect — the same reasoning `import-dialog`
   * writes down for its own destination list. The single-team instance is the common shape,
   * and there is no choice to make there, so it answers itself.
   */
  const destinations = useMemo(() => teams.data?.filter((team) => !team.archived) ?? [], [teams.data]);
  const destination = teamId || (destinations.length === 1 ? destinations[0].id : "");

  /**
   * A team's name, or its id when the list cannot name it.
   *
   * The full list, not [destinations]: a base wired to a team that was archived afterwards
   * is still wired, and printing its raw uuid where a name belongs is how somebody works out
   * that they should re-point it. `teams.id` cannot dangle — `V37`'s foreign key cascades —
   * so the fallback is about archiving and about a list that has not arrived yet, never
   * about a team that is gone.
   */
  const teamName = (id: string) => teams.data?.find((team) => team.id === id)?.name ?? id;

  /** The workspace's name for a wired base, when the workspace still offers it. */
  const sourceName = (dataSourceId: string) =>
    sources.find((source) => source.id === dataSourceId)?.name;

  /** The name of the team this base already feeds, or `undefined` when it feeds none. */
  const wiredTeamName = (dataSourceId: string) => {
    const wired = bases.find((base) => base.dataSourceId === dataSourceId);
    return wired && teamName(wired.teamId);
  };

  const invalidate = () => {
    client.invalidateQueries({ queryKey: ["notion-request-bases"] });
    // The queue is what a wired base fills, and it is a route away. Invalidated by prefix,
    // the way `core.ts`'s own mutations do, so a team-scoped variant of the key cannot be
    // left holding the count from before the base existed.
    client.invalidateQueries({ queryKey: ["triage"] });
  };

  const register = useMutation({
    mutationFn: (source: NotionImportSource) =>
      requestBasesApi.register({
        dataSourceId: source.id,
        databaseId: source.databaseId,
        teamId: destination,
      }),
    onSuccess: () => {
      setSelected(null);
      invalidate();
    },
  });

  const unregister = useMutation({
    mutationFn: requestBasesApi.unregister,
    onSuccess: invalidate,
  });

  const pick = sources.find((source) => source.id === selected);
  /**
   * `available: false` with a reason is a first-class answer, not an error — an instance with
   * no Notion token has no workspace to list — so both arrive at the same sentence.
   */
  const unavailable = discovered.isError
    ? actionErrorMessage(discovered.error)
    : discovered.data && !discovered.data.available
      ? (discovered.data.reason ?? "Kanso cannot read this workspace.")
      : undefined;

  return (
    <section className="flex flex-col gap-2.5 rounded-lg bg-card p-4" data-testid="request-bases">
      <span className="text-13 font-medium">Notion requests</span>
      <SettingsNote>
        A Notion database Kanso never writes to and only reads. Every page somebody creates in
        it becomes a ticket in one team&apos;s triage queue.
      </SettingsNote>

      {/* The rule, stated where it is being applied rather than only in `KAN-55`'s comment:
          a member who can see the workspace and not the button has been told why. */}
      {!canConfigure && (
        <SettingsNote>
          Anybody can see which databases this workspace holds. Wiring one to a queue is a
          setting, so it is the instance owner&apos;s or an admin&apos;s.
        </SettingsNote>
      )}

      {canConfigure && bases.length > 0 && (
        <div className="flex flex-col gap-0.5 pt-1.5 text-12">
          {bases.map((base) => (
            <WiredRow
              key={base.dataSourceId}
              base={base}
              name={sourceName(base.dataSourceId)}
              teamName={teamName(base.teamId)}
              stopping={unregister.isPending}
              onStop={() => unregister.mutate(base.dataSourceId)}
            />
          ))}
        </div>
      )}

      {discovered.isLoading && <SettingsNote>Reading the workspace…</SettingsNote>}

      {/* Said plainly rather than as an empty list, following screen 24's plan screen: a
          heading over no rows reads as a workspace that is empty, which is a different
          fact. */}
      {unavailable && (
        <div className="flex flex-col gap-1 rounded-md bg-warning/15 px-3 py-3 text-12 text-status-progress">
          <span className="font-medium">No workspace to read yet</span>
          <span>{unavailable}</span>
        </div>
      )}

      {!discovered.isLoading && !unavailable && (
        <div className="flex flex-col gap-0.5 pt-1.5 text-12">
          {sources.map((source) => (
            <SourceRow
              key={source.id}
              source={source}
              wiredTo={canConfigure ? wiredTeamName(source.id) : undefined}
              selectable={canConfigure}
              selected={selected === source.id}
              onSelect={() => setSelected(selected === source.id ? null : source.id)}
            />
          ))}
          {sources.length === 0 && (
            <SettingsNote>This workspace holds no database Kanso can read.</SettingsNote>
          )}
        </div>
      )}

      {canConfigure && !unavailable && (
        <>
          <SettingsInline>
            <label className="sr-only" htmlFor="request-base-team">
              Triage queue
            </label>
            <select
              id="request-base-team"
              className="max-w-[220px]"
              value={destination}
              onChange={(event) => setTeamId(event.target.value)}
            >
              <option value="">Whose queue?</option>
              {destinations.map((team) => (
                <option key={team.id} value={team.id}>
                  {team.name}
                </option>
              ))}
            </select>
            <button
              type="button"
              className="button button-primary"
              disabled={!pick || !destination || register.isPending}
              onClick={() => pick && register.mutate(pick)}
            >
              Siphon into this queue
            </button>
          </SettingsInline>

          {/*
           * The refusal, and the one place on this screen that had to be built rather than
           * borrowed.
           *
           * `RequestBaseService.register` refuses one of the mirror's own databases with a
           * sentence saying what would happen — the instance adopting its own tickets back
           * into itself, on every poll — and a screen that printed "Request failed (400)"
           * would throw away the only part of that a person can act on. `discovery` filters
           * the mirror out of the list, so the refusal is reached by a *stale* list: the
           * dialog open across a `bootstrapNotion`, or a second tab. That is exactly when
           * somebody needs the sentence, because the row they clicked looks fine.
           *
           * `flex flex-col` is load-bearing, not decoration. Two `<span>`s in a plain
           * `<div>` are inline boxes: they run together on one line — "That base cannot be
           * siphonedThat database is one Kanso's own mirror writes to." — and the reason is
           * long enough here to wrap into the heading. This repo has shipped that defect
           * twice; it is written down rather than assumed.
           */}
          {register.isError && (
            <div
              role="alert"
              data-testid="request-base-refused"
              className="flex flex-col gap-1 rounded-md border border-urgent/40 bg-urgent/5 px-3 py-2.5 text-12"
            >
              <span className="font-medium text-urgent">That base cannot be siphoned</span>
              <span className="text-muted-foreground">{actionErrorMessage(register.error)}</span>
            </div>
          )}
          {unregister.isError && (
            <SettingsNote error>{actionErrorMessage(unregister.error)}</SettingsNote>
          )}

          <SettingsNote>
            Kanso never writes to a requests base, and it does not read status, priority,
            dates or assignees off a page: whoever files a request is not the person who says
            when it is due. Those columns survive as prose in the ticket&apos;s description.
          </SettingsNote>
        </>
      )}
    </section>
  );
}
