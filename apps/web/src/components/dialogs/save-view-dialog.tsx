"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError } from "@/lib/api";
import { useCreateView, useServedFilters } from "@/lib/queries";
import { chipsOf } from "../organise/chips";
import { savedViewFilters } from "../organise/facets";
import { useOrganiseTeam } from "../organise/team";
import { DialogFrame, Field } from "./field";
import { useUi } from "@/store/ui";

/**
 * Names the view slice C's rail asked for, and nothing more.
 *
 * The rail opened `{ kind: "saveView" }` and stopped there: the dialog had to live beside
 * the other three and be mounted from `app/page.tsx`, both of which were frozen for the
 * fan-out. This is the integration pass keeping that bargain.
 *
 * It saves the question the list is currently asking, which it could not do before: the
 * composed filters had nowhere to live but one route's own state, so a dialog reachable
 * from five places could only ever have read them from one of them. They live in
 * `store/ui.ts` now, which is the change the previous version of this comment was waiting
 * for — "when the filters do become readable from a store rather than from a route's own
 * state, this is the one component that changes".
 *
 * They are shown before they are saved, as the same chips the strip draws. A dialog that
 * silently carried a filter set into a stored view would be a view whose contents nobody
 * agreed to; a dialog that silently dropped one would be the emptier bug. Naming them is
 * the whole of the difference.
 *
 * `savedViewFilters` is the gate on the way out, and it is the server's own list rather
 * than this file's opinion: a key the server does not serve is a 400 on the write, and
 * the write is the last moment anybody is looking.
 */
export function SaveViewDialog({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const { team } = useOrganiseTeam();
  const create = useCreateView();
  const served = useServedFilters();
  const composed = useUi((state) => state.filters);

  const [name, setName] = useState("");
  const [shared, setShared] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const filters = savedViewFilters(composed, served.data ?? []);
  /**
   * The ids are printed raw. This dialog opens over five routes and only some of them
   * have loaded the projects, people, cycles and labels that would resolve them, and a
   * name that is right on two screens and a UUID on the other three is worse than one
   * that is honest everywhere. The strip behind the dialog has the names.
   */
  const chips = chipsOf(filters, {
    project: (id) => id,
    person: (id) => id,
    cycle: (id) => id,
    label: (id) => id,
  });

  const submit = () => {
    const trimmed = name.trim();
    if (!trimmed) {
      setError("A view needs a name.");
      return;
    }
    if (!team) {
      setError("No team to save this view in.");
      return;
    }

    create.mutate(
      { teamId: team.id, name: trimmed, shared, filters, groupBy: "status", sortBy: "priority" },
      {
        // Straight to the view that was just made: the rail's own list is invalidated by
        // the mutation, so coming back to a page that now has one more row teaches less
        // than landing on the row itself.
        onSuccess: (view) => {
          onClose();
          router.push(`/views/${view.id}`);
        },
        onError: (cause) =>
          setError(
            cause instanceof ApiError ? cause.message : "Could not save the view.",
          ),
      },
    );
  };

  return (
    <DialogFrame
      title={team ? `New view in ${team.name}` : "New view"}
      onClose={onClose}
      onSubmit={submit}
      submitLabel="Save the view"
      pending={create.isPending}
      error={error}
    >
      <Field label="Name" hint="What this view is for — “Sync debt”, “Blocked for 3 days”.">
        <input autoFocus value={name} onChange={(event) => setName(event.target.value)} />
      </Field>

      <Field
        label="The question"
        hint={
          chips.length === 0
            ? "Nothing is filtered, so this view will hold everything in the team. Press F on the list to narrow it first."
            : "What the list is asking right now. Change it on the list, or on the view once it is saved."
        }
      >
        <div className="flex flex-wrap items-center gap-2" data-testid="save-view-filters">
          {chips.length === 0 ? (
            <span className="text-13 text-faint">No filters</span>
          ) : (
            chips.map((chip) => (
              <span
                key={chip.key}
                className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-2.5 py-1 text-12 text-muted-foreground"
              >
                {chip.label} {chip.value && <span className="text-foreground">{chip.value}</span>}
              </span>
            ))
          )}
        </div>
      </Field>

      {/* Shared by default, which is what the drawing shows: a view nobody else can see is
          a filter, and the filter box already exists for that. */}
      <Field label="Visibility">
        <label className="flex items-center gap-2 text-13">
          <input
            type="checkbox"
            checked={shared}
            onChange={(event) => setShared(event.target.checked)}
          />
          Shared with the team
        </label>
      </Field>
    </DialogFrame>
  );
}
