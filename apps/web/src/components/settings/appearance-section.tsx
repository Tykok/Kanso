"use client";

import { DENSITIES, THEMES, type Preferences } from "@/lib/api";
import {
  ACCENT_HINT,
  DENSITY_HINT,
  DENSITY_LABELS,
  SIDEBAR_HINT,
  STATUS_BAR_HINT,
  SYNC_BADGES_HINT,
  THEME_HINT,
  THEME_LABELS,
} from "@/lib/preferences-copy";
import { PreferencePreview } from "@/components/setup/preview";
import { usePreferences, useSavePreferences } from "@/lib/queries";
import { SettingsField } from "./field";
import { AccentSwatches, Segmented, Toggle } from "./panel";

/**
 * No Save button, on purpose.
 *
 * Appearance is judged by looking at it, so each click applies to the whole page at
 * once and persists in the background. A preview tile would ask you to imagine the
 * result; this shows it. A failed save rolls the interface back, which is the honest
 * signal that nothing was stored. The tile itself is the wizard's own — one preview,
 * not a settings copy of it that could drift.
 */
export function AppearanceSection() {
  const preferences = usePreferences();
  const save = useSavePreferences();
  const set = (patch: Partial<Preferences>) => save.mutate(patch);

  return (
    <section className="flex flex-col gap-6">
      <h2 className="text-21 font-medium tracking-tight">Appearance</h2>

      <div className="flex flex-col gap-4">
        <SettingsField label="Theme" hint={THEME_HINT}>
          <Segmented
            label="Theme"
            value={preferences.theme}
            options={THEMES.map((theme) => ({ value: theme, label: THEME_LABELS[theme] }))}
            onChange={(theme) => set({ theme })}
          />
        </SettingsField>

        <SettingsField label="Accent" hint={ACCENT_HINT}>
          <AccentSwatches value={preferences.accent} onChange={(accent) => set({ accent })} />
        </SettingsField>

        <SettingsField label="Density" hint={DENSITY_HINT}>
          <Segmented
            label="Density"
            value={preferences.density}
            options={DENSITIES.map((density) => ({ value: density, label: DENSITY_LABELS[density] }))}
            onChange={(density) => set({ density })}
          />
        </SettingsField>

        <SettingsField label="Sidebar" hint={SIDEBAR_HINT}>
          <Toggle
            bare
            label="Sidebar"
            value={preferences.sidebarVisible}
            onChange={(sidebarVisible) => set({ sidebarVisible })}
          />
        </SettingsField>

        <SettingsField label="Sync badges" hint={SYNC_BADGES_HINT}>
          <Toggle
            bare
            label="Sync badges"
            value={preferences.showSyncBadges}
            onChange={(showSyncBadges) => set({ showSyncBadges })}
          />
        </SettingsField>

        <SettingsField label="Status bar" hint={STATUS_BAR_HINT}>
          <Toggle
            bare
            label="Status bar"
            value={preferences.showStatusBar}
            onChange={(showStatusBar) => set({ showStatusBar })}
          />
        </SettingsField>
      </div>

      <div className="flex flex-col gap-2">
        <span className="text-11 uppercase tracking-wide text-faint">Preview</span>
        <PreferencePreview preferences={preferences} />
        <span className="text-11 text-faint">
          Everything applies to the whole application, this preview included.
        </span>
      </div>

      {save.isError && (
        <div className="text-11 text-urgent">
          Not saved: {(save.error as Error).message}. The interface went back to what is stored.
        </div>
      )}
    </section>
  );
}
