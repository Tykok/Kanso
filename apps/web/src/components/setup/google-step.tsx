"use client";

import { useMutation } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { API_URL, api, type SetupState } from "@/lib/api";
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

  const save = useMutation({
    mutationFn: api.saveGoogle,
    onSuccess: (next) => {
      setClientSecret("");
      onState(next);
    },
  });

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
      <p className="setup-hint">
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
        onChange={(event) => setClientId(event.target.value)}
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
    </FormCard>
  );
}
