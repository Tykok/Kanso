"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import {
  ACCENTS,
  DENSITIES,
  THEMES,
  api,
  type Accent,
  type Density,
  type Preferences,
  type Theme,
} from "@/lib/api";
import { keys } from "@/lib/queries";
import { ChoiceGroup, Toggle, messageFor } from "./fields";
import { FormCard } from "./frame";
import { PreferencePreview } from "./preview";

const THEME_LABELS: Record<Theme, string> = {
  system: "Follow the system",
  light: "Light",
  dark: "Dark",
};

const DENSITY_LABELS: Record<Density, string> = {
  comfortable: "Comfortable",
  compact: "Compact",
};

const ACCENT_LABELS: Record<Accent, string> = {
  indigo: "Indigo",
  blue: "Blue",
  green: "Green",
  amber: "Amber",
  rose: "Rose",
  violet: "Violet",
};

type Props = {
  head: ReactNode;
  value: Preferences;
  onChange: (value: Preferences) => void;
  onDone: () => void;
  onSkip: () => void;
  onBack?: () => void;
};

export function PreferencesStep({ head, value, onChange, onDone, onSkip, onBack }: Props) {
  const queryClient = useQueryClient();

  const save = useMutation({
    mutationFn: api.savePreferences,
    onSuccess: () => {
      // Preferences ride along with /api/me, so the whole session identity is what
      // goes stale when they change — not some separate preferences cache.
      queryClient.invalidateQueries({ queryKey: keys.me });
      onDone();
    },
  });

  /**
   * Skipping still stamps the onboarding.
   *
   * "I'll keep the defaults" is an answer, and the guard on the inbox only knows
   * whether the stamp is there — leaving it unset would bounce the user straight
   * back into this step, forever.
   */
  const skip = useMutation({
    mutationFn: () => api.savePreferences({ onboarded: true }),
    onSettled: () => {
      queryClient.invalidateQueries({ queryKey: keys.me });
      onSkip();
    },
  });

  const set = <K extends keyof Preferences>(key: K, next: Preferences[K]) =>
    onChange({ ...value, [key]: next });

  return (
    <FormCard
      head={head}
      title="Preferences"
      intro="Yours alone, not the instance's. Changeable at any time from settings."
      primaryLabel="Save"
      pending={save.isPending || skip.isPending}
      error={save.error ? messageFor(save.error) : null}
      onSkip={() => skip.mutate()}
      onBack={onBack}
      onSubmit={() =>
        save.mutate({
          // Finishing this step is what marks the user as onboarded; the guard on the
          // inbox reads that stamp to decide whether to send them here.
          onboarded: true,
          theme: value.theme,
          accent: value.accent,
          density: value.density,
          sidebarVisible: value.sidebarVisible,
          showSyncBadges: value.showSyncBadges,
          showStatusBar: value.showStatusBar,
        })
      }
    >
      <ChoiceGroup
        label="Theme"
        name="theme"
        value={value.theme}
        options={THEMES.map((theme) => ({ value: theme, label: THEME_LABELS[theme] }))}
        onChange={(theme) => set("theme", theme)}
      />

      <div className="setup-field">
        <span className="setup-label">Accent</span>
        <div className="setup-accents" role="radiogroup" aria-label="Accent">
          {ACCENTS.map((accent) => (
            <label key={accent} className="setup-accent" data-accent={accent}>
              <input
                type="radio"
                name="accent"
                checked={value.accent === accent}
                onChange={() => set("accent", accent)}
              />
              <span className="setup-swatch" />
              <span>{ACCENT_LABELS[accent]}</span>
            </label>
          ))}
        </div>
      </div>

      <ChoiceGroup
        label="Density"
        name="density"
        value={value.density}
        options={DENSITIES.map((density) => ({ value: density, label: DENSITY_LABELS[density] }))}
        onChange={(density) => set("density", density)}
      />

      <div className="setup-toggles">
        <Toggle
          label="Sidebar"
          hint="Teams and projects down the left. Hidden below 720px either way."
          checked={value.sidebarVisible}
          onChange={(checked) => set("sidebarVisible", checked)}
        />
        <Toggle
          label="Sync badges"
          hint="Per-row state of the Notion mirror. Worth keeping while the mirror is new."
          checked={value.showSyncBadges}
          onChange={(checked) => set("showSyncBadges", checked)}
        />
        <Toggle
          label="Status bar"
          hint="The keyboard reminders along the bottom."
          checked={value.showStatusBar}
          onChange={(checked) => set("showStatusBar", checked)}
        />
      </div>

      <div className="setup-field">
        <span className="setup-label">Preview</span>
        <PreferencePreview preferences={value} />
        <span className="setup-hint">
          Everything applies to the whole app as you pick it, this preview included.
          Nothing is stored until you save.
        </span>
      </div>
    </FormCard>
  );
}
