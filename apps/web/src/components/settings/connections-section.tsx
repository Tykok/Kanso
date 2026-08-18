"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ImportDialog } from "@/components/inbox/import-dialog";
import { API_URL, ApiError, api, type SetupState } from "@/lib/api";
import { keys, useRetryFailedPushes, useSyncStatus } from "@/lib/queries";
import { NotionConnect } from "@/components/setup/notion-connect";
import { NotionPageField } from "@/components/setup/notion-page-field";
import { SettingsInline, SettingsNote } from "./field";

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

/** Only printed when there is something the badge alone does not say. */
function ManagedNote({ managed }: { managed: boolean }) {
  if (!managed) return null;
  return <SettingsNote>Set by the environment, so it cannot be changed here.</SettingsNote>;
}

/** A pill saying whether a connection is wired up — "configured" green, else neutral. */
function ConnectionBadge({ configured }: { configured: boolean }) {
  return (
    <span
      className={`inline-flex h-[22px] items-center rounded-sm px-1.5 text-11 uppercase tracking-wide ${
        configured
          ? "bg-status-done/15 text-status-done"
          : "bg-accent text-muted-foreground"
      }`}
    >
      {configured ? "configured" : "not configured"}
    </span>
  );
}

/**
 * Notion and Google for the whole instance.
 *
 * A member gets the same section read-only: they should be able to see why their
 * tickets appear in Notion without being able to change it for everyone.
 */
export function ConnectionsSection({
  state,
  canConfigure,
}: {
  state: SetupState;
  canConfigure: boolean;
}) {
  const queryClient = useQueryClient();
  const refresh = (next: SetupState) => queryClient.setQueryData(keys.setupState, next);

  /**
   * What the consent screen sent back.
   *
   * The callback is a browser redirect, so its answer arrives as a query parameter rather
   * than as a mutation result — and it has to be *said*. Coming back from Notion to a
   * screen that looks exactly as it did before is indistinguishable from nothing having
   * happened, which is the failure mode the button exists to remove. The parameter is
   * stripped once read so a reload does not re-announce a connection made ten minutes ago.
   */
  const params = useSearchParams();
  const connected = params.get("notion_connected");
  const connectError = params.get("notion_error");

  useEffect(() => {
    if (connected === null && connectError === null) return;
    queryClient.invalidateQueries({ queryKey: keys.setupState });
    window.history.replaceState(null, "", window.location.pathname);
  }, [connected, connectError, queryClient]);

  const [token, setToken] = useState("");
  const [parentPageId, setParentPageId] = useState(state.notion.parentPageId ?? "");
  const [clientId, setClientId] = useState(state.google.clientId ?? "");
  const [clientSecret, setClientSecret] = useState("");
  const [importing, setImporting] = useState(false);

  const test = useMutation({
    mutationFn: () =>
      api.testNotion({ token: token.trim() || undefined, parentPageId: parentPageId.trim() }),
  });
  const saveNotion = useMutation({
    mutationFn: () =>
      api.saveNotion({ token: token.trim() || undefined, parentPageId: parentPageId.trim() }),
    onSuccess: (next) => {
      setToken("");
      refresh(next);
    },
  });
  const bootstrap = useMutation({ mutationFn: api.bootstrapNotion, onSuccess: refresh });
  const saveGoogle = useMutation({
    mutationFn: () => api.saveGoogle({ clientId: clientId.trim(), clientSecret }),
    onSuccess: (next) => {
      setClientSecret("");
      refresh(next);
      // The provider list is rebuilt server-side without a restart, so the sign-in
      // screen has to be told to ask again.
      queryClient.invalidateQueries({ queryKey: keys.authMode });
    },
  });

  const notionLocked = state.notion.managedByEnvironment || !canConfigure;
  const googleLocked = state.google.managedByEnvironment || !canConfigure;

  // The queue is on this page because this is where the inbox's `See the queue` on a
  // refused push lands: the mirror's failures belong beside the connection that
  // produced them, not on a screen of their own.
  const sync = useSyncStatus();
  const retryPushes = useRetryFailedPushes();
  const failed = sync.data?.failed ?? [];

  return (
    <section className="flex flex-col gap-6">
      <h2 className="text-21 font-medium tracking-tight">Connections</h2>

      <div className="flex flex-col gap-2.5 rounded-lg bg-card p-4">
        <div className="flex items-center gap-2.5">
          <label htmlFor="notion-token" className="flex-1 text-13 font-medium">
            Notion
          </label>
          <ConnectionBadge configured={state.notion.configured} />
        </div>
        <ManagedNote managed={state.notion.managedByEnvironment} />

        {connected !== null && (
          <SettingsNote>
            {connected ? `Connected to ${connected}.` : "Notion is connected."}
          </SettingsNote>
        )}
        {connectError !== null && <SettingsNote error>{connectError}</SettingsNote>}

        {/* The same block the wizard draws. Connecting is the same act whether it is
            being done for the first time or the fourth, so it is the same component. */}
        {canConfigure && <NotionConnect state={state} onState={refresh} />}

        {canConfigure && (
          <>
            <input
              id="notion-token"
              className="w-full max-w-[380px]"
              type="password"
              autoComplete="off"
              disabled={notionLocked}
              placeholder={
                state.notion.configured ? "Stored — leave empty to keep it" : "Integration token"
              }
              value={token}
              onChange={(event) => setToken(event.target.value)}
            />
            {/* The same block the wizard draws, for the same reason `NotionConnect` is
                shared: choosing the parent page is one act, whether it is being done during
                setup or changed a month later. */}
            <div className="max-w-[380px]">
              <NotionPageField
                value={parentPageId}
                onChange={setParentPageId}
                token={token}
                disabled={notionLocked}
              />
            </div>
            <SettingsInline>
              <button
                className="button"
                disabled={notionLocked || test.isPending}
                onClick={() => test.mutate()}
              >
                Test connection
              </button>
              <button
                className="button button-primary"
                disabled={notionLocked || saveNotion.isPending || !parentPageId.trim()}
                onClick={() => saveNotion.mutate()}
              >
                Save
              </button>
              {state.notion.configured && !state.notion.bootstrapped && (
                <button
                  className="button"
                  disabled={bootstrap.isPending}
                  onClick={() => bootstrap.mutate()}
                >
                  Create the databases
                </button>
              )}
            </SettingsInline>
            {test.data && <SettingsNote error={!test.data.ok}>{test.data.detail}</SettingsNote>}
            {test.isError && <SettingsNote error>{message(test.error)}</SettingsNote>}
            {saveNotion.isError && <SettingsNote error>{message(saveNotion.error)}</SettingsNote>}
            {bootstrap.isError && <SettingsNote error>{message(bootstrap.error)}</SettingsNote>}
            <SettingsNote>
              {state.notion.bootstrapped
                ? "The four mirrored databases exist."
                : "The databases have not been created yet; nothing can be pushed until they are."}
            </SettingsNote>

            {/* Screen 24's way in. The import reads Notion and writes Kanso, which is the
                opposite direction to everything else in this card — so it is a button
                that opens a three-step dialog rather than another field. */}
            <SettingsInline>
              <button
                className="button"
                disabled={!state.notion.configured}
                onClick={() => setImporting(true)}
              >
                Import from Notion…
              </button>
            </SettingsInline>
          </>
        )}
      </div>

      {/*
       * The queue, named as a section rather than as a note.
       *
       * `See the queue` on a refused push in the inbox lands here, so this has to be
       * the place where "which writes did the mirror refuse, and why" is answerable.
       * Drawn only when something has failed: an empty queue is not news, and a
       * permanent "0 failed" row is one more line to read past on every visit.
       */}
      {failed.length > 0 && (
        <div
          data-testid="mirror-queue"
          className="flex flex-col gap-2.5 rounded-lg bg-card p-4"
        >
          <div className="flex items-center gap-2.5">
            <span className="flex-1 text-13 font-medium">The mirror refused these writes</span>
            <span className="text-11 text-urgent">{failed.length}</span>
          </div>

          <div className="flex flex-col gap-0.5 text-12">
            {failed.map((job) => (
              <div
                key={job.id}
                data-testid="failed-push"
                className="grid grid-cols-[80px_1fr] items-start gap-2.5 rounded-sm bg-background px-2.5 py-1.5"
              >
                <span className="font-mono text-11 text-faint">{job.entity}</span>
                <span className="text-muted-foreground">
                  {job.error ?? "No reason was recorded."}
                  <span className="text-faint">
                    {" "}
                    · {job.attempts} {job.attempts === 1 ? "attempt" : "attempts"}
                  </span>
                </span>
              </div>
            ))}
          </div>

          {canConfigure && (
            <SettingsInline>
              <button
                className="button"
                disabled={retryPushes.isPending}
                onClick={() => retryPushes.mutate()}
              >
                Retry all
              </button>
            </SettingsInline>
          )}
          <SettingsNote>
            Every push writes the whole row from Postgres, so retrying one that already
            partly landed cannot make the mirror worse.
          </SettingsNote>
        </div>
      )}

      {importing && <ImportDialog onClose={() => setImporting(false)} />}

      <div className="flex flex-col gap-2.5 rounded-lg bg-card p-4">
        <div className="flex items-center gap-2.5">
          <label htmlFor="google-client-id" className="flex-1 text-13 font-medium">
            Google sign-in
          </label>
          <ConnectionBadge configured={state.google.configured} />
        </div>
        <ManagedNote managed={state.google.managedByEnvironment} />
        {canConfigure && (
          <>
            <input
              id="google-client-id"
              className="w-full max-w-[380px]"
              disabled={googleLocked}
              placeholder="Client ID"
              value={clientId}
              onChange={(event) => setClientId(event.target.value)}
            />
            <input
              className="w-full max-w-[380px]"
              type="password"
              autoComplete="off"
              disabled={googleLocked}
              placeholder={
                state.google.configured ? "Stored — re-enter to change it" : "Client secret"
              }
              value={clientSecret}
              onChange={(event) => setClientSecret(event.target.value)}
            />
            <SettingsInline>
              <button
                className="button button-primary"
                disabled={googleLocked || saveGoogle.isPending || !clientId.trim() || !clientSecret}
                onClick={() => saveGoogle.mutate()}
              >
                Save
              </button>
              {saveGoogle.isSuccess && (
                <SettingsNote>Saved — the button appears without a restart.</SettingsNote>
              )}
            </SettingsInline>
            {saveGoogle.isError && <SettingsNote error>{message(saveGoogle.error)}</SettingsNote>}
            <SettingsNote>
              Authorised redirect URI to paste into Google Cloud:{" "}
              <code className="rounded-sm bg-accent px-1 py-0.5" style={{ fontFamily: "var(--font-mono)" }}>
                {API_URL}/login/oauth2/code/google
              </code>
            </SettingsNote>
          </>
        )}
      </div>
    </section>
  );
}
