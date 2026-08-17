"use client";

import { useMemo, useState } from "react";
import { ApiError, type MemberRole } from "@/lib/api";
import { useAddTeamMember, useRemoveTeamMember, usePeople, useTeamMembers } from "@/lib/queries";
import { Button } from "@/components/ui/button";

const ROLE_LABELS: Record<MemberRole, string> = {
  member: "Member",
  admin: "Admin",
};

function message(error: unknown): string {
  return error instanceof ApiError ? error.message : ((error as Error)?.message ?? "Something went wrong");
}

/**
 * Who may be assigned this team's work — the surface for `team_members`, which has
 * had endpoints since the first CRUD branch but nothing in the app to call them.
 * `onError` reports failures through the dialog's own footer rather than inline,
 * the way `team-dialog.tsx` already routes every other server message here.
 *
 * `canConfigure` governs the add and remove controls only, never whether this section
 * mounts: `GET /teams/{id}/members` answers any authenticated user, per the instance's
 * read posture (everything readable, only writes are scoped), and the server already
 * enforces the write side — `POST`/`DELETE` both call `requireConfigurator`. Hiding the
 * roster from a plain member would buy no privacy the API doesn't already give away,
 * and would cost them the one thing the timeline now makes them ask: who is in this
 * team, and therefore who can move this bar.
 */
export function MembersSection({
  teamId,
  onError,
  canConfigure,
}: {
  teamId: string;
  onError: (message: string | null) => void;
  canConfigure: boolean;
}) {
  const members = useTeamMembers(teamId);
  const people = usePeople();
  const add = useAddTeamMember(teamId);
  const remove = useRemoveTeamMember(teamId);
  const [userId, setUserId] = useState("");
  const [role, setRole] = useState<MemberRole>("member");

  const candidates = useMemo(() => {
    const already = new Set((members.data ?? []).map((row) => row.user.id));
    return (people.data ?? [])
      .filter((person) => !already.has(person.id))
      .sort((a, b) => a.displayName.localeCompare(b.displayName));
  }, [members.data, people.data]);

  const submit = () => {
    if (!userId) return;
    onError(null);
    add.mutate(
      { userId, role },
      {
        onSuccess: () => setUserId(""),
        onError: (error) => onError(message(error)),
      },
    );
  };

  const handleRemove = (removedUserId: string) => {
    onError(null);
    remove.mutate(removedUserId, { onError: (error) => onError(message(error)) });
  };

  return (
    <div className="settings-group">
      {/* The same caption weight and tracking as `ui/group-label.tsx`'s `GroupLabel`,
          spelled out rather than reused verbatim: `GroupLabel`'s own `pt-group
          px-row-x` spacing is a sidebar-row concern, and would misalign this label
          inside `.settings-group`'s own flow. `.settings-label` (globals.css) is
          gone — it shipped `font-weight: 600`, the one bold in an interface that
          has none, and a `0.06em` tracking the rest of the branch doesn't use. */}
      <span className="text-11 font-medium tracking-[0.1em] text-faint uppercase">Members</span>

      {(members.data ?? []).map((row) => (
        <div className="settings-row" key={row.user.id}>
          <span>
            {row.user.displayName}
            <span className="settings-note"> · {ROLE_LABELS[row.role]}</span>
          </span>
          {canConfigure && (
            <Button
              type="button"
              variant="outline"
              size="sm"
              disabled={remove.isPending}
              onClick={() => handleRemove(row.user.id)}
            >
              Remove
            </Button>
          )}
        </div>
      ))}
      {members.isError ? (
        // Not the same sentence as genuine emptiness: "no members" is read as "open to
        // everyone" now that `TicketAccess` means it, and a failed fetch must never be
        // mistaken for that answer.
        <span className="settings-note error">Members could not be loaded — {message(members.error)}</span>
      ) : (
        members.data?.length === 0 && (
          <span className="settings-note">
            No members yet — anyone in the instance can edit this team&rsquo;s tickets.
          </span>
        )
      )}

      {canConfigure && (
        <div className="settings-row">
          <select value={userId} onChange={(event) => setUserId(event.target.value)}>
            <option value="">Add a member…</option>
            {candidates.map((person) => (
              <option key={person.id} value={person.id}>
                {person.displayName}
              </option>
            ))}
          </select>
          <select value={role} onChange={(event) => setRole(event.target.value as MemberRole)}>
            <option value="member">{ROLE_LABELS.member}</option>
            <option value="admin">{ROLE_LABELS.admin}</option>
          </select>
          <Button type="button" size="sm" disabled={!userId || add.isPending} onClick={submit}>
            Add
          </Button>
        </div>
      )}
    </div>
  );
}
