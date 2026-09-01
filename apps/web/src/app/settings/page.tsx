"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AccountSection } from "@/components/settings/account-section";
import { AgentsSection } from "@/components/settings/agents-section";
import { AppearanceSection } from "@/components/settings/appearance-section";
import { ConnectionsSection } from "@/components/settings/connections-section";
import { SettingsNote } from "@/components/settings/field";
import { NotionPeopleSection } from "@/components/settings/notion-people-section";
import { PeopleSection } from "@/components/settings/people-section";
import { VelocitySection } from "@/components/settings/velocity-section";
import { ApiError } from "@/lib/api";
import { useMe, useSetupState } from "@/lib/queries";
import { canConfigure as configures } from "@/lib/seat";

type SectionId = "appearance" | "account" | "velocity" | "people" | "connections" | "agents";

const SECTION_NAMES: Record<SectionId, string> = {
  appearance: "Appearance",
  account: "Account",
  velocity: "Velocity",
  people: "People",
  connections: "Connections",
  agents: "Agents",
};

export default function SettingsPage() {
  const router = useRouter();
  const me = useMe();
  const setup = useSetupState();
  const [section, setSection] = useState<SectionId>("appearance");

  const signedOut = me.error instanceof ApiError && me.error.status === 401;

  useEffect(() => {
    if (signedOut) router.replace("/login");
  }, [signedOut, router]);

  if (me.isLoading) return <div className="centered">Loading…</div>;
  if (signedOut || !me.data) return <div className="centered">Signing in…</div>;

  const canConfigure = configures(me.data.user.instanceRole);
  // "agents" is in both arrays, and that is the point: a grant belongs to the person who
  // made it, so every member manages their own — there is nothing here for an admin to
  // administer, and no list of anyone else's for them to see.
  // "velocity" is in both arrays for the same reason "agents" is: it is a fact about how
  // you work, declared by you, and there is nothing in it for an admin to administer.
  const sections: SectionId[] = canConfigure
    ? ["appearance", "account", "velocity", "people", "connections", "agents"]
    : // A member has nothing to manage about other people, but still sees the
      // connections read-only: the mirror affects their tickets.
      ["appearance", "account", "velocity", "connections", "agents"];

  return (
    <div className="mx-auto flex max-w-[760px] flex-col gap-5 px-5 pb-16 pt-6">
      <header className="flex items-baseline gap-3 border-b border-border pb-4">
        <Link className="button" href="/">
          Back
        </Link>
        <h1 className="text-15 font-medium tracking-tight">Settings</h1>
        <span className="ml-auto text-11 text-faint">
          {me.data.user.email} · {me.data.user.instanceRole}
        </span>
      </header>

      <div className="grid grid-cols-1 items-start gap-4 pt-5 sm:grid-cols-[148px_1fr] sm:gap-7">
        <nav className="sticky top-5 flex flex-row flex-wrap gap-0.5 sm:flex-col sm:flex-nowrap">
          {sections.map((id) => (
            <button
              key={id}
              aria-current={id === section}
              onClick={() => setSection(id)}
              className="rounded-md px-2 py-1.5 text-left text-13 text-muted-foreground hover:bg-accent aria-current:bg-accent-soft aria-current:font-medium aria-current:text-foreground"
            >
              {SECTION_NAMES[id]}
            </button>
          ))}
        </nav>

        <main className="min-w-0">
          {section === "account" && <AccountSection me={me.data} />}
          {section === "appearance" && <AppearanceSection />}
          {section === "velocity" && <VelocitySection />}
          {section === "people" && canConfigure && <PeopleSection />}
          {section === "agents" && <AgentsSection />}
          {section === "connections" && (
            <div className="flex flex-col gap-6">
              {setup.data ? (
                <ConnectionsSection state={setup.data} canConfigure={canConfigure} />
              ) : (
                <section className="flex flex-col gap-6">
                  <h2 className="text-21 font-medium tracking-tight">Connections</h2>
                  <SettingsNote>
                    {setup.error ? "Instance configuration unavailable." : "Loading…"}
                  </SettingsNote>
                </section>
              )}
              {/*
               * Notion's own section, not folded into `ConnectionsSection` — that file is
               * already 375 lines about a different subject, and the person correspondence
               * outlives any one connection: it is what lets the mirror fill the `people`
               * property once it writes one at all.
               */}
              <NotionPeopleSection canConfigure={canConfigure} />
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
