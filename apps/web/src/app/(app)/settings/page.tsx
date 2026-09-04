"use client";

import { useState } from "react";
import { AccountSection } from "@/components/settings/account-section";
import { AgentsSection } from "@/components/settings/agents-section";
import { AppearanceSection } from "@/components/settings/appearance-section";
import { ConnectionsSection } from "@/components/settings/connections-section";
import { SettingsNote } from "@/components/settings/field";
import { NotionPeopleSection } from "@/components/settings/notion-people-section";
import { PeopleSection } from "@/components/settings/people-section";
import { RequestBases } from "@/components/settings/request-bases";
import { ShortcutsSection } from "@/components/settings/shortcuts-section";
import { TokensSection } from "@/components/settings/tokens-section";
import { VelocitySection } from "@/components/settings/velocity-section";
import { useMe, useSetupState } from "@/lib/queries";
import { canConfigure as configures } from "@/lib/seat";

type SectionId =
  | "appearance"
  | "shortcuts"
  | "account"
  | "velocity"
  | "people"
  | "connections"
  | "agents"
  | "tokens";

const SECTION_NAMES: Record<SectionId, string> = {
  appearance: "Appearance",
  shortcuts: "Shortcuts",
  account: "Account",
  velocity: "Velocity",
  people: "People",
  connections: "Connections",
  agents: "Agents",
  tokens: "API tokens",
};

export default function SettingsPage() {
  const me = useMe();
  const setup = useSetupState();
  const [section, setSection] = useState<SectionId>("appearance");

  /**
   * The sign-in redirect this page ran from an effect is the shell's gate now, and so is
   * the loading splash. What is left is the one thing that is genuinely local: the page
   * cannot draw a section about somebody before it knows who they are.
   */
  if (!me.data) return <div className="centered">Loading…</div>;

  const canConfigure = configures(me.data.user.instanceRole);
  // "agents" is in both arrays, and that is the point: a grant belongs to the person who
  // made it, so every member manages their own — there is nothing here for an admin to
  // administer, and no list of anyone else's for them to see.
  // "velocity" is in both arrays for the same reason "agents" is: it is a fact about how
  // you work, declared by you, and there is nothing in it for an admin to administer.
  // "shortcuts" is in both arrays for the third time and for the same reason: a keyboard
  // belongs to one person, it is stored in their own preferences, and there is no
  // instance-wide keyboard for an admin to administer. It sits next to "appearance"
  // because the two are one question asked twice — how this application behaves for me.
  // "tokens" likewise, and most strictly of the four: `ApiTokenService` takes no user id
  // on any method a person calls, so an admin has no way to read or revoke somebody
  // else's credentials even if this array offered them the tab.
  const sections: SectionId[] = canConfigure
    ? [
        "appearance",
        "shortcuts",
        "account",
        "velocity",
        "people",
        "connections",
        "agents",
        "tokens",
      ]
    : // A member has nothing to manage about other people, but still sees the
      // connections read-only: the mirror affects their tickets.
      ["appearance", "shortcuts", "account", "velocity", "connections", "agents", "tokens"];

  return (
    <div className="mx-auto flex w-full max-w-[760px] flex-col gap-5 overflow-y-auto px-5 pb-16 pt-6">
      {/* The `Back` button that stood here is gone: it was a `<Link href="/">`, so it did
          not go back, it went home. The shell's `×` — and `esc`, which runs the same
          thing — is the way out of every destination now. */}
      <header className="flex items-baseline gap-3 border-b border-border pb-4">
        <h1 className="m-0 text-15 font-medium tracking-tight">Settings</h1>
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
          {section === "shortcuts" && <ShortcutsSection />}
          {section === "velocity" && <VelocitySection />}
          {section === "people" && canConfigure && <PeopleSection />}
          {section === "agents" && <AgentsSection />}
          {section === "tokens" && <TokensSection />}
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
              {/*
               * The requests base, beside the person correspondence and for the same reason
               * it is beside `ConnectionsSection` rather than inside it: that file is about
               * the mirror — what Kanso publishes, what Notion refused — and a requests base
               * is the one Notion relationship it has nothing to say about. `V37` keeps the
               * two apart in the schema, and `RequestBaseController` in its own class, on a
               * stronger version of the same argument.
               */}
              <RequestBases canConfigure={canConfigure} />
            </div>
          )}
        </main>
      </div>
    </div>
  );
}
