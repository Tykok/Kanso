"use client";

import { ACCENTS, DENSITIES, THEMES, type Preferences } from "@/lib/api";
import { usePreferences, useSavePreferences } from "@/lib/queries";
import { DENSITY_LABELS, Segmented, THEME_LABELS, Toggle } from "./panel";

/**
 * No Save button, on purpose.
 *
 * Appearance is judged by looking at it, so each click applies to the whole page at
 * once and persists in the background. A preview tile would ask you to imagine the
 * result; this shows it. A failed save rolls the interface back, which is the honest
 * signal that nothing was stored.
 */
export function AppearanceSection() {
  const preferences = usePreferences();
  const save = useSavePreferences();
  const set = (patch: Partial<Preferences>) => save.mutate(patch);

  return (
    <section className="settings-section">
      <h2>Appearance</h2>

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
              // The palette is defined once in CSS; the swatch borrows it by wearing
              // the attribute, so a colour can never be stated twice.
              data-accent={accent}
              aria-label={accent}
              aria-pressed={preferences.accent === accent}
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
          options={DENSITIES.map((density) => ({ value: density, label: DENSITY_LABELS[density] }))}
          onChange={(density) => set({ density })}
        />
      </div>

      <Toggle
        label="Sidebar"
        value={preferences.sidebarVisible}
        onChange={(sidebarVisible) => set({ sidebarVisible })}
      />
      <Toggle
        label="Notion sync badges"
        value={preferences.showSyncBadges}
        onChange={(showSyncBadges) => set({ showSyncBadges })}
      />
      <Toggle
        label="Status bar"
        value={preferences.showStatusBar}
        onChange={(showStatusBar) => set({ showStatusBar })}
      />

      {save.isError && (
        <div className="settings-note error">
          Not saved: {(save.error as Error).message}. The interface went back to what is stored.
        </div>
      )}
    </section>
  );
}
