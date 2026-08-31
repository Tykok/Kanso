"use client";

import { useMutation } from "@tanstack/react-query";
import { useState } from "react";
import { api, API_URL, type SetupState } from "@/lib/api";
import { readNotionAppCredentials, redirectUriProblem } from "@/lib/notion-app-credentials";
import { Callout, CopyRow, TextField, messageFor } from "./fields";

/**
 * The Notion connection, as one block shared by the wizard and the settings screen.
 *
 * It replaces four trips outside Kanso with one, and the one that survives is the one
 * neither Notion nor Google will let an app avoid: creating an integration for a host
 * they have never heard of. Everything after that is a button, because Notion's own
 * consent screen is where the person chooses which pages Kanso may see — which is also
 * what retires the step people silently skipped, sharing a page from its `•••` menu.
 *
 * The redirect URI is derived from `API_URL` here and from the incoming request on the
 * server, so what Notion is told and what Notion is answered are the same string. If a
 * reverse proxy rewrites the host, that proxy has to forward it.
 */
const REDIRECT_URI = `${API_URL}/api/setup/notion/callback`;

/** Where the integration is created. The same page `.env.example` names for the token. */
const INTEGRATIONS_URL = "https://www.notion.so/profile/integrations";

export function NotionConnect({
  state,
  onState,
}: {
  state: SetupState;
  onState: (next: SetupState) => void;
}) {
  const stored = state.notion;
  const managed = stored.managedByEnvironment;

  const [clientId, setClientId] = useState("");
  const [clientSecret, setClientSecret] = useState("");
  const [editingApp, setEditingApp] = useState(!stored.appConfigured);
  /** What the pasted blob said about the redirect URI it was registered with. */
  const [pasteNote, setPasteNote] = useState<string | null>(null);

  const saveApp = useMutation({
    mutationFn: api.saveNotionApp,
    onSuccess: (next) => {
      // Write-only, like every other secret here: leaving it in the field would keep it
      // in the DOM for no gain and make a saved step look unsaved.
      setClientSecret("");
      setEditingApp(false);
      onState(next);
    },
  });

  /**
   * One paste instead of two transcriptions.
   *
   * Notion offers no client file, so what arrives is the authorization URL from the
   * integration page, the labelled block around the two secrets, or the pair as JSON —
   * `readNotionAppCredentials` treats all three the same. Anything it does not
   * recognise falls through unchanged, so typing an id by hand still works, and a
   * recognised blob never stays in the id box, because a field holding a whole block
   * is a field that looks broken.
   */
  const takeClientId = (value: string) => {
    const credentials = readNotionAppCredentials(value);
    if (!credentials) {
      setClientId(value);
      return;
    }
    setClientId(credentials.clientId);
    if (credentials.clientSecret) setClientSecret(credentials.clientSecret);
    setPasteNote(redirectUriProblem(credentials, REDIRECT_URI));
  };

  const connect = useMutation({
    mutationFn: api.startNotionConnect,
    // The window navigates itself; see `startNotionConnect` for why this is not a redirect.
    onSuccess: ({ url }) => window.location.assign(url),
  });

  if (managed) {
    return (
      <Callout>
        Notion is configured by the environment, so there is nothing to connect here.
        Unset `NOTION_TOKEN` to manage the connection from this screen.
      </Callout>
    );
  }

  return (
    <div className="flex flex-col gap-3">
      {stored.appConfigured && !editingApp ? (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="button"
              disabled={connect.isPending}
              onClick={() => connect.mutate()}
            >
              {connect.isPending
                ? "Opening Notion…"
                : stored.configured
                  ? "Reconnect Notion"
                  : "Connect Notion"}
            </button>

            {/* What is connected, not merely that something is. A token names nothing a
                person recognises; the workspace does. Absent when the token was pasted. */}
            {stored.configured && (
              <span className="text-12 text-muted-foreground">
                {stored.workspaceName
                  ? `Connected to ${stored.workspaceName}.`
                  : "Connected with a pasted token."}
              </span>
            )}
          </div>

          <p className="m-0 text-11 text-faint">
            Notion asks which pages Kanso may see. Nothing is shared until you choose it
            there — and nothing else has to be shared by hand afterwards.{" "}
            <button
              type="button"
              className="underline hover:text-foreground"
              onClick={() => setEditingApp(true)}
            >
              Change the integration
            </button>
          </p>

          {connect.error && (
            <span className="text-12 text-urgent">{messageFor(connect.error)}</span>
          )}
        </>
      ) : (
        <>
          <p className="m-0 text-12 text-muted-foreground">
            Create a <strong>public</strong> integration in Notion once, paste what it
            gives you here, and the rest is a button. Notion will not issue a client to a
            host it has never heard of, which is the one step no app can skip for you.{" "}
            <a
              href={INTEGRATIONS_URL}
              target="_blank"
              rel="noreferrer"
              className="underline hover:text-foreground"
            >
              Create it in Notion
            </a>
            .
          </p>

          <CopyRow label="Redirect URI to register on the integration" value={REDIRECT_URI} />

          {pasteNote && <Callout>{pasteNote}</Callout>}

          <TextField
            label="OAuth client id"
            autoComplete="off"
            spellCheck={false}
            value={clientId}
            placeholder={stored.appConfigured ? "Saved — type a new one to replace it" : ""}
            hint="Or paste Notion's authorization URL, or both values at once — it fills in the secret too."
            onChange={(event) => takeClientId(event.target.value)}
          />

          <TextField
            label="OAuth client secret"
            type="password"
            autoComplete="off"
            spellCheck={false}
            value={clientSecret}
            placeholder={stored.appConfigured ? "Saved — type a new one to replace it" : "secret_…"}
            onChange={(event) => setClientSecret(event.target.value)}
          />

          <div className="flex flex-wrap items-center gap-2">
            <button
              type="button"
              className="button"
              disabled={saveApp.isPending || !clientId.trim()}
              onClick={() =>
                saveApp.mutate({
                  clientId: clientId.trim(),
                  clientSecret: clientSecret.trim() || undefined,
                })
              }
            >
              {saveApp.isPending ? "Saving…" : "Save the integration"}
            </button>

            {stored.appConfigured && (
              <button
                type="button"
                className="text-12 text-faint underline hover:text-foreground"
                onClick={() => setEditingApp(false)}
              >
                Cancel
              </button>
            )}

            {saveApp.error && (
              <span className="text-12 text-urgent">{messageFor(saveApp.error)}</span>
            )}
          </div>
        </>
      )}
    </div>
  );
}
