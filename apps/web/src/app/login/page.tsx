"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { useSetupState } from "@/components/setup/data";
import {
  PASSWORD_MIN,
  Divider,
  ProviderButtons,
  TextField,
  fieldFromDetail,
  messageFor,
  passwordProblem,
} from "@/components/setup/fields";
import { FormCard, MessageCard, SetupPage } from "@/components/setup/frame";
import { API_URL, ApiError, api, apiOrigin, type AuthMode } from "@/lib/api";
import { safeNext } from "@/lib/next-url";
import { keys, useAuthMode } from "@/lib/queries";

export default function LoginPage() {
  return (
    // Reading the invitation token opts this tree out of prerendering; the boundary
    // keeps that to the form rather than to the whole route.
    <Suspense
      fallback={
        <SetupPage>
          <MessageCard title="Sign in">
            <p className="m-0 text-11 text-faint">Loading…</p>
          </MessageCard>
        </SetupPage>
      }
    >
      <SignIn />
    </Suspense>
  );
}

function SignIn() {
  const router = useRouter();
  const setup = useSetupState();
  const mode = useAuthMode();
  const invitation = useSearchParams().get("invite");

  useEffect(() => {
    // An instance nobody owns has no account to sign into, and offering a form that
    // can only fail is worse than sending them where the account gets made.
    if (setup.data?.needsOwner) router.replace("/setup");
  }, [setup.data, router]);

  if (setup.error) {
    return (
      <SetupPage>
        <MessageCard title="Cannot reach the instance">
          <p className="m-0 text-12 text-urgent">{messageFor(setup.error)}</p>
          <div className="flex flex-wrap items-center gap-2">
            <button type="button" className="button" onClick={() => setup.refetch()}>
              Try again
            </button>
          </div>
        </MessageCard>
      </SetupPage>
    );
  }

  if (setup.isPending || mode.isPending || setup.data?.needsOwner) {
    return (
      <SetupPage>
        <MessageCard title="Sign in">
          <p className="m-0 text-11 text-faint">Loading…</p>
        </MessageCard>
      </SetupPage>
    );
  }

  if (invitation) {
    return (
      <SetupPage>
        <AcceptInvitation token={invitation} />
      </SetupPage>
    );
  }

  return (
    <SetupPage>
      <PasswordSignIn mode={mode.data} />
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

function PasswordSignIn({ mode }: { mode?: AuthMode }) {
  const router = useRouter();
  const queryClient = useQueryClient();
  // Set by the API when an agent's authorisation lands on /oauth/consent with no
  // session: the consent screen is served on the API's origin, so the way back is an
  // absolute URL rather than a route in this app.
  const next = useSearchParams().get("next");

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
      const target = safeNext(next, apiOrigin());
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
    // No form to submit, so no form: a card with the providers, or an honest dead end.
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

function AcceptInvitation({ token }: { token: string }) {
  const router = useRouter();
  const queryClient = useQueryClient();

  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [problem, setProblem] = useState<string | null>(null);

  const accept = useMutation({
    mutationFn: api.acceptInvitation,
    onSuccess: (me) => {
      queryClient.setQueryData(keys.me, me);
      // Straight into the wizard: an invited member still has a preferences step,
      // and it is the only one they will ever be shown.
      router.replace("/setup");
    },
  });

  const apiField = fieldFromDetail(accept.error);
  const apiMessage = accept.error ? messageFor(accept.error) : null;

  return (
    <FormCard
      title="Accept your invitation"
      intro="Set the password you will sign in with. The link works once."
      primaryLabel="Create account"
      pending={accept.isPending}
      error={apiMessage && !apiField ? apiMessage : null}
      onSubmit={() => {
        const found = passwordProblem(password);
        setProblem(found);
        if (found) return;
        accept.mutate({ token, email: email.trim(), displayName: displayName.trim(), password });
      }}
    >
      <TextField
        label="Email"
        type="email"
        autoFocus
        required
        autoComplete="email"
        value={email}
        error={apiField === "email" ? apiMessage : null}
        onChange={(event) => setEmail(event.target.value)}
      />

      <TextField
        label="Display name"
        required
        autoComplete="name"
        value={displayName}
        onChange={(event) => setDisplayName(event.target.value)}
      />

      <TextField
        label="Password"
        type="password"
        required
        autoComplete="new-password"
        value={password}
        hint={`${PASSWORD_MIN} characters minimum — ${password.length} so far.`}
        error={problem ?? (apiField === "password" ? apiMessage : null)}
        onChange={(event) => {
          setPassword(event.target.value);
          setProblem(null);
        }}
      />
    </FormCard>
  );
}
