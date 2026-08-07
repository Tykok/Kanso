"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api, type InstanceRole } from "@/lib/api";
import { keys, usePendingInvitations, usePeople } from "@/lib/queries";

const ROLE_LABELS: Record<InstanceRole, string> = {
  owner: "Owner",
  admin: "Admin",
  member: "User",
};

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

function CopyableLink({ url }: { url: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <div className="settings-inline">
      <input readOnly value={url} onFocus={(event) => event.currentTarget.select()} />
      <button
        className="button"
        onClick={() => {
          void navigator.clipboard?.writeText(url).then(() => setCopied(true));
        }}
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </div>
  );
}

/**
 * Roles and invitations. Only reachable by the owner and admins — a member sees
 * neither the controls nor the pending links.
 */
export function PeopleSection() {
  const queryClient = useQueryClient();
  const people = usePeople();
  const invitations = usePendingInvitations(true);
  const [issued, setIssued] = useState<string | null>(null);
  const [email, setEmail] = useState("");
  const [role, setRole] = useState<InstanceRole>("member");

  const setUserRole = useMutation({
    mutationFn: ({ id, next }: { id: string; next: InstanceRole }) => api.setRole(id, next),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: keys.people });
      queryClient.invalidateQueries({ queryKey: keys.me });
    },
  });

  const invite = useMutation({
    mutationFn: () => api.createInvitation({ email: email.trim() || undefined, role }),
    onSuccess: (link) => {
      setIssued(link.url);
      setEmail("");
      queryClient.invalidateQueries({ queryKey: keys.invitations });
    },
  });

  const revoke = useMutation({
    mutationFn: api.revokeInvitation,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: keys.invitations }),
  });

  return (
    <section className="settings-section">
      <h2>People</h2>

      <div className="settings-field">
        <label>Members</label>
        <ul className="people-list">
          {(people.data ?? []).map((person) => (
            <li key={person.id}>
              <span className="people-name">
                {person.displayName}
                <span className="settings-note">{person.email}</span>
              </span>

              {person.instanceRole === "owner" ? (
                // Ownership is not handed over from a dropdown: that is how an
                // instance ends up with nobody able to configure it.
                <span className="people-role">Owner</span>
              ) : (
                <select
                  value={person.instanceRole}
                  disabled={setUserRole.isPending}
                  onChange={(event) =>
                    setUserRole.mutate({ id: person.id, next: event.target.value as InstanceRole })
                  }
                >
                  <option value="admin">{ROLE_LABELS.admin}</option>
                  <option value="member">{ROLE_LABELS.member}</option>
                </select>
              )}
            </li>
          ))}
        </ul>
        <span className="settings-note">
          An admin can configure the instance and invite people. A user can do neither.
        </span>
        {setUserRole.isError && (
          <span className="settings-note error">{message(setUserRole.error)}</span>
        )}
      </div>

      <div className="settings-field">
        <label htmlFor="invite-email">Invite someone</label>
        <div className="settings-inline">
          <input
            id="invite-email"
            placeholder="Email (optional)"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <select value={role} onChange={(event) => setRole(event.target.value as InstanceRole)}>
            <option value="member">{ROLE_LABELS.member}</option>
            <option value="admin">{ROLE_LABELS.admin}</option>
          </select>
          <button
            className="button button-primary"
            disabled={invite.isPending}
            onClick={() => invite.mutate()}
          >
            Create link
          </button>
        </div>
        <span className="settings-note">
          With an address the link only works for that person; without one it works for whoever
          opens it. No email is sent — copy the link and pass it on yourself.
        </span>
        {invite.isError && <span className="settings-note error">{message(invite.error)}</span>}
        {issued && (
          <>
            <CopyableLink url={issued} />
            <span className="settings-note">
              Shown once. Nothing can display it again — the server keeps only a hash.
            </span>
          </>
        )}
      </div>

      {(invitations.data ?? []).length > 0 && (
        <div className="settings-field">
          <label>Pending invitations</label>
          <ul className="people-list">
            {(invitations.data ?? []).map((invitation) => (
              <li key={invitation.id}>
                <span className="people-name">
                  {invitation.email ?? "Anyone with the link"}
                  <span className="settings-note">
                    {ROLE_LABELS[invitation.role]} ·{" "}
                    {invitation.expired
                      ? "expired"
                      : `expires ${new Date(invitation.expiresAt).toLocaleDateString()}`}
                  </span>
                </span>
                <button
                  className="button"
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(invitation.id)}
                >
                  Revoke
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  );
}
