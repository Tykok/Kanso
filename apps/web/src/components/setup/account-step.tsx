"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { useState, type ReactNode } from "react";
import { api } from "@/lib/api";
import { keys } from "@/lib/queries";
import { setupKeys } from "./data";
import {
  PASSWORD_MIN,
  TextField,
  fieldFromDetail,
  messageFor,
  passwordProblem,
} from "./fields";
import { FormCard } from "./frame";

/**
 * Step 0. Deliberately the one step without a Skip: every later step writes through
 * an authenticated session, so there would be nothing on the other side of it. An
 * operator who already has an account signs in instead.
 */
export function AccountStep({ head, onDone }: { head: ReactNode; onDone: () => void }) {
  const queryClient = useQueryClient();

  const [email, setEmail] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [problem, setProblem] = useState<string | null>(null);

  const create = useMutation({
    mutationFn: api.createOwner,
    onSuccess: (me) => {
      // The response is the identity behind the cookie the API just set; seeding it
      // saves a round trip and tells the next steps they are talking to the owner.
      queryClient.setQueryData(keys.me, me);
      queryClient.invalidateQueries({ queryKey: setupKeys.state });
      onDone();
    },
  });

  const apiField = fieldFromDetail(create.error);
  const apiMessage = create.error ? messageFor(create.error) : null;

  return (
    <FormCard
      head={head}
      title="Create your account"
      intro="The first account claims this instance and becomes its owner. Everyone else arrives through an invitation link."
      primaryLabel="Create account"
      pending={create.isPending}
      error={apiMessage && !apiField ? apiMessage : null}
      onSubmit={() => {
        const found = passwordProblem(password, confirm);
        setProblem(found);
        if (found) return;
        create.mutate({ email: email.trim(), displayName: displayName.trim(), password });
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

      <TextField
        label="Confirm password"
        type="password"
        required
        autoComplete="new-password"
        value={confirm}
        onChange={(event) => {
          setConfirm(event.target.value);
          setProblem(null);
        }}
      />

      <p className="m-0 text-11 text-faint">
        There is no password reset: the owner regenerates an invitation link instead.
      </p>
    </FormCard>
  );
}
