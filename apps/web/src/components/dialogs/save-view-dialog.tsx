"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import { ApiError } from "@/lib/api";
import { useCreateView } from "@/lib/queries";
import { useOrganiseTeam } from "../organise/shell";
import { DialogFrame, Field } from "./field";

/**
 * Names the view slice C's rail asked for, and nothing more.
 *
 * The rail opened `{ kind: "saveView" }` and stopped there: the dialog had to live beside
 * the other three and be mounted from `app/page.tsx`, both of which were frozen for the
 * fan-out. This is the integration pass keeping that bargain.
 *
 * What it deliberately does *not* do is capture the filters currently on screen. The rail
 * is reachable from four routes and the ticket list, and only one of those has filter chips
 * to read; a dialog that silently saved an empty filter set from the other four would make
 * "Save the view" mean something different depending on where it was pressed. So a new view
 * starts empty and grouped by status — the same default `/views/[id]` draws — and the chips
 * on that screen are where a filter is added. When the filters do become readable from a
 * store rather than from a route's own state, this is the one component that changes.
 */
export function SaveViewDialog({ onClose }: { onClose: () => void }) {
  const router = useRouter();
  const { team } = useOrganiseTeam();
  const create = useCreateView();

  const [name, setName] = useState("");
  const [shared, setShared] = useState(true);
  const [error, setError] = useState<string | null>(null);

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
      { teamId: team.id, name: trimmed, shared, groupBy: "status", sortBy: "priority" },
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
