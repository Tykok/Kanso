"use client";

import {
  DENSITIES,
  OPEN_TICKET_MODES,
  openTicketMode,
  SIDEBAR_MODES,
  THEMES,
  type OpenTicketMode,
  type Preferences,
} from "@/lib/api";
import {
  ACCENT_HINT,
  DENSITY_HINT,
  DENSITY_LABELS,
  SIDEBAR_HINT,
  SIDEBAR_MODE_LABELS,
  STATUS_BAR_HINT,
  SYNC_BADGES_HINT,
  THEME_HINT,
  THEME_LABELS,
  VIEW_CONTROLS_HINT,
} from "@/lib/preferences-copy";
import { PreferencePreview } from "@/components/setup/preview";
import { usePreferences, useSaveOpenTicket, useSavePreferences } from "@/lib/queries";
import { SettingsField } from "./field";
import { AccentSwatches, Segmented, Toggle } from "./panel";

/**
 * The one preference slice A adds a control for, with its copy beside it.
 *
 * `lib/preferences-copy.ts` holds the other six hints and is not this branch's file, so
 * these two live here — next to the only control that reads them, which is where copy
 * this specific is easiest to keep true.
 */
const OPEN_TICKET_LABELS: Record<OpenTicketMode, string> = {
  panel: "Panel",
  page: "Page",
};

const OPEN_TICKET_HINT =
  "What ↵ does to the selected ticket. The panel keeps the list behind it; the page gives " +
  "the description room to be read. ⤢ and ⇧↵ expand the ticket in front of you either way, " +
  "without changing this.";

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
  const { setOpenTicket } = useSaveOpenTicket();
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

        {/*
          * A `Segmented` and not a `Toggle`, because the sidebar has three modes and
          * `Toggle` reduces everything to Shown/Hidden. This is also the only control
          * that reaches `hidden`: the `PanelLeft` buttons swap pinned and hover, and a
          * button whose third state you discover by pressing it twice is exactly what
          * this pass exists to remove.
          */}
        <SettingsField label="Sidebar" hint={SIDEBAR_HINT}>
          <Segmented
            label="Sidebar"
            value={preferences.sidebarMode}
            options={SIDEBAR_MODES.map((mode) => ({
              value: mode,
              label: SIDEBAR_MODE_LABELS[mode],
            }))}
            onChange={(sidebarMode) => set({ sidebarMode })}
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

        {/*
          * A `Toggle` and not three, because the three buttons are one decision: they
          * are the same intention drawn three times, and a reader who wants Filter but
          * not Group has a screen problem this setting could not fix.
          */}
        <SettingsField label="View controls" hint={VIEW_CONTROLS_HINT}>
          <Toggle
            bare
            label="View controls"
            value={preferences.showViewControls}
            onChange={(showViewControls) => set({ showViewControls })}
          />
        </SettingsField>

        {/*
          * Screen 02 draws this control in the foot of the ticket panel and its own
          * caption says the setting "lives in the preferences" — so it lives here, and
          * `⤢` and `⇧↵` still expand the ticket in front of you without changing it.
          *
          * The column behind it, `user_preferences.open_ticket`, is slice 0's; until it
          * exists the server answers this write with a row that carries no `openTicket`
          * and the control visibly reverts, which is the same honest signal a failed
          * theme change already gives.
          */}
        <SettingsField label="Open a ticket" hint={OPEN_TICKET_HINT}>
          <Segmented
            label="Open a ticket"
            value={openTicketMode(preferences)}
            options={OPEN_TICKET_MODES.map((mode) => ({
              value: mode,
              label: OPEN_TICKET_LABELS[mode],
            }))}
            onChange={setOpenTicket}
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
