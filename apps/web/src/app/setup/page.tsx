"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useCallback, useState } from "react";
import { AccountStep } from "@/components/setup/account-step";
import { setupKeys, useSetupState } from "@/components/setup/data";
import { DoneStep } from "@/components/setup/done-step";
import { messageFor } from "@/components/setup/fields";
import { MessageCard, SetupPage, StepRail, type StepId } from "@/components/setup/frame";
import { GoogleStep } from "@/components/setup/google-step";
import { NotionStep } from "@/components/setup/notion-step";
import { PreferencesStep } from "@/components/setup/preferences-step";
import {
  ApiError,
  DEFAULT_PREFERENCES,
  api,
  type InstanceRole,
  type Me,
  type Preferences,
  type SetupState,
} from "@/lib/api";
import { useMe } from "@/lib/queries";
import { applyPreferences } from "@/lib/theme";

/**
 * Notion and Google are instance-wide, so they belong to whoever owns the instance.
 * A member invited into an existing Kanso has nothing to decide there, and hiding
 * the steps beats showing them disabled: there is nothing to come back for.
 */
function buildPlan(state: SetupState, role?: InstanceRole): StepId[] {
  const plan: StepId[] = [];
  if (state.needsOwner) plan.push("account");
  if (state.needsOwner || role !== "member") plan.push("notion", "google");
  plan.push("preferences");
  return plan;
}

export default function SetupRoute() {
  const setup = useSetupState();
  const me = useMe();

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

  if (setup.isPending || me.isPending || !setup.data) {
    return (
      <SetupPage>
        <MessageCard title="Setup">
          <p className="m-0 text-11 text-faint">Reading the instance state…</p>
        </MessageCard>
      </SetupPage>
    );
  }

  // No account step to offer and no session to write with. Said rather than
  // redirected: a bounce between /setup and /login is the one way this can loop.
  if (!setup.data.needsOwner && me.error instanceof ApiError && me.error.status === 401) {
    return (
      <SetupPage>
        <MessageCard title="This instance already has an owner">
          <p className="m-0 text-13 text-muted-foreground">
            Sign in first — the rest of the wizard writes against your account.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <Link className="button button-primary" href="/login">
              Go to sign in
            </Link>
          </div>
        </MessageCard>
      </SetupPage>
    );
  }

  return (
    <SetupPage>
      <Wizard state={setup.data} me={me.data} />
    </SetupPage>
  );
}

/**
 * Mounted only once both queries have answered, which is what lets the plan be
 * frozen at mount. It has to be: creating the owner clears `needsOwner`, and a plan
 * that lost a step under the cursor would silently swallow the next one.
 */
function Wizard({ state, me }: { state: SetupState; me?: Me }) {
  const queryClient = useQueryClient();

  const [plan] = useState<StepId[]>(() => buildPlan(state, me?.user.instanceRole));
  const [index, setIndex] = useState(0);
  const [preferences, setPreferences] = useState<Preferences>(
    () => me?.preferences ?? DEFAULT_PREFERENCES,
  );

  const complete = useMutation({
    mutationFn: api.completeSetup,
    onSuccess: (next) => queryClient.setQueryData(setupKeys.state, next),
  });

  const canCompleteInstance = me ? me.user.instanceRole !== "member" : false;

  const advance = useCallback(() => {
    if (index < plan.length - 1) {
      setIndex(index + 1);
      return;
    }
    // Finishing or skipping the last step is what stops the instance asking. Only
    // someone who may configure the instance marks it done; a member finishing their
    // own preferences has nothing instance-wide to record, and asking anyway would
    // just spend a request on a guaranteed 403.
    if (!canCompleteInstance) {
      setIndex(plan.length);
      return;
    }
    complete.mutate(undefined, { onSettled: () => setIndex(plan.length) });
  }, [plan, index, complete, canCompleteInstance]);

  const writeState = useCallback(
    (next: SetupState) => queryClient.setQueryData(setupKeys.state, next),
    [queryClient],
  );

  const step: StepId | undefined = plan[index];
  const head = <StepRail plan={plan} index={index} />;
  const back = index > 0 ? () => setIndex(index - 1) : undefined;

  if (step === "account") return <AccountStep head={head} onDone={advance} />;

  if (step === "notion") {
    return (
      <NotionStep
        head={head}
        state={state}
        onState={writeState}
        onDone={advance}
        onSkip={advance}
        onBack={back}
      />
    );
  }

  if (step === "google") {
    return (
      <GoogleStep
        head={head}
        state={state}
        onState={writeState}
        onDone={advance}
        onSkip={advance}
        onBack={back}
      />
    );
  }

  if (step === "preferences") {
    return (
      <PreferencesStep
        head={head}
        value={preferences}
        onChange={(next) => {
          setPreferences(next);
          // Applied to the document immediately, saved only on Save. Picking a theme
          // and being shown a swatch instead of the theme is the one thing this
          // screen has to get right.
          applyPreferences(next);
        }}
        onDone={advance}
        onSkip={advance}
        onBack={back}
      />
    );
  }

  return (
    <DoneStep
      head={head}
      state={state}
      me={me}
      preferences={preferences}
      canInvite={me ? me.user.instanceRole !== "member" : false}
      onBack={() => setIndex(plan.length - 1)}
    />
  );
}
