"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { API_URL, ApiError, type Me } from "@/lib/api";
import { api } from "@/lib/api";
import { keys } from "@/lib/queries";
import { SettingsFormField, SettingsInline, SettingsNote, SettingsStatic } from "./field";

const MIN_PASSWORD = 12;

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

/** A saved/failed line that says which field it belongs to, next to that field. */
function Status({ saved, error }: { saved?: boolean; error?: unknown }) {
  if (error) return <SettingsNote error>{message(error)}</SettingsNote>;
  if (saved) return <SettingsNote>Saved</SettingsNote>;
  return null;
}

export function AccountSection({ me }: { me: Me }) {
  const queryClient = useQueryClient();
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.me });

  const [displayName, setDisplayName] = useState(me.user.displayName);
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");

  const rename = useMutation({ mutationFn: api.renameMe, onSuccess: refresh });
  const notion = useQuery({ queryKey: ["me", "notion-identity"], queryFn: api.myNotionIdentity });
  const unlink = useMutation({ mutationFn: api.unlinkProvider, onSuccess: refresh });
  const password = useMutation({
    mutationFn: api.changePassword,
    onSuccess: () => {
      setCurrent("");
      setNext("");
      setConfirm("");
      refresh();
    },
  });

  const passwordProblem =
    next.length > 0 && next.length < MIN_PASSWORD
      ? `At least ${MIN_PASSWORD} characters.`
      : confirm.length > 0 && next !== confirm
        ? "The two entries do not match."
        : null;

  // Removing the last way in leaves an account nobody can sign into, and nothing
  // inside the app can undo that afterwards.
  const canUnlink = Boolean(me.user.linkedProvider) && me.user.hasPassword;

  return (
    <section className="flex flex-col">
      <h2 className="mb-6 text-21 font-medium tracking-tight">Account</h2>

      <SettingsFormField>
        <label htmlFor="display-name" className="text-13 font-medium">
          Display name
        </label>
        <SettingsInline>
          <input
            id="display-name"
            className="flex-1 min-w-[180px]"
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
          <button
            className="button"
            disabled={rename.isPending || displayName.trim() === me.user.displayName}
            onClick={() => rename.mutate(displayName)}
          >
            Save
          </button>
        </SettingsInline>
        <Status saved={rename.isSuccess} error={rename.error} />
      </SettingsFormField>

      <SettingsFormField>
        <span className="text-13 font-medium">Email</span>
        <SettingsStatic>{me.user.email}</SettingsStatic>
        <SettingsNote>
          Fixed: it is both your sign-in and how a provider account is matched to this one.
        </SettingsNote>
      </SettingsFormField>

      {notion.data?.connected ? (
        <SettingsFormField>
          <span className="text-13 font-medium">Notion identity</span>
          {notion.data.member ? (
            <SettingsStatic>
              {notion.data.member.name ?? "Unnamed"}
              {notion.data.member.email ? ` · ${notion.data.member.email}` : ""}
              <span className="block font-mono text-11 text-faint">{notion.data.notionPersonId}</span>
            </SettingsStatic>
          ) : (
            <SettingsStatic>{notion.data.notionPersonId ?? "Not matched"}</SettingsStatic>
          )}
          <SettingsNote>
            Read-only. Which Notion person you are is a fact about that workspace, not a field
            on this account, so an admin sets it on the import&rsquo;s matching screen. It is what
            lets the mirror put you in Notion&rsquo;s <code>Assignees</code> property instead of
            plain text.
          </SettingsNote>
          {notion.data.reason ? <SettingsNote error>{notion.data.reason}</SettingsNote> : null}
        </SettingsFormField>
      ) : null}

      <SettingsFormField>
        <span className="text-13 font-medium">Sign in with Google</span>
        {me.user.linkedProvider ? (
          <SettingsInline>
            <SettingsStatic>Linked to {me.user.linkedProvider}</SettingsStatic>
            <button
              className="button"
              disabled={!canUnlink || unlink.isPending}
              onClick={() => unlink.mutate(me.user.linkedProvider as string)}
            >
              Unlink
            </button>
          </SettingsInline>
        ) : (
          <SettingsInline>
            <SettingsStatic>Not linked</SettingsStatic>
            <a className="button" href={`${API_URL}/oauth2/authorization/google`}>
              Link Google
            </a>
          </SettingsInline>
        )}
        <SettingsNote>
          {me.user.linkedProvider && !me.user.hasPassword
            ? "Set a password first — unlinking now would leave you no way to sign in."
            : "Signing in with Google attaches to this account when the address matches."}
        </SettingsNote>
        <Status error={unlink.error} />
      </SettingsFormField>

      <SettingsFormField>
        <label htmlFor="current-password" className="text-13 font-medium">
          {me.user.hasPassword ? "Change password" : "Password"}
        </label>
        {me.user.hasPassword ? (
          <>
            <input
              id="current-password"
              className="w-full max-w-[380px]"
              type="password"
              autoComplete="current-password"
              placeholder="Current password"
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
            />
            <input
              className="w-full max-w-[380px]"
              type="password"
              autoComplete="new-password"
              placeholder={`New password (${MIN_PASSWORD}+ characters)`}
              value={next}
              onChange={(event) => setNext(event.target.value)}
            />
            <input
              className="w-full max-w-[380px]"
              type="password"
              autoComplete="new-password"
              placeholder="Repeat the new password"
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
            />
            <SettingsInline>
              <button
                className="button button-primary"
                disabled={
                  password.isPending ||
                  !current ||
                  next.length < MIN_PASSWORD ||
                  next !== confirm
                }
                onClick={() => password.mutate({ currentPassword: current, newPassword: next })}
              >
                Change password
              </button>
              {passwordProblem && <SettingsNote error>{passwordProblem}</SettingsNote>}
              <Status saved={password.isSuccess} error={password.error} />
            </SettingsInline>
            <SettingsNote>
              Your other signed-in browsers are signed out. This one stays.
            </SettingsNote>
          </>
        ) : (
          <SettingsNote>
            This account signs in through {me.user.linkedProvider ?? "a provider"} and has no
            password.
          </SettingsNote>
        )}
      </SettingsFormField>
    </section>
  );
}
