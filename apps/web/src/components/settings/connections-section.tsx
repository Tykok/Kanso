"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { API_URL, ApiError, api, type SetupState } from "@/lib/api";
import { keys } from "@/lib/queries";

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

function StateLine({ configured, managed }: { configured: boolean; managed: boolean }) {
  return (
    <span className="settings-note">
      {configured ? "Configured" : "Not configured"}
      {managed && " · set by the environment, so it cannot be changed here"}
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

  const [token, setToken] = useState("");
  const [parentPageId, setParentPageId] = useState(state.notion.parentPageId ?? "");
  const [clientId, setClientId] = useState(state.google.clientId ?? "");
  const [clientSecret, setClientSecret] = useState("");

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

  return (
    <section className="settings-section">
      <h2>Connections</h2>

      <div className="settings-field">
        <label htmlFor="notion-token">Notion</label>
        <StateLine
          configured={state.notion.configured}
          managed={state.notion.managedByEnvironment}
        />
        {canConfigure && (
          <>
            <input
              id="notion-token"
              type="password"
              autoComplete="off"
              disabled={notionLocked}
              placeholder={
                state.notion.configured ? "Stored — leave empty to keep it" : "Integration token"
              }
              value={token}
              onChange={(event) => setToken(event.target.value)}
            />
            <input
              disabled={notionLocked}
              placeholder="Parent page id"
              value={parentPageId}
              onChange={(event) => setParentPageId(event.target.value)}
            />
            <div className="settings-inline">
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
            </div>
            {test.data && (
              <span className={`settings-note${test.data.ok ? "" : " error"}`}>
                {test.data.detail}
              </span>
            )}
            {test.isError && <span className="settings-note error">{message(test.error)}</span>}
            {saveNotion.isError && (
              <span className="settings-note error">{message(saveNotion.error)}</span>
            )}
            {bootstrap.isError && (
              <span className="settings-note error">{message(bootstrap.error)}</span>
            )}
            <span className="settings-note">
              {state.notion.bootstrapped
                ? "The four mirrored databases exist."
                : "The databases have not been created yet; nothing can be pushed until they are."}
            </span>
          </>
        )}
      </div>

      <div className="settings-field">
        <label htmlFor="google-client-id">Google sign-in</label>
        <StateLine
          configured={state.google.configured}
          managed={state.google.managedByEnvironment}
        />
        {canConfigure && (
          <>
            <input
              id="google-client-id"
              disabled={googleLocked}
              placeholder="Client ID"
              value={clientId}
              onChange={(event) => setClientId(event.target.value)}
            />
            <input
              type="password"
              autoComplete="off"
              disabled={googleLocked}
              placeholder={
                state.google.configured ? "Stored — re-enter to change it" : "Client secret"
              }
              value={clientSecret}
              onChange={(event) => setClientSecret(event.target.value)}
            />
            <div className="settings-inline">
              <button
                className="button button-primary"
                disabled={googleLocked || saveGoogle.isPending || !clientId.trim() || !clientSecret}
                onClick={() => saveGoogle.mutate()}
              >
                Save
              </button>
              {saveGoogle.isSuccess && (
                <span className="settings-note">Saved — the button appears without a restart.</span>
              )}
            </div>
            {saveGoogle.isError && (
              <span className="settings-note error">{message(saveGoogle.error)}</span>
            )}
            <span className="settings-note">
              Authorised redirect URI to paste into Google Cloud:{" "}
              <code>{API_URL}/login/oauth2/code/google</code>
            </span>
          </>
        )}
      </div>
    </section>
  );
}
