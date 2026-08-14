"use client";

import { useMutation } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { api, type SetupState } from "@/lib/api";
import { Callout, TextField, messageFor } from "./fields";
import { FormCard } from "./frame";

type Props = {
  head: ReactNode;
  state: SetupState;
  onState: (next: SetupState) => void;
  onDone: () => void;
  onSkip: () => void;
  onBack?: () => void;
};

export function NotionStep({ head, state, onState, onDone, onSkip, onBack }: Props) {
  const stored = state.notion;
  const managed = stored.managedByEnvironment;

  const [token, setToken] = useState("");
  const [parentPageId, setParentPageId] = useState(stored.parentPageId ?? "");

  const test = useMutation({ mutationFn: api.testNotion });
  const bootstrap = useMutation({ mutationFn: api.bootstrapNotion, onSuccess: onState });
  const save = useMutation({
    mutationFn: api.saveNotion,
    onSuccess: (next) => {
      // The token is write-only. Leaving it in the field would keep the step looking
      // unsaved, and would keep the secret in the DOM for no gain.
      setToken("");
      onState(next);
    },
  });

  // A secret never comes back from the API, so any typed token counts as a change.
  const dirty = token.trim().length > 0 || parentPageId.trim() !== (stored.parentPageId ?? "");
  const settled = managed || (stored.configured && !dirty);

  const failure = save.error ?? bootstrap.error;

  return (
    <FormCard
      head={head}
      title="Notion"
      intro="Postgres stays the source of truth; Notion keeps a readable copy of it. Skip this and Kanso works exactly the same, minus the mirror."
      primaryLabel={settled ? "Continue" : "Save"}
      pending={save.isPending}
      error={failure ? messageFor(failure) : null}
      onSkip={onSkip}
      onBack={onBack}
      onSubmit={() => {
        if (settled) {
          onDone();
          return;
        }
        save.mutate({
          token: token.trim() || undefined,
          parentPageId: parentPageId.trim(),
        });
      }}
    >
      {managed && (
        <Callout>
          Notion is configured by the environment. The wizard shows it read-only —
          letting both write the same setting is how they end up disagreeing.
        </Callout>
      )}

      <TextField
        label="Integration token"
        type="password"
        readOnly={managed}
        autoFocus={!managed}
        autoComplete="off"
        spellCheck={false}
        value={managed ? "" : token}
        placeholder={
          managed
            ? "Set in the environment"
            : stored.configured
              ? "Saved — type a new one to replace it"
              : "secret_…"
        }
        hint="Create an internal integration in Notion, then share the parent page with it."
        onChange={(event) => setToken(event.target.value)}
      />

      <TextField
        label="Parent page id"
        readOnly={managed}
        required={!managed}
        autoComplete="off"
        spellCheck={false}
        value={parentPageId}
        placeholder="32 hex characters from the page URL"
        hint="The page Kanso creates its databases under."
        onChange={(event) => setParentPageId(event.target.value)}
      />

      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          className="button"
          disabled={test.isPending}
          onClick={() =>
            test.mutate({
              token: token.trim() || undefined,
              parentPageId: parentPageId.trim() || undefined,
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

      {(stored.configured || managed) && (
        <div className="flex flex-wrap items-center gap-2">
          {stored.bootstrapped ? (
            <span className="text-11 text-faint">Databases already created in Notion.</span>
          ) : (
            <>
              <button
                type="button"
                className="button"
                disabled={bootstrap.isPending}
                onClick={() => bootstrap.mutate()}
              >
                {bootstrap.isPending ? "Creating…" : "Create the databases now"}
              </button>
              <span className="text-11 text-faint">
                Tickets, teams and projects, once. Safe to leave for later.
              </span>
            </>
          )}
        </div>
      )}
    </FormCard>
  );
}
