"use client";

import { useState } from "react";
import { ApiError, mcpAddCommand, unrecognisedScopeNote } from "@/lib/api";
import { useAuthMode, useGrants, useRevokeGrant } from "@/lib/queries";
import { useApiOrigin } from "@/lib/use-api-origin";
import { SettingsFormField, SettingsInline, SettingsNote } from "./field";

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

/**
 * A copy of `people-section.tsx`'s `CopyableLink`, deliberately not shared with it.
 *
 * The two look alike and are not the same thing: that one shows a secret that exists once
 * and can never be displayed again, this one a command that is the same on every instance
 * and can be typed by hand. Folding them together would put the invitation's "shown once"
 * caveat one prop away from a field where it is false.
 */
function CopyableCommand({ command }: { command: string }) {
  const [copied, setCopied] = useState(false);

  return (
    <SettingsInline>
      <input
        readOnly
        className="flex-1 min-w-[180px] text-12"
        style={{ fontFamily: "var(--font-mono)" }}
        value={command}
        onFocus={(event) => event.currentTarget.select()}
      />
      <button
        className="button"
        onClick={() => {
          void navigator.clipboard?.writeText(command).then(() => setCopied(true));
        }}
      >
        {copied ? "Copied" : "Copy"}
      </button>
    </SettingsInline>
  );
}

/**
 * What holds a key to this account, and the button that takes it back.
 *
 * Every member gets this section, admin or not: a grant is made by one person about their
 * own account, and nobody else — an admin included — appears in the list or can revoke
 * from it. That is enforced by the server, which never accepts a member as a parameter;
 * this file simply has nothing to hide.
 *
 * Empty is the common case, so the empty state is the section rather than a footnote in
 * it: the one command that connects an agent, printed to be copied.
 */
export function AgentsSection() {
  const authMode = useAuthMode();
  const grants = useGrants();
  const revoke = useRevokeGrant();
  const connected = grants.data ?? [];
  // The command is drawn into the HTML, so the origin arrives through the hook rather
  // than from `mcpAddCommand`'s own default: a prerender and a browser must agree.
  const origin = useApiOrigin();

  return (
    <section className="flex flex-col">
      <h2 className="mb-4 text-21 font-medium tracking-tight">Agents</h2>

      {connected.length > 0 && (
        <SettingsFormField>
          <span className="text-13 font-medium">Connected applications</span>
          <ul className="flex flex-col gap-px overflow-hidden rounded-md border border-border">
            {connected.map((grant) => (
              <li
                key={grant.clientId}
                className="flex items-center gap-3 border-b border-border bg-card px-3 py-2 last:border-b-0"
              >
                <span className="flex min-w-0 flex-1 flex-col gap-px leading-tight">
                  {grant.clientName}
                  <SettingsNote>
                    Connected {new Date(grant.grantedAt).toLocaleDateString()}
                  </SettingsNote>
                  {/*
                   * The consent screen's own sentences, sent by the server. A member
                   * revoking something should read what they agreed to, not a shorter
                   * paraphrase written on this side of the wire.
                   */}
                  {grant.scopeProse.map((sentence) => (
                    <SettingsNote key={sentence}>{sentence}</SettingsNote>
                  ))}
                  {/*
                   * A permission the server holds but has no words for. Shown as a count
                   * and never as the scope itself — the server does not send the string,
                   * so there is nothing here that a stranger wrote. Marked `error`
                   * because a grant nobody can explain is the one row on this screen
                   * worth looking twice at.
                   */}
                  {unrecognisedScopeNote(grant.unrecognisedScopes) && (
                    <SettingsNote error>
                      {unrecognisedScopeNote(grant.unrecognisedScopes)}
                    </SettingsNote>
                  )}
                </span>
                <button
                  className="button"
                  disabled={revoke.isPending}
                  onClick={() => revoke.mutate(grant.clientId)}
                >
                  Revoke
                </button>
              </li>
            ))}
          </ul>
          <SettingsNote>
            Revoking stops the application at once — the tokens it is holding go with the
            permission, so its next request is refused rather than its next hour.
          </SettingsNote>
          {revoke.isError && <SettingsNote error>{message(revoke.error)}</SettingsNote>}
        </SettingsFormField>
      )}

      <SettingsFormField>
        <span className="text-13 font-medium">
          {connected.length > 0 ? "Connect another" : "Connect an agent"}
        </span>
        {authMode.data?.mode === "dev" ? (
          // Printing the command here would be printing a command that cannot work: with
          // identity asserted by a header there is no authorisation server to answer it.
          <SettingsNote>
            This instance identifies people by an unverified header (dev mode), so
            connecting an agent is disabled. It needs an identity provider to be worth
            anything.
          </SettingsNote>
        ) : (
          <>
            <CopyableCommand command={mcpAddCommand(origin)} />
            <SettingsNote>
              Run it wherever the agent lives. It opens this instance in a browser to ask
              you what to allow, and nothing is pasted anywhere — no key, no token.
            </SettingsNote>
            {/*
             * Said here because it is true here, and because the alternative is a member
             * following their agent's link into a blank page: the authorisation URL
             * answers a browser with no session a bare 401. Signed in already, in the
             * browser you are reading this in, is the common case — which is exactly why
             * the sentence above needs this one next to it rather than instead of it.
             */}
            <SettingsNote>
              Sign in to Kanso in the browser it opens first. Without a session the
              authorisation page has nothing to show you and nowhere to send you.
            </SettingsNote>
          </>
        )}
      </SettingsFormField>

      {grants.isError && <SettingsNote error>{message(grants.error)}</SettingsNote>}
    </section>
  );
}
