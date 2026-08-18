"use client";

import { useMutation } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { API_URL, api, type SetupState } from "@/lib/api";
import { readGoogleClientFile, redirectUriProblem } from "@/lib/google-client-file";
import { Callout, CopyRow, TextField, messageFor } from "./fields";
import { FormCard } from "./frame";

/**
 * Spring registers the callback under a fixed path, so the URI is derivable rather
 * than configurable — and it has to match Google's entry character for character,
 * which is why it is offered to copy instead of described.
 */
const REDIRECT_URI = `${API_URL}/login/oauth2/code/google`;

type Props = {
  head: ReactNode;
  state: SetupState;
  onState: (next: SetupState) => void;
  onDone: () => void;
  onSkip: () => void;
  onBack?: () => void;
};

export function GoogleStep({ head, state, onState, onDone, onSkip, onBack }: Props) {
  const stored = state.google;
  const managed = stored.managedByEnvironment;

  const [clientId, setClientId] = useState(stored.clientId ?? "");
  const [clientSecret, setClientSecret] = useState("");
  /** What the pasted client file said about its own redirect URIs, if it said anything. */
  const [fileNote, setFileNote] = useState<string | null>(null);

  const save = useMutation({
    mutationFn: api.saveGoogle,
    onSuccess: (next) => {
      setClientSecret("");
      onState(next);
    },
  });

  const test = useMutation({ mutationFn: api.testGoogle });

  /**
   * One paste of the downloaded JSON instead of two careful transcriptions.
   *
   * Anything that is not a client file falls through unchanged, so typing an id by hand
   * still works — and the JSON never stays in the id box, because a field holding a whole
   * file is a field that looks broken.
   */
  const takeClientId = (value: string) => {
    const file = readGoogleClientFile(value);
    if (!file) {
      setClientId(value);
      return;
    }
    setClientId(file.clientId);
    if (file.clientSecret) setClientSecret(file.clientSecret);
    setFileNote(redirectUriProblem(file, REDIRECT_URI));
  };

  const dirty = clientSecret.trim().length > 0 || clientId.trim() !== (stored.clientId ?? "");
  const settled = managed || (stored.configured && !dirty);

  return (
    <FormCard
      head={head}
      title="Google sign-in"
      intro="Optional. Without it, everyone signs in with the email and password they set when they accept their invitation."
      primaryLabel={settled ? "Continue" : "Save"}
      pending={save.isPending}
      error={save.error ? messageFor(save.error) : null}
      onSkip={onSkip}
      onBack={onBack}
      onSubmit={() => {
        if (settled) {
          onDone();
          return;
        }
        save.mutate({ clientId: clientId.trim(), clientSecret: clientSecret.trim() });
      }}
    >
      {managed && (
        <Callout>
          Google is configured by the environment. The wizard shows it read-only —
          letting both write the same setting is how they end up disagreeing.
        </Callout>
      )}

      <CopyRow label="Authorised redirect URI" value={REDIRECT_URI} />
      <p className="m-0 text-11 text-faint">
        Paste it into Google Cloud → APIs &amp; Services → Credentials → your OAuth client,
        under Authorised redirect URIs. Google rejects the sign-in if it differs by a
        single character.
      </p>

      <TextField
        label="Client id"
        readOnly={managed}
        required={!managed}
        autoFocus={!managed}
        autoComplete="off"
        spellCheck={false}
        value={clientId}
        placeholder={managed ? "Set in the environment" : "…apps.googleusercontent.com"}
        hint={
          managed
            ? undefined
            : "Or paste the whole JSON file Google Cloud downloads for the client — it fills in the secret too."
        }
        onChange={(event) => takeClientId(event.target.value)}
      />

      <TextField
        label="Client secret"
        type="password"
        readOnly={managed}
        required={!managed && clientId.trim() !== (stored.clientId ?? "")}
        autoComplete="off"
        spellCheck={false}
        value={managed ? "" : clientSecret}
        placeholder={
          managed
            ? "Set in the environment"
            : stored.configured
              ? "Saved — type a new one to replace it"
              : "GOCSPX-…"
        }
        onChange={(event) => setClientSecret(event.target.value)}
      />

      {/* The file knows which redirect URIs its client was created with, so a missing one
          is worth saying at paste time — it is the second most common misconfiguration
          after a wrong secret, and the credential check below cannot see it. */}
      {fileNote && <p className="m-0 text-12 text-urgent">{fileNote}</p>}

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="button"
          disabled={test.isPending}
          onClick={() =>
            test.mutate({
              clientId: clientId.trim() || undefined,
              clientSecret: clientSecret.trim() || undefined,
            })
          }
        >
          {test.isPending ? "Testing…" : "Test connection"}
        </button>

        {test.data && (
          <span className={`text-12 ${test.data.ok ? "text-status-done" : "text-urgent"}`}>
            {test.data.detail}
          </span>
        )}
        {test.error && <span className="text-12 text-urgent">{messageFor(test.error)}</span>}
      </div>
    </FormCard>
  );
}
