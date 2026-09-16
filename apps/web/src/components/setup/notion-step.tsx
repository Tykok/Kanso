"use client";

import { useMutation } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { ImportDialog } from "@/components/inbox/import-dialog";
import { NotionPeopleSection } from "@/components/settings/notion-people-section";
import { api, type SetupState } from "@/lib/api";
import { useMe } from "@/lib/queries";
import { canConfigure as configures } from "@/lib/seat";
import { Callout, Divider, TextField, messageFor } from "./fields";
import { NotionConnect } from "./notion-connect";
import { NotionImportCard } from "./notion-import-card";
import { NotionPageField } from "./notion-page-field";
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
  /**
   * A cache read, not a request: `/setup` mounts `useMe` before it mounts any step. The
   * plan puts this step in front of a viewer too — it hides it from members alone — and a
   * viewer may configure nothing, so the two blocks below would draw controls the server
   * answers 403 to.
   */
  const me = useMe();
  const configurator = configures(me.data?.user.instanceRole);

  const [token, setToken] = useState("");
  /**
   * The paste is the fallback now, not the way in. Two instances still need it — one that
   * already has a working internal integration and should not have to redo it, and one
   * whose browser cannot reach a consent screen at all — so it is kept, and folded away.
   */
  const [pasting, setPasting] = useState(false);
  const [parentPageId, setParentPageId] = useState(stored.parentPageId ?? "");
  /**
   * Screen 24, over the wizard. Held here rather than in [NotionImportCard] because the
   * dialog is mounted outside the `FormCard` below — see that card for why a dialog inside
   * this step's `<form>` would advance the wizard when somebody pressed its own Next.
   */
  const [importing, setImporting] = useState(false);

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
    <>
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

        <NotionConnect state={state} onState={onState} />

        {!managed && (
          <Divider>
            <button
              type="button"
              className="underline hover:text-foreground"
              onClick={() => setPasting((open) => !open)}
            >
              {pasting ? "Hide the token field" : "Paste an integration token instead"}
            </button>
          </Divider>
        )}

        {(pasting || managed) && (
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
          hint="An internal integration's secret. The page it writes under still has to be shared with it by hand — which is what connecting above avoids."
          onChange={(event) => setToken(event.target.value)}
        />
        )}

        {/* Chosen from what the integration can actually see, rather than typed as 32 hex
            characters — the last manual step of the old setup, and the one that used to fail
            at bootstrap instead of at save. `token` is whatever is in the field above, so the
            list works before this step has been saved. */}
        <NotionPageField
          value={parentPageId}
          onChange={setParentPageId}
          token={token}
          disabled={managed}
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

        {/*
          Matching people first, importing second, and that order is the whole reason they
          sit in one block: step 4 of the import pre-fills itself from this correspondence,
          so a workspace matched here arrives at that step already answered.
        */}
        {configurator && (stored.configured || managed) && (
          <>
            <NotionPeopleSection canConfigure />
            <NotionImportCard onOpen={() => setImporting(true)} />
          </>
        )}
      </FormCard>
      {importing && <ImportDialog onClose={() => setImporting(false)} />}
    </>
  );
}
