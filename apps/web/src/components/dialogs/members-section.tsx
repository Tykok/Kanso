"use client";

import { useMemo, useState } from "react";
import { ApiError, type MemberRole } from "@/lib/api";
import { useAddTeamMember, useRemoveTeamMember, usePeople, useTeamMembers } from "@/lib/queries";

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
 */
export function MembersSection({
  teamId,
  onError,
}: {
  teamId: string;
  onError: (message: string | null) => void;
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
      <span className="settings-label">Members</span>

      {(members.data ?? []).map((row) => (
        <div className="settings-row" key={row.user.id}>
          <span>
            {row.user.displayName}
            <span className="settings-note"> · {ROLE_LABELS[row.role]}</span>
          </span>
          <button
            type="button"
            className="button"
            disabled={remove.isPending}
            onClick={() => handleRemove(row.user.id)}
          >
            Remove
          </button>
        </div>
      ))}
      {members.data?.length === 0 && <span className="settings-note">No members yet.</span>}

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
        <button
          type="button"
          className="button"
          disabled={!userId || add.isPending}
          onClick={submit}
        >
          Add
        </button>
      </div>
    </div>
  );
}
