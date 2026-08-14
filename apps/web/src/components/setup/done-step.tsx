"use client";

import { useMutation } from "@tanstack/react-query";
import { useRouter } from "next/navigation";
import type { ReactNode } from "react";
import { api, type Me, type Preferences, type SetupState } from "@/lib/api";
import { CopyRow, messageFor } from "./fields";
import { FormCard } from "./frame";

type Props = {
  head: ReactNode;
  state: SetupState;
  me?: Me;
  preferences: Preferences;
  canInvite: boolean;
  onBack?: () => void;
};

export function DoneStep({ head, state, me, preferences, canInvite, onBack }: Props) {
  const router = useRouter();
  const invite = useMutation({ mutationFn: () => api.createInvitation({}) });

  const notion = !state.notion.configured
    ? "Not connected. Tickets live in Postgres only."
    : state.notion.bootstrapped
      ? "Connected, databases created."
      : "Connected, databases not created yet.";

  const google = state.google.configured
    ? "Enabled."
    : "Not configured. Sign in with email and password.";

  return (
    <FormCard
      head={head}
      title="Ready"
      intro="Nothing here is final — every one of these is reachable again from settings."
      primaryLabel="Open Kanso"
      onBack={onBack}
      onSubmit={() => router.push("/")}
    >
      <dl className="m-0 grid grid-cols-[auto_1fr] items-baseline gap-x-3.5 gap-y-2">
        {me && (
          <>
            <dt className="text-11 uppercase tracking-wide text-faint">Account</dt>
            <dd className="m-0 text-13 text-muted-foreground">
              {me.user.displayName} · {me.user.email} · {me.user.instanceRole}
            </dd>
          </>
        )}

        <dt className="text-11 uppercase tracking-wide text-faint">Notion</dt>
        <dd className="m-0 text-13 text-muted-foreground">{notion}</dd>

        <dt className="text-11 uppercase tracking-wide text-faint">Google</dt>
        <dd className="m-0 text-13 text-muted-foreground">{google}</dd>

        <dt className="text-11 uppercase tracking-wide text-faint">Preferences</dt>
        <dd className="m-0 text-13 text-muted-foreground">
          {preferences.theme} theme · {preferences.accent} · {preferences.density}
        </dd>
      </dl>

      {canInvite && (
        <div className="flex flex-col gap-1.5">
          <span className="text-11 uppercase tracking-wide text-faint">Invite someone</span>
          {invite.data ? (
            <>
              <CopyRow value={invite.data.url} />
              <span className="text-11 text-faint">
                Single use, expires {new Date(invite.data.expiresAt).toLocaleString()}. There is
                no mail server in the box — send the link yourself.
              </span>
            </>
          ) : (
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                className="button"
                disabled={invite.isPending}
                onClick={() => invite.mutate()}
              >
                {invite.isPending ? "Creating…" : "Create an invitation link"}
              </button>
              <span className="text-11 text-faint">A link to copy and pass on. No email is sent.</span>
            </div>
          )}
          {invite.error && <span className="text-12 text-urgent">{messageFor(invite.error)}</span>}
        </div>
      )}
    </FormCard>
  );
}
