"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState } from "react";
import { API_URL, ApiError, type Me } from "@/lib/api";
import { api } from "@/lib/api";
import { keys } from "@/lib/queries";

const MIN_PASSWORD = 12;

function message(error: unknown) {
  return error instanceof ApiError ? error.message : (error as Error)?.message ?? "Something went wrong";
}

/** A saved/failed line that says which field it belongs to, next to that field. */
function Status({ saved, error }: { saved?: boolean; error?: unknown }) {
  if (error) return <span className="settings-note error">{message(error)}</span>;
  if (saved) return <span className="settings-note">Saved</span>;
  return null;
}

export function AccountSection({ me }: { me: Me }) {
  const queryClient = useQueryClient();
  const refresh = () => queryClient.invalidateQueries({ queryKey: keys.me });

  const [displayName, setDisplayName] = useState(me.user.displayName);
  const [notionId, setNotionId] = useState(me.user.notionPersonId ?? "");
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [confirm, setConfirm] = useState("");

  const rename = useMutation({ mutationFn: api.renameMe, onSuccess: refresh });
  const notion = useMutation({ mutationFn: api.setNotionIdentity, onSuccess: refresh });
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
    <section className="settings-section">
      <h2>Account</h2>

      <div className="settings-field">
        <label htmlFor="display-name">Display name</label>
        <div className="settings-inline">
          <input
            id="display-name"
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
        </div>
        <Status saved={rename.isSuccess} error={rename.error} />
      </div>

      <div className="settings-field">
        <label>Email</label>
        <div className="settings-static">{me.user.email}</div>
        <span className="settings-note">
          Fixed: it is both your sign-in and how a provider account is matched to this one.
        </span>
      </div>

      <div className="settings-field">
        <label htmlFor="notion-id">Notion identity</label>
        <div className="settings-inline">
          <input
            id="notion-id"
            placeholder="Notion user id"
            value={notionId}
            onChange={(event) => setNotionId(event.target.value)}
          />
          <button
            className="button"
            disabled={notion.isPending}
            onClick={() => notion.mutate(notionId.trim() || null)}
          >
            Save
          </button>
        </div>
        <span className="settings-note">
          Lets the mirror put you in Notion&rsquo;s <code>Assignees</code> property. Without it you
          appear only as text, because Notion accepts nobody outside its own workspace there.
        </span>
        <Status saved={notion.isSuccess} error={notion.error} />
      </div>

      <div className="settings-field">
        <label>Sign in with Google</label>
        {me.user.linkedProvider ? (
          <div className="settings-inline">
            <div className="settings-static">Linked to {me.user.linkedProvider}</div>
            <button
              className="button"
              disabled={!canUnlink || unlink.isPending}
              onClick={() => unlink.mutate(me.user.linkedProvider as string)}
            >
              Unlink
            </button>
          </div>
        ) : (
          <div className="settings-inline">
            <div className="settings-static">Not linked</div>
            <a className="button" href={`${API_URL}/oauth2/authorization/google`}>
              Link Google
            </a>
          </div>
        )}
        <span className="settings-note">
          {me.user.linkedProvider && !me.user.hasPassword
            ? "Set a password first — unlinking now would leave you no way to sign in."
            : "Signing in with Google attaches to this account when the address matches."}
        </span>
        <Status error={unlink.error} />
      </div>

      <div className="settings-field">
        <label htmlFor="current-password">
          {me.user.hasPassword ? "Change password" : "Password"}
        </label>
        {me.user.hasPassword ? (
          <>
            <input
              id="current-password"
              type="password"
              autoComplete="current-password"
              placeholder="Current password"
              value={current}
              onChange={(event) => setCurrent(event.target.value)}
            />
            <input
              type="password"
              autoComplete="new-password"
              placeholder={`New password (${MIN_PASSWORD}+ characters)`}
              value={next}
              onChange={(event) => setNext(event.target.value)}
            />
            <input
              type="password"
              autoComplete="new-password"
              placeholder="Repeat the new password"
              value={confirm}
              onChange={(event) => setConfirm(event.target.value)}
            />
            <div className="settings-inline">
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
              {passwordProblem && <span className="settings-note error">{passwordProblem}</span>}
              <Status saved={password.isSuccess} error={password.error} />
            </div>
            <span className="settings-note">
              Your other signed-in browsers are signed out. This one stays.
            </span>
          </>
        ) : (
          <span className="settings-note">
            This account signs in through {me.user.linkedProvider ?? "a provider"} and has no
            password.
          </span>
        )}
      </div>
    </section>
  );
}
