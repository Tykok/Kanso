"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { AccountStep } from "@/components/setup/account-step";
import { useSetupState } from "@/components/setup/data";
import { messageFor } from "@/components/setup/fields";
import { MessageCard, SetupPage } from "@/components/setup/frame";
import { ApiError } from "@/lib/api";
import { useMe } from "@/lib/queries";

/**
 * Claiming the instance, and nothing else.
 *
 * This was four steps: the account, Notion, Google, preferences, and a summary of the
 * three that could be skipped. Every one of those had a settings screen of its own, and
 * the wizard's copy was the one nobody came back to. What is left is the single
 * irreversible act — creating the owner — and `onboarded_at` is stamped by the endpoint
 * that performs it, so nothing sends anybody back here afterwards.
 */
export default function SetupRoute() {
  const router = useRouter();
  const setup = useSetupState();
  const me = useMe();

  const signedIn = me.data !== undefined;
  const claimed = setup.data !== undefined && !setup.data.needsOwner;

  useEffect(() => {
    // Nothing to claim and somebody to be: the board is where they were going.
    if (claimed && signedIn) router.replace("/");
  }, [claimed, signedIn, router]);

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

  // Said rather than redirected: a bounce between /setup and /login is the one way this
  // can loop, and an owner who is merely signed out is not an error.
  if (claimed && me.error instanceof ApiError && me.error.status === 401) {
    return (
      <SetupPage>
        <MessageCard title="This instance already has an owner">
          <p className="m-0 text-13 text-muted-foreground">
            Sign in — everything the wizard used to ask for is in settings.
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

  if (!setup.data.needsOwner) {
    return (
      <SetupPage>
        <MessageCard title="Setup">
          <p className="m-0 text-11 text-faint">Opening Kanso…</p>
        </MessageCard>
      </SetupPage>
    );
  }

  return (
    <SetupPage>
      <AccountStep onDone={() => router.replace("/")} />
    </SetupPage>
  );
}
