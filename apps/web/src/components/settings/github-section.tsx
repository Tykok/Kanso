"use client";

import { useEffect, useState } from "react";
import { useSearchParams } from "next/navigation";
import { useQueryClient } from "@tanstack/react-query";
import { ApiError } from "@/lib/api";
import {
  githubKeys,
  useGithubLink,
  useSaveGithubApp,
  useStartGithubLink,
  useUnlinkGithub,
} from "@/lib/queries";
import { useApiOrigin } from "@/lib/use-api-origin";
import {
  githubLinkAction,
  githubLinkDetail,
  githubLinkStage,
  githubLinkSummary,
} from "../github/link-copy";
import { SettingsFormField, SettingsInline, SettingsNote } from "./field";

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

/**
 * A member's own GitHub account, and — for an owner — the App it is asked through.
 *
 * Its own section rather than a card inside `ConnectionsSection`, and for two reasons that
 * are both about audience. The first is the one `NotionPeopleSection` gives: that file is
 * already 390 lines about a different subject. The second is stronger — everything in
 * Connections is *instance* configuration that only an owner can change, and the main
 * gesture here belongs to whoever is reading it. A member with no admin rights has
 * something to do on this screen, which is not true of any other card in that section.
 *
 * It is also deliberately not folded into `AccountSection`, where it would sit beside
 * "Linked to github" — the OIDC *sign-in* provider. Two different GitHub links, one screen,
 * and a reader who conflated them would think unlinking their sign-in had removed their
 * name from the feed. Two sections keeps the two questions apart.
 */
export function GithubSection({ canConfigure }: { canConfigure: boolean }) {
  const link = useGithubLink();
  const start = useStartGithubLink();
  const unlink = useUnlinkGithub();
  const saveApp = useSaveGithubApp();
  const queryClient = useQueryClient();

  const params = useSearchParams();
  const linked = params.get("github_linked");
  const linkError = params.get("github_error");

  /**
   * The callback's answer, read off the query string the API's redirect put there and then
   * removed from the URL.
   *
   * The same shape `ConnectionsSection` uses for `notion_connected`, including the
   * `replaceState`: without it the note survives a reload and a soft navigation back here,
   * so a member who connected on Tuesday reads "Connected" again on Thursday. The
   * invalidation is what turns the redirect into a fresh answer — the browser arrived from
   * GitHub, so this tab's cache predates the row that now exists.
   */
  useEffect(() => {
    if (linked === null && linkError === null) return;
    void queryClient.invalidateQueries({ queryKey: githubKeys.link });
    window.history.replaceState(null, "", window.location.pathname);
  }, [linked, linkError, queryClient]);

  const [clientId, setClientId] = useState("");
  const [clientSecret, setClientSecret] = useState("");
  const [confirming, setConfirming] = useState(false);

  return (
    <section className="flex flex-col">
      <h2 className="mb-6 text-21 font-medium tracking-tight">GitHub</h2>

      {/* Drawn before the data arrives rather than behind a spinner: the heading and the
          two notes below are the whole screen for an instance with no App, and a loading
          state that replaces them flashes on every visit for no information. */}
      {linked !== null && (
        <SettingsNote>
          {linked ? `Connected as @${linked}.` : "Your GitHub account is connected."}
        </SettingsNote>
      )}
      {linkError !== null && <SettingsNote error>{linkError}</SettingsNote>}

      {link.isError && <SettingsNote error>{message(link.error)}</SettingsNote>}

      {link.data && (
        <>
          <SettingsFormField>
            <SettingsInline>
              <span className="flex-1 text-13 font-medium">Your GitHub account</span>
              <LinkBadge stage={githubLinkStage(link.data)} />
            </SettingsInline>
            <SettingsNote>{githubLinkSummary(link.data)}</SettingsNote>
            {githubLinkDetail(link.data) && (
              <SettingsNote>{githubLinkDetail(link.data)}</SettingsNote>
            )}

            <SettingsInline>
              {githubLinkAction(link.data) && (
                <button
                  className="button"
                  disabled={start.isPending}
                  onClick={() => start.mutate()}
                >
                  {start.isPending ? "Opening GitHub…" : githubLinkAction(link.data)}
                </button>
              )}
              {/* A second press, not a modal. `people-section.tsx`'s shape: unlinking is
                  reversible — the member can walk the consent screen again — so it does not
                  earn a dialog, but it does change what their name looks like on future
                  feed lines, so it does not happen on one click either. */}
              {link.data.linked &&
                (confirming ? (
                  <>
                    <button
                      className="button"
                      disabled={unlink.isPending}
                      onClick={() => {
                        unlink.mutate(undefined, { onSettled: () => setConfirming(false) });
                      }}
                    >
                      {unlink.isPending ? "Unlinking…" : "Really unlink"}
                    </button>
                    <button className="button" onClick={() => setConfirming(false)}>
                      Keep it
                    </button>
                  </>
                ) : (
                  <button className="button" onClick={() => setConfirming(true)}>
                    Unlink
                  </button>
                ))}
            </SettingsInline>

            {start.isError && <SettingsNote error>{message(start.error)}</SettingsNote>}
            {unlink.isError && <SettingsNote error>{message(unlink.error)}</SettingsNote>}

            {/* Said once, on the card that could otherwise be read as a threat. Nothing
                about a member's history changes when they unlink: `activity.actor_id`
                points at `users`, not at `github_accounts`, so what is already attributed
                stays attributed. */}
            {link.data.linked && (
              <SettingsNote>
                Unlinking stops Kanso naming you on new pull requests. What it has already
                recorded keeps your name.
              </SettingsNote>
            )}
          </SettingsFormField>

          {canConfigure && (
            <SettingsFormField>
              <span className="text-13 font-medium">The GitHub App</span>
              <SettingsNote>
                The App members consent through — not the one that signs people in. Create a
                GitHub App, point its callback at the URI below, and paste its client id and
                secret here.
              </SettingsNote>
              {link.data.appManagedByEnvironment ? (
                <SettingsNote>
                  Set by the environment (KANSO_GITHUB_CLIENT_ID), so it cannot be changed
                  here.
                </SettingsNote>
              ) : (
                <>
                  <SettingsInline>
                    <input
                      aria-label="GitHub App client id"
                      className="min-w-[180px] flex-1 text-13"
                      autoComplete="off"
                      placeholder="Iv23li…"
                      value={clientId}
                      onChange={(event) => setClientId(event.target.value)}
                    />
                    <input
                      aria-label="GitHub App client secret"
                      className="min-w-[180px] flex-1 text-13"
                      type="password"
                      autoComplete="off"
                      placeholder={
                        link.data.appConfigured ? "Stored — leave empty to keep it" : "Client secret"
                      }
                      value={clientSecret}
                      onChange={(event) => setClientSecret(event.target.value)}
                    />
                    <button
                      className="button"
                      disabled={clientId.trim().length === 0 || saveApp.isPending}
                      onClick={() => {
                        saveApp.mutate(
                          {
                            clientId: clientId.trim(),
                            clientSecret: clientSecret.trim() || undefined,
                          },
                          // The secret is cleared on success and never on failure: a
                          // rejected save that also emptied the field would make the
                          // person type it again to find out what was wrong with it.
                          { onSuccess: () => setClientSecret("") },
                        );
                      }}
                    >
                      {saveApp.isPending ? "Saving…" : "Save"}
                    </button>
                  </SettingsInline>
                  {saveApp.isError && <SettingsNote error>{message(saveApp.error)}</SettingsNote>}
                </>
              )}
              <CallbackUri />
            </SettingsFormField>
          )}
        </>
      )}
    </section>
  );
}

/** Four stages, four words, and no fallback — a fifth stage is a type error here. */
function LinkBadge({ stage }: { stage: ReturnType<typeof githubLinkStage> }) {
  const copy: Record<ReturnType<typeof githubLinkStage>, { label: string; tone: string }> = {
    "no-app": { label: "unavailable", tone: "bg-accent text-muted-foreground" },
    offer: { label: "not linked", tone: "bg-accent text-muted-foreground" },
    linked: { label: "linked", tone: "bg-status-done/15 text-status-done" },
    reconnect: { label: "expired", tone: "bg-urgent/15 text-urgent" },
  };
  const { label, tone } = copy[stage];
  return (
    <span
      className={`inline-flex h-[22px] shrink-0 items-center rounded-sm px-1.5 text-11 uppercase tracking-wide ${tone}`}
    >
      {label}
    </span>
  );
}

/**
 * The callback URI to register on the App, selectable and copyable.
 *
 * Derived from the page's own origin on this side, which is the same origin the server
 * derives it from per request — so what is shown is what the server will send. Read-only
 * input rather than a `<code>`, and selecting from offset 0, the pair of fixes
 * `selectFromTheStart` and `BranchToCopy` both document: a caret left at the end scrolls a
 * long URI past its own scheme and host, which is the part somebody is checking.
 *
 * Through `useApiOrigin` and not `window.location` directly: this string is drawn into the
 * HTML, and `next build` prerenders it with no window at all.
 */
function CallbackUri() {
  const value = `${useApiOrigin()}/api/github/link/callback`;
  const [copied, setCopied] = useState(false);

  return (
    <>
      <SettingsNote>Callback URL to register on the App</SettingsNote>
      <SettingsInline>
        <input
          readOnly
          aria-label="GitHub App callback URL"
          className="min-w-[180px] flex-1 text-12"
          style={{ fontFamily: "var(--font-mono)" }}
          value={value}
          onFocus={(event) => {
            event.currentTarget.setSelectionRange(0, value.length, "backward");
            event.currentTarget.scrollLeft = 0;
          }}
        />
        <button
          className="button"
          onClick={() => {
            void navigator.clipboard?.writeText(value).then(() => setCopied(true));
          }}
        >
          {copied ? "Copied" : "Copy"}
        </button>
      </SettingsInline>
    </>
  );
}
