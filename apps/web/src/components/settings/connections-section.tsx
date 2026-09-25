"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";
import { ImportDialog } from "@/components/inbox/import-dialog";
import { ApiError, api, type SetupState } from "@/lib/api";
import { forgetCallback } from "@/lib/forget-callback";
import { readGoogleClientFile, redirectUriProblem } from "@/lib/google-client-file";
import { keys, useSyncStatus } from "@/lib/queries";
import { useApiOrigin } from "@/lib/use-api-origin";
import { NotionConnect } from "@/components/setup/notion-connect";
import { NotionPageField } from "@/components/setup/notion-page-field";
import { SettingsInline, SettingsNote } from "./field";

/**
 * Spring registers Google's callback under a fixed path, so the URI is derivable rather
 * than configurable — and it has to match Google's entry character for character, which
 * is why it is both printed to copy and compared against a pasted client file.
 */
const GOOGLE_CALLBACK_PATH = "/login/oauth2/code/google";

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

  // Inside the component, because the origin is the page's own and `next build`
  // prerenders this file: a `window` read at module scope would fail the build.
  const googleRedirectUri = `${useApiOrigin()}${GOOGLE_CALLBACK_PATH}`;

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
    forgetCallback("connections");
  }, [connected, connectError, queryClient]);

  const [token, setToken] = useState("");
  /**
   * The paste is the fallback, not the way in — and `Test connection` folds with it. It
   * was written to catch a typo in a token, and with consent as the way in there is no
   * token to mistype; beside the field it is still the right button, on the screen it is
   * one more control to read past.
   */
  const [pasting, setPasting] = useState(state.notion.managedByEnvironment);
  const [parentPageId, setParentPageId] = useState(state.notion.parentPageId ?? "");
  const [clientId, setClientId] = useState(state.google.clientId ?? "");
  const [clientSecret, setClientSecret] = useState("");
  /** What the pasted client file said about its own redirect URIs, if it said anything. */
  const [googleFileNote, setGoogleFileNote] = useState<string | null>(null);
  const [importing, setImporting] = useState(false);

  const test = useMutation({
    mutationFn: () =>
      api.testNotion({ token: token.trim() || undefined, parentPageId: parentPageId.trim() }),
  });
  /**
   * Saving the page is what creates the databases.
   *
   * Two requests rather than one endpoint doing both: `POST /api/setup/notion` would
   * become eight Notion round trips deep, and every MCP caller would inherit a latency it
   * never asked for. Chained here, each failure lands on the card that caused it.
   *
   * Guarded on the answer rather than on the props: `save` returns the state it just
   * wrote, and that is the only reading that knows whether the page it stored is new.
   */
  const saveNotion = useMutation({
    mutationFn: async () => {
      const next = await api.saveNotion({
        token: token.trim() || undefined,
        parentPageId: parentPageId.trim(),
      });
      if (next.notion.parentPageId && !next.notion.bootstrapped) await api.bootstrapNotion();
      return next;
    },
    onSuccess: (next) => {
      setToken("");
      refresh(next);
    },
    /**
     * Invalidated here rather than in `onSuccess`: the save can land and the bootstrap
     * that follows it can still throw, and a chain that stops at the first failure must
     * not also stop the cache from finding out. The page is saved either way — the
     * request that changed the server already succeeded — so the note under these
     * buttons has to be read off what is actually stored, not off whichever half of the
     * chain last returned. The error itself still surfaces through `saveNotion.isError`;
     * this only decides what the *other* fields on screen show while that error is up.
     */
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: keys.setupState });
      queryClient.invalidateQueries({ queryKey: keys.sync });
      queryClient.invalidateQueries({ queryKey: keys.syncDetail });
    },
  });
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
  const testGoogle = useMutation({
    mutationFn: () =>
      api.testGoogle({
        clientId: clientId.trim() || undefined,
        clientSecret: clientSecret.trim() || undefined,
      }),
  });

  /**
   * One paste of the JSON Google Cloud downloads instead of two transcriptions. Anything
   * that is not a client file falls through unchanged, so typing an id still works — and
   * the JSON never stays in the id box, because a field holding a whole file looks broken.
   */
  const takeGoogleClientId = (value: string) => {
    const file = readGoogleClientFile(value);
    if (!file) {
      setClientId(value);
      return;
    }
    setClientId(file.clientId);
    if (file.clientSecret) setClientSecret(file.clientSecret);
    setGoogleFileNote(redirectUriProblem(file, googleRedirectUri));
  };

  const notionLocked = state.notion.managedByEnvironment || !canConfigure;
  const googleLocked = state.google.managedByEnvironment || !canConfigure;

  // The count stays beside the connection that produced the failures, because a member's
  // `See the queue` lands here: the rows and Notion's reasons are the configurator's, on
  // the sync queue section.
  const sync = useSyncStatus();
  const failedCount = sync.data?.jobs.failed ?? 0;

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

        {/* One component regardless of whether this is the first connection or the
            fourth — connecting is the same act either time, so there is nothing to draw
            twice. See `notion-connect.tsx` for why it still lives under `components/setup/`
            with one caller. */}
        {canConfigure && <NotionConnect state={state} onState={refresh} />}

        {canConfigure && (
          <>
            <button
              type="button"
              className="text-11 underline text-faint hover:text-foreground"
              onClick={() => setPasting((open) => !open)}
            >
              {pasting ? "Hide the token field" : "Paste an integration token instead"}
            </button>
            {pasting && (
              <input
                id="notion-token"
                aria-label="Integration token"
                className="w-full max-w-[380px]"
                type="password"
                autoComplete="off"
                disabled={notionLocked}
                placeholder={
                  state.notion.configured
                    ? "Stored — leave empty to keep it"
                    : "Integration token"
                }
                value={token}
                onChange={(event) => setToken(event.target.value)}
              />
            )}
            {/* Choosing the parent page is one act whether it is being done right after
                connecting or changed a month later, so it is one component too — see
                `notion-page-field.tsx` for why it stays beside `NotionConnect`. */}
            <div className="max-w-[380px]">
              <NotionPageField
                value={parentPageId}
                onChange={setParentPageId}
                token={token}
                disabled={notionLocked}
              />
            </div>
            <SettingsInline>
              {pasting && (
                <button
                  className="button"
                  disabled={notionLocked || test.isPending}
                  onClick={() => test.mutate()}
                >
                  Test connection
                </button>
              )}
              <button
                className="button button-primary"
                disabled={notionLocked || saveNotion.isPending || !parentPageId.trim()}
                onClick={() => saveNotion.mutate()}
              >
                {saveNotion.isPending ? "Saving…" : "Save"}
              </button>
            </SettingsInline>
            {test.data && <SettingsNote error={!test.data.ok}>{test.data.detail}</SettingsNote>}
            {test.isError && <SettingsNote error>{message(test.error)}</SettingsNote>}
            {saveNotion.isError && <SettingsNote error>{message(saveNotion.error)}</SettingsNote>}
            <SettingsNote>
              {state.notion.bootstrapped
                ? "The four mirrored databases exist."
                : state.notion.configured
                  ? "Choose the page Kanso creates its four databases under, then save."
                  : "Not connected. Tickets live in Postgres only."}
            </SettingsNote>

            {/* Screen 24's way in. The import reads Notion and writes Kanso, which is the
                opposite direction to everything else in this card — so it is a button
                that opens a two-screen dialog rather than another field. */}
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
       * How many writes the mirror refused, and for a configurator the way to which and why.
       *
       * A member's `See the queue` on a refused push lands here, so the count has to be
       * readable without the rows. Drawn only when something has failed: an empty queue is
       * not news, and a permanent "0 failed" row is one more line to read past.
       */}
      {failedCount > 0 && (
        <div
          data-testid="mirror-queue"
          className="flex flex-col gap-2.5 rounded-lg bg-card p-4"
        >
          <div className="flex items-center gap-2.5">
            <span className="flex-1 text-13 font-medium">The mirror refused these writes</span>
            <span className="text-11 text-urgent">{failedCount}</span>
          </div>

          {/* Said rather than left blank: a heading over nothing reads as a screen that
              failed to load, and the reason it is empty is a rule, not an error. */}
          {!canConfigure && (
            <SettingsNote>
              Which pages, and what Notion said about them, is the configurator&apos;s to read.
            </SettingsNote>
          )}

          {canConfigure && (
            <SettingsInline>
              {/* One home for the failures: the rows and their reasons moved to the queue
                  section, and this card keeps the count a member can read too. */}
              <Link className="button" href="/settings?section=sync-queue">
                Open the sync queue
              </Link>
            </SettingsInline>
          )}
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
              onChange={(event) => takeGoogleClientId(event.target.value)}
            />
            <SettingsNote>
              Or paste the whole JSON file Google Cloud downloads for the client — it fills
              in the secret too.
            </SettingsNote>
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
            {/* The file knows which redirect URIs its client was created with, so a
                missing one is worth saying at paste time: the credential check speaks to
                Google's token endpoint, which cannot see it. */}
            {googleFileNote && <SettingsNote error>{googleFileNote}</SettingsNote>}
            <SettingsInline>
              <button
                className="button"
                disabled={googleLocked || testGoogle.isPending}
                onClick={() => testGoogle.mutate()}
              >
                Test connection
              </button>
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
            {testGoogle.data && (
              <SettingsNote error={!testGoogle.data.ok}>{testGoogle.data.detail}</SettingsNote>
            )}
            {testGoogle.isError && <SettingsNote error>{message(testGoogle.error)}</SettingsNote>}
            {saveGoogle.isError && <SettingsNote error>{message(saveGoogle.error)}</SettingsNote>}
            <SettingsNote>
              Authorised redirect URI to paste into Google Cloud:{" "}
              <code className="rounded-sm bg-accent px-1 py-0.5" style={{ fontFamily: "var(--font-mono)" }}>
                {googleRedirectUri}
              </code>
            </SettingsNote>
          </>
        )}
      </div>
    </section>
  );
}
