"use client";

import { useState } from "react";
import { ApiError, lastUsedLabel, type NewApiToken } from "@/lib/api";
import {
  useApiTokenScopes,
  useApiTokens,
  useCreateApiToken,
  useRevokeApiToken,
} from "@/lib/queries";
import { SettingsFormField, SettingsInline, SettingsNote } from "./field";

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

/**
 * The secret, in the one moment it exists on this side of the wire.
 *
 * A third copy of `people-section.tsx`'s `CopyableLink` shape rather than a shared
 * component, and the precedent is `agents-section.tsx`'s `CopyableCommand`, which records
 * the rule: these are folded together only when they mean the same thing. This one and the
 * invitation link *do* mean the same thing — both are shown once and unrecoverable — but
 * this one needs two things that one does not, and both are about the failure this screen
 * is most likely to have.
 *
 * **It selects itself on mount.** `autoFocus` on a read-only input, plus the neighbours'
 * select-on-focus in the one variant [selectFromTheStart] explains, means the secret is
 * already highlighted when it appears: the copy is one keystroke away for somebody whose
 * browser has no clipboard permission, which is every browser served over plain HTTP to
 * anything but localhost. `navigator.clipboard` is absent there and the optional call
 * below quietly does nothing — so the button is the convenience and the selection is the
 * guarantee.
 *
 * **It has no dismiss timer and no auto-close.** It goes away when the person says so, and
 * `onDone`'s label says what saying so means.
 */
/**
 * Select the whole value and leave the *beginning* of it on screen.
 *
 * `select()` would do the first half and undo the second: it drops the caret at the end,
 * the field scrolls to follow it, and a 53-character token in a field this wide then
 * visibly starts somewhere in the middle of its own secret — past the `kanso_pat_` marker
 * that is the one part a person recognises. Seen in a browser; nothing else would show it,
 * because the value and the clipboard are both already correct and only the scroll offset
 * is wrong.
 *
 * `"backward"` puts the selection's focus at offset 0, which is most of the way there, and
 * the assignment finishes it: measured in Chromium, direction alone still left the field
 * 72px in — enough to clip the marker — because the scroll that follows `autoFocus` is not
 * the one the selection direction governs. So the offset is stated rather than inferred,
 * which is also the only version of this that a test can assert.
 */
function selectFromTheStart(field: HTMLInputElement) {
  field.setSelectionRange(0, field.value.length, "backward");
  field.scrollLeft = 0;
}

function CopyableSecret({ secret, onDone }: { secret: string; onDone: () => void }) {
  const [copied, setCopied] = useState(false);

  return (
    <SettingsInline>
      <input
        readOnly
        autoFocus
        aria-label="Your new API token"
        className="flex-1 min-w-[180px] text-12"
        style={{ fontFamily: "var(--font-mono)" }}
        value={secret}
        onFocus={(event) => selectFromTheStart(event.currentTarget)}
      />
      <button
        className="button"
        onClick={() => {
          void navigator.clipboard?.writeText(secret).then(() => setCopied(true));
        }}
      >
        {copied ? "Copied" : "Copy"}
      </button>
      <button className="button" onClick={onDone}>
        I have copied it
      </button>
    </SettingsInline>
  );
}

/**
 * API tokens: a credential for something that is not a browser.
 *
 * Its own section rather than a part of Agents, because they are two doors and `V27` argues
 * the difference at length — an OAuth grant starts on a consent screen and a token is for
 * the caller that has no browser to render one. The practical proof that folding them would
 * be wrong is in `AgentsSection` itself: it disables connecting entirely in dev mode,
 * because there is no authorisation server to answer. Tokens work in dev mode — a token is
 * a row in a table this instance wrote — so the same screen would have to disable half of
 * itself and explain why.
 *
 * Every member gets this section, admin or not, for the reason Agents gives: the server
 * accepts no member as a parameter, so there is no one else's list to show and nothing here
 * for an admin to administer.
 */
export function TokensSection() {
  const tokens = useApiTokens();
  const choices = useApiTokenScopes();
  const create = useCreateApiToken();
  const revoke = useRevokeApiToken();

  const [name, setName] = useState("");
  const [chosen, setChosen] = useState<string[]>([]);
  /**
   * The secret lives here and nowhere else.
   *
   * Section state and not `create.data`, which is the bug this screen exists to not have:
   * creating invalidates the list, the list refetches, this component re-renders, and a
   * value read out of the mutation is a value whose lifetime is the mutation's rather than
   * the person's. `useState` survives every re-render underneath it, so the only things
   * that can take the secret away are the person pressing the button that says so and a
   * reload — which is the truth about it, and is what the notes below say out loud.
   */
  const [issued, setIssued] = useState<NewApiToken | null>(null);
  /** Which row is asking a second time. One at a time, by id: a stale `true` would arm the
   *  confirmation on whichever row happened to render in that position. */
  const [confirming, setConfirming] = useState<string | null>(null);

  const listed = tokens.data ?? [];
  const scopeChoices = choices.data ?? [];
  const canCreate = name.trim().length > 0 && chosen.length > 0 && !create.isPending;

  const toggle = (scope: string) =>
    setChosen((current) =>
      current.includes(scope) ? current.filter((held) => held !== scope) : [...current, scope],
    );

  return (
    <section className="flex flex-col">
      <h2 className="mb-4 text-21 font-medium tracking-tight">API tokens</h2>

      {listed.length > 0 && (
        <SettingsFormField>
          <span className="text-13 font-medium">Your tokens</span>
          <ul className="flex flex-col gap-px overflow-hidden rounded-md border border-border">
            {listed.map((token) => (
              <li
                key={token.id}
                className="flex flex-col gap-1.5 border-b border-border bg-card px-3 py-2 last:border-b-0"
              >
                <div className="flex items-center gap-3">
                  <span className="flex min-w-0 flex-1 flex-col gap-px leading-tight">
                    {token.name}
                    <SettingsNote>
                      {/* The stored prefix, with an ellipsis so nobody reads it as the
                          whole token and pastes it into a config that will 401. */}
                      <span style={{ fontFamily: "var(--font-mono)" }}>{token.prefix}…</span>
                    </SettingsNote>
                    <SettingsNote>
                      {lastUsedLabel(token.lastUsedAt)} · created{" "}
                      {new Date(token.createdAt).toLocaleDateString()}
                    </SettingsNote>
                    {/* The server's sentences, not a paraphrase written here — the same
                        wording the consent screen shows for the same two scopes. */}
                    {token.scopeProse.map((sentence) => (
                      <SettingsNote key={sentence}>{sentence}</SettingsNote>
                    ))}
                  </span>

                  {confirming === token.id ? (
                    <SettingsInline>
                      <button className="button" onClick={() => setConfirming(null)}>
                        Keep it
                      </button>
                      <button
                        className="button"
                        disabled={revoke.isPending}
                        onClick={() => {
                          revoke.mutate(token.id);
                          setConfirming(null);
                        }}
                      >
                        Revoke for good
                      </button>
                    </SettingsInline>
                  ) : (
                    <button className="button" onClick={() => setConfirming(token.id)}>
                      Revoke
                    </button>
                  )}
                </div>

                {/*
                 * The confirmation, below the row rather than beside the buttons: it is two
                 * sentences and the second one is the one people do not know. Revoking is
                 * not undoable by re-creating — the row is deleted and a new token is a
                 * different secret — so "I will just make it again" repairs the access and
                 * not the credential that is already deployed somewhere.
                 */}
                {confirming === token.id && (
                  <SettingsNote error>
                    Revoking takes effect immediately and cannot be undone. Creating another
                    token does not bring this one back: the new one is a different secret,
                    so anything still using this token has to be given the new one by hand.
                  </SettingsNote>
                )}
              </li>
            ))}
          </ul>
          {revoke.isError && <SettingsNote error>{message(revoke.error)}</SettingsNote>}
        </SettingsFormField>
      )}

      <SettingsFormField>
        <label htmlFor="token-name" className="text-13 font-medium">
          {listed.length > 0 ? "Create another token" : "Create a token"}
        </label>
        <SettingsInline>
          <input
            id="token-name"
            className="flex-1 min-w-[180px]"
            placeholder="What is it for? (deploy script, laptop CLI…)"
            value={name}
            onChange={(event) => setName(event.target.value)}
          />
        </SettingsInline>
        <SettingsNote>
          The name is the only way to tell two tokens apart when you come back to revoke
          one — they are otherwise the same owner, the same permissions and an opaque
          prefix.
        </SettingsNote>

        {/*
         * Checkboxes over the server's own list, so there is no scope string written in
         * this file. `ApiTokenScopeChoice` carries the argument: a literal list here would
         * be a second vocabulary that no compiler could hold to the first.
         */}
        <div className="flex flex-col gap-1.5 pt-1">
          {scopeChoices.map((choice) => (
            <label key={choice.scope} className="flex items-start gap-2 text-13">
              <input
                type="checkbox"
                className="mt-0.5"
                checked={chosen.includes(choice.scope)}
                onChange={() => toggle(choice.scope)}
              />
              <span className="flex min-w-0 flex-col gap-px leading-tight">
                <span className="text-12" style={{ fontFamily: "var(--font-mono)" }}>
                  {choice.scope}
                </span>
                <SettingsNote>{choice.prose}</SettingsNote>
              </span>
            </label>
          ))}
        </div>
        <SettingsNote>
          A token needs at least one, and nothing is ticked for you: the permissions of a
          credential should be a decision somebody made. Reads and writes are separate so a
          token for a dashboard cannot change anything.
        </SettingsNote>

        {/*
         * Said before the secret exists, which is the half of "shown once" that a warning
         * printed next to the secret cannot cover — by then the person has already decided
         * how much attention to pay. The sentence beside the secret repeats it, in the
         * server's own words, at the moment it becomes urgent.
         */}
        <SettingsNote>
          You will see the token once, immediately after creating it, and never again —
          Kanso keeps only a hash. Reloading this page loses it for good.
        </SettingsNote>

        <SettingsInline>
          <button
            className="button button-primary"
            disabled={!canCreate}
            onClick={() =>
              create.mutate(
                { name: name.trim(), scopes: chosen },
                {
                  onSuccess: (made) => {
                    setIssued(made);
                    setName("");
                    setChosen([]);
                  },
                },
              )
            }
          >
            Create token
          </button>
        </SettingsInline>
        {create.isError && <SettingsNote error>{message(create.error)}</SettingsNote>}

        {issued && (
          <div className="mt-1 flex flex-col gap-1.5 rounded-md border border-urgent px-3 py-2.5">
            <span className="text-13 font-medium">{issued.token.name}</span>
            <CopyableSecret secret={issued.secret} onDone={() => setIssued(null)} />
            {/*
             * The server's own sentence rather than one written here. It is the wording a
             * `curl` caller gets in the response body, and a person reading a screen should
             * not be told something subtly different from a person reading JSON.
             */}
            <SettingsNote error>{issued.warning}</SettingsNote>
          </div>
        )}
      </SettingsFormField>

      <SettingsFormField>
        <span className="text-13 font-medium">Using one</span>
        <SettingsNote>
          Send it as <span style={{ fontFamily: "var(--font-mono)" }}>Authorization: Bearer
          &lt;token&gt;</span> to this instance&apos;s API. It acts as you, and is refused
          the moment it is revoked or your account is deactivated.
        </SettingsNote>
        {/*
         * Stated because a person on this screen is very likely here to connect an agent,
         * and this is the one thing a token will not do. `ApiTokenFilter` records why the
         * two doors are partitioned rather than ordered; without this sentence the failure
         * is a 401 from an agent that looked correctly configured.
         */}
        <SettingsNote>
          It does not work for an MCP agent. That door is OAuth and lives under Agents — a
          token is for a CLI, a script or a webhook, which have no browser to consent in.
        </SettingsNote>
      </SettingsFormField>

      {tokens.isError && <SettingsNote error>{message(tokens.error)}</SettingsNote>}
      {choices.isError && <SettingsNote error>{message(choices.error)}</SettingsNote>}
    </section>
  );
}
