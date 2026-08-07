"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { AccountSection } from "@/components/settings/account-section";
import { AppearanceSection } from "@/components/settings/appearance-section";
import { ConnectionsSection } from "@/components/settings/connections-section";
import { PeopleSection } from "@/components/settings/people-section";
import { ApiError } from "@/lib/api";
import { useMe, useSetupState } from "@/lib/queries";
import "./settings.css";

type SectionId = "account" | "appearance" | "people" | "connections";

const SECTION_NAMES: Record<SectionId, string> = {
  account: "Account",
  appearance: "Appearance",
  people: "People",
  connections: "Connections",
};

export default function SettingsPage() {
  const router = useRouter();
  const me = useMe();
  const setup = useSetupState();
  const [section, setSection] = useState<SectionId>("account");

  const signedOut = me.error instanceof ApiError && me.error.status === 401;

  useEffect(() => {
    if (signedOut) router.replace("/login");
  }, [signedOut, router]);

  if (me.isLoading) return <div className="centered">Loading…</div>;
  if (signedOut || !me.data) return <div className="centered">Signing in…</div>;

  const canConfigure = me.data.user.instanceRole !== "member";
  const sections: SectionId[] = canConfigure
    ? ["account", "appearance", "people", "connections"]
    : // A member has nothing to manage about other people, but still sees the
      // connections read-only: the mirror affects their tickets.
      ["account", "appearance", "connections"];

  return (
    <div className="settings-page">
      <header className="settings-head">
        <Link className="button" href="/">
          Back
        </Link>
        <h1>Settings</h1>
        <span className="settings-note">
          {me.data.user.email} · {me.data.user.instanceRole}
        </span>
      </header>

      <div className="settings-body">
        <nav className="settings-nav">
          {sections.map((id) => (
            <button key={id} aria-current={id === section} onClick={() => setSection(id)}>
              {SECTION_NAMES[id]}
            </button>
          ))}
        </nav>

        <main className="settings-main">
          {section === "account" && <AccountSection me={me.data} />}
          {section === "appearance" && <AppearanceSection />}
          {section === "people" && canConfigure && <PeopleSection />}
          {section === "connections" &&
            (setup.data ? (
              <ConnectionsSection state={setup.data} canConfigure={canConfigure} />
            ) : (
              <section className="settings-section">
                <h2>Connections</h2>
                <span className="settings-note">
                  {setup.error ? "Instance configuration unavailable." : "Loading…"}
                </span>
              </section>
            ))}
        </main>
      </div>
    </div>
  );
}
