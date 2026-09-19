"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import {
  Divider,
  ProviderButtons,
  TextField,
  fieldFromDetail,
  messageFor,
} from "@/components/setup/fields";
import { FormCard, MessageCard, SetupPage } from "@/components/setup/frame";
import { API_URL, ApiError, api, apiOrigin, type AuthMode } from "@/lib/api";
import { safeNext } from "@/lib/next-url";
import { keys } from "@/lib/queries";

/**
 * Signing in, as one screen.
 *
 * There were two. `/login` asked for an email and a password and offered the configured
 * providers underneath; the shell drew its own screen on a 401, and that one knew only
 * about providers — so an instance signing in with a password showed a red sentence saying
 * no provider was configured, which was true and useless, over a form that existed one
 * route away. It also said it whenever `/api/auth/mode` had not answered *yet*, or had
 * failed: three states, one of them a verdict about the instance's configuration, told
 * apart by nothing.
 *
 * So the form moved here and both callers render it. What is unknown is treated as
 * unknown: with no answer from `/api/auth/mode` the password form is drawn anyway, because
 * a form that reports the server's real error beats a dead end that guessed.
 */
export function LoginScreen({ mode }: { mode?: AuthMode }) {
  return (
    <SetupPage>
      <PasswordSignIn mode={mode} />
    </SetupPage>
  );
}

/**
 * A rate-limited attempt comes back with the same 4xx shape as a wrong password.
 * Without naming the limit, a locked-out user keeps retyping a password that was
 * right the first time.
 */
function SignInError({ error }: { error: unknown }) {
  const limited = error instanceof ApiError && error.status === 429;
  return (
    <p className="m-0 text-12 text-urgent" role="alert">
      {limited && <strong className="font-medium">Too many attempts — the password may well be right. </strong>}
      {messageFor(error)}
    </p>
  );
}

export function PasswordSignIn({
  mode,
  /**
   * Set by the API when an agent's authorisation lands on `/oauth/consent` with no session:
   * the consent screen is served on the API's origin, so the way back is an absolute URL
   * rather than a route in this app.
   *
   * A prop rather than a `useSearchParams` call, so that this renders inside the app shell
   * too: reading the query string in a client component makes every prerendered page above
   * it need a `Suspense` boundary, which `app-shell` deliberately does not have. The route
   * has one already and reads it there.
   */
  next,
}: {
  mode?: AuthMode;
  next?: string | null;
}) {
  const router = useRouter();
  const queryClient = useQueryClient();

  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  const login = useMutation({
    mutationFn: api.login,
    onSuccess: () => {
      // The cookie changed who the cached identity belongs to.
      queryClient.invalidateQueries({ queryKey: keys.me });
      // `apiOrigin()` and not `API_URL`: nothing is inlined into a published image, and
      // `safeNext` parses the origin it is given — `new URL("")` throws, is caught, and
      // returns HOME, so every absolute `next` would be refused and authorising an agent
      // would silently land on the board instead of the consent screen. Resolved here
      // rather than at module scope because this runs after a click, in a browser.
      const target = safeNext(next ?? null, apiOrigin());
      // An absolute target is the consent page on the API origin, which is a real
      // navigation rather than a route change.
      if (target.startsWith("http")) window.location.assign(target);
      else router.replace(target);
    },
  });

  // When /api/auth/mode itself failed there is no honest answer about what is
  // enabled, and a form that reports a real error beats a dead end that guesses.
  const providers = mode?.providers ?? [];
  const passwordEnabled = mode?.passwordLoginEnabled ?? true;

  if (!passwordEnabled) {
    /*
     * No form to submit, so no form. Reached only when `/api/auth/mode` actually said so —
     * an unanswered question is not a verdict, and printing this over a request still in
     * flight is what sent somebody looking for a `GOOGLE_CLIENT_ID` that was already set.
     */
    return (
      <MessageCard title={providers.length > 0 ? "Sign in" : "No way in"}>
        {providers.length > 0 ? (
          <ProviderButtons
            primary
            providers={providers.map((provider) => ({
              id: provider.id,
              label: provider.label,
              href: `${API_URL}${provider.authorizeUrl}`,
            }))}
          />
        ) : (
          <>
            <p className="m-0 text-13 text-muted-foreground">
              This instance has no sign-in method configured: password sign-in is off and no
              identity provider is registered.
            </p>
            <div className="flex flex-wrap items-center gap-2">
              <Link className="button button-primary" href="/setup">
                Open setup
              </Link>
            </div>
          </>
        )}
      </MessageCard>
    );
  }

  // A rate limit must never be filed under the password field: the whole point is
  // that it says nothing about whether the password was right.
  const limited = login.error instanceof ApiError && login.error.status === 429;
  const apiField = limited ? null : fieldFromDetail(login.error);

  return (
    <FormCard
      title="Sign in"
      intro="A keyboard-first tracker. Postgres holds the truth; Notion keeps a readable copy."
      primaryLabel="Sign in"
      pending={login.isPending}
      onSubmit={() => login.mutate({ email: email.trim(), password })}
    >
      <TextField
        label="Email"
        type="email"
        autoFocus
        required
        autoComplete="email"
        value={email}
        onChange={(event) => setEmail(event.target.value)}
      />

      <TextField
        label="Password"
        type="password"
        required
        autoComplete="current-password"
        value={password}
        error={apiField === "password" ? messageFor(login.error) : null}
        onChange={(event) => setPassword(event.target.value)}
      />

      {login.error && apiField !== "password" && <SignInError error={login.error} />}

      {providers.length > 0 && (
        <>
          <Divider>or</Divider>
          <ProviderButtons
            providers={providers.map((provider) => ({
              id: provider.id,
              label: provider.label,
              href: `${API_URL}${provider.authorizeUrl}`,
            }))}
          />
        </>
      )}
    </FormCard>
  );
}
