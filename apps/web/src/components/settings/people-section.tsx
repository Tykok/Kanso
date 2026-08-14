"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { ApiError, api, type InstanceRole } from "@/lib/api";
import { keys, usePendingInvitations, usePeople } from "@/lib/queries";
import { SettingsFormField, SettingsInline, SettingsNote } from "./field";

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
    <SettingsInline>
      <input
        readOnly
        className="flex-1 min-w-[180px] text-12"
        style={{ fontFamily: "var(--font-mono)" }}
        value={url}
        onFocus={(event) => event.currentTarget.select()}
      />
      <button
        className="button"
        onClick={() => {
          void navigator.clipboard?.writeText(url).then(() => setCopied(true));
        }}
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </SettingsInline>
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
    <section className="flex flex-col">
      <h2 className="mb-4 text-21 font-medium tracking-tight">People</h2>

      <SettingsFormField>
        <span className="text-13 font-medium">Members</span>
        <ul className="flex flex-col gap-px overflow-hidden rounded-md border border-border">
          {(people.data ?? []).map((person) => (
            <li
              key={person.id}
              className="flex items-center gap-3 border-b border-border bg-card px-3 py-2 last:border-b-0"
            >
              <span className="flex min-w-0 flex-1 flex-col gap-px leading-tight">
                {person.displayName}
                <SettingsNote>{person.email}</SettingsNote>
              </span>

              {person.instanceRole === "owner" ? (
                // Ownership is not handed over from a dropdown: that is how an
                // instance ends up with nobody able to configure it.
                <span className="text-11 uppercase tracking-wide text-faint">Owner</span>
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
        <SettingsNote>
          An admin can configure the instance and invite people. A user can do neither.
        </SettingsNote>
        {setUserRole.isError && <SettingsNote error>{message(setUserRole.error)}</SettingsNote>}
      </SettingsFormField>

      <SettingsFormField>
        <label htmlFor="invite-email" className="text-13 font-medium">
          Invite someone
        </label>
        <SettingsInline>
          <input
            id="invite-email"
            className="flex-1 min-w-[180px]"
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
        </SettingsInline>
        <SettingsNote>
          With an address the link only works for that person; without one it works for whoever
          opens it. No email is sent — copy the link and pass it on yourself.
        </SettingsNote>
        {invite.isError && <SettingsNote error>{message(invite.error)}</SettingsNote>}
        {issued && (
          <>
            <CopyableLink url={issued} />
            <SettingsNote>
              Shown once. Nothing can display it again — the server keeps only a hash.
            </SettingsNote>
          </>
        )}
      </SettingsFormField>

      {(invitations.data ?? []).length > 0 && (
        <SettingsFormField>
          <span className="text-13 font-medium">Pending invitations</span>
          <ul className="flex flex-col gap-px overflow-hidden rounded-md border border-border">
            {(invitations.data ?? []).map((invitation) => (
              <li
                key={invitation.id}
                className="flex items-center gap-3 border-b border-border bg-card px-3 py-2 last:border-b-0"
              >
                <span className="flex min-w-0 flex-1 flex-col gap-px leading-tight">
                  {invitation.email ?? "Anyone with the link"}
                  <SettingsNote>
                    {ROLE_LABELS[invitation.role]} ·{" "}
                    {invitation.expired
                      ? "expired"
                      : `expires ${new Date(invitation.expiresAt).toLocaleDateString()}`}
                  </SettingsNote>
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
        </SettingsFormField>
      )}
    </section>
  );
}
