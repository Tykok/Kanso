"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { PasswordSignIn } from "@/components/login";
import { useSetupState } from "@/components/setup/data";
import {
  PASSWORD_MIN,
  TextField,
  fieldFromDetail,
  messageFor,
  passwordProblem,
} from "@/components/setup/fields";
import { FormCard, MessageCard, SetupPage } from "@/components/setup/frame";
import { api } from "@/lib/api";
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
  const params = useSearchParams();
  const invitation = params.get("invite");
  const next = params.get("next");

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

  // The shell renders the same form on a 401 — see `components/login.tsx`. `next` is read
  // here because this route has the `Suspense` boundary the query string needs.
  return (
    <SetupPage>
      <PasswordSignIn mode={mode.data} next={next} />
    </SetupPage>
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
