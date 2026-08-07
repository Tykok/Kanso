"use client";

import Link from "next/link";
import { ACCENTS, DENSITIES, THEMES, type Preferences } from "@/lib/api";
import { useMe, usePreferences, useSavePreferences, useSyncStatus } from "@/lib/queries";

export const THEME_LABELS: Record<(typeof THEMES)[number], string> = {
  system: "System",
  light: "Light",
  dark: "Dark",
};

export const DENSITY_LABELS: Record<(typeof DENSITIES)[number], string> = {
  comfortable: "Comfortable",
  compact: "Compact",
};

type Option<T> = { value: T; label: string };

/**
 * Everything here is a choice between two or three named things, so they all get the
 * same control. Buttons rather than radios: they are in the tab order as they are,
 * and a settings panel that needs its own arrow-key handling is a settings panel
 * competing with the list underneath it.
 */
export function Segmented<T extends string>({
  label,
  options,
  value,
  onChange,
}: {
  label: string;
  options: Option<T>[];
  value: T;
  onChange: (value: T) => void;
}) {
  return (
    <div className="segmented" role="group" aria-label={label}>
      {options.map((option) => (
        <button
          key={option.value}
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

export function Toggle({
  label,
  value,
  onChange,
}: {
  label: string;
  value: boolean;
  onChange: (value: boolean) => void;
}) {
  return (
    <div className="settings-row">
      <span>{label}</span>
      <Segmented
        label={label}
        value={value ? "shown" : "hidden"}
        options={[
          { value: "shown", label: "Shown" },
          { value: "hidden", label: "Hidden" },
        ]}
        onChange={(next) => onChange(next === "shown")}
      />
    </div>
  );
}

export function SettingsPanel({ onClose }: { onClose: () => void }) {
  const me = useMe();
  const preferences = usePreferences();
  const save = useSavePreferences();
  const sync = useSyncStatus();

  const set = (patch: Partial<Preferences>) => save.mutate(patch);
  const canReconfigure = me.data && me.data.user.instanceRole !== "member";

  const instanceSummary = !sync.data
    ? "Instance status unavailable"
    : sync.data.mirrorEnabled
      ? `Notion mirror on · ${sync.data.bootstrapped ? "bootstrapped" : "not bootstrapped yet"}`
      : "Notion mirror off";

  return (
    <div className="backdrop" onClick={onClose}>
      <div className="panel" onClick={(event) => event.stopPropagation()}>
        <div className="panel-header">
          <strong style={{ flex: 1 }}>Settings</strong>
          <button className="button" onClick={onClose}>
            Close
          </button>
        </div>

        {/* Escape and the list shortcuts stay with the window handler in page.tsx,
            which already refuses to act on the list while an overlay is open. */}
        <div className="panel-body">
          <div className="settings-group">
            <div className="settings-label">Appearance</div>

            <div className="settings-row">
              <span>Theme</span>
              <Segmented
                label="Theme"
                value={preferences.theme}
                options={THEMES.map((theme) => ({ value: theme, label: THEME_LABELS[theme] }))}
                onChange={(theme) => set({ theme })}
              />
            </div>

            <div className="settings-row">
              <span>Accent</span>
              <div className="swatches">
                {ACCENTS.map((accent) => (
                  <button
                    key={accent}
                    className="swatch"
                    // The palette is defined once, in CSS; the swatch borrows the
                    // one it stands for instead of restating six colours here.
                    data-accent={accent}
                    // Six unlabelled circles: the name has to be available somewhere.
                    title={accent}
                    aria-label={accent}
                    aria-pressed={accent === preferences.accent}
                    onClick={() => set({ accent })}
                  />
                ))}
              </div>
            </div>

            <div className="settings-row">
              <span>Density</span>
              <Segmented
                label="Density"
                value={preferences.density}
                options={DENSITIES.map((density) => ({
                  value: density,
                  label: DENSITY_LABELS[density],
                }))}
                onChange={(density) => set({ density })}
              />
            </div>

            <Toggle
              label="Sidebar"
              value={preferences.sidebarVisible}
              onChange={(sidebarVisible) => set({ sidebarVisible })}
            />
            <Toggle
              label="Sync badges"
              value={preferences.showSyncBadges}
              onChange={(showSyncBadges) => set({ showSyncBadges })}
            />
            <Toggle
              label="Status bar"
              value={preferences.showStatusBar}
              onChange={(showStatusBar) => set({ showStatusBar })}
            />

            {save.isError && (
              <div className="error settings-note">
                Not saved: {(save.error as Error).message}. The instance kept its previous setting.
              </div>
            )}
          </div>

          {/*
            Deliberately shallow. This overlay exists so a theme is two keystrokes
            away without leaving the list; account, people and connections are a
            page because they are forms, and a form in a transient overlay loses
            what you typed the moment something else takes focus.
          */}
          <div className="settings-group">
            <div className="settings-label">Instance</div>
            <div className="settings-note">{instanceSummary}</div>
            <div className="settings-row">
              <span className="settings-note">
                {canReconfigure
                  ? "Account, people and connections."
                  : "Account and your Notion identity."}
              </span>
              <Link className="button" href="/settings" onClick={onClose}>
                All settings
              </Link>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
