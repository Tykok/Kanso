"use client";

import { useMutation, useQueryClient } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { ACCENTS, DENSITIES, THEMES, api, type Preferences } from "@/lib/api";
import {
  ACCENT_LABELS,
  DENSITY_LABELS,
  SIDEBAR_HINT,
  STATUS_BAR_HINT,
  SYNC_BADGES_HINT,
  THEME_LABELS,
} from "@/lib/preferences-copy";
import { keys } from "@/lib/queries";
import { ChoiceGroup, Toggle, messageFor } from "./fields";
import { FormCard } from "./frame";
import { PreferencePreview } from "./preview";

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

      <ChoiceGroup
        label="Accent"
        name="accent"
        value={value.accent}
        options={ACCENTS.map((accent) => ({ value: accent, label: ACCENT_LABELS[accent], accent }))}
        onChange={(accent) => set("accent", accent)}
      />

      <ChoiceGroup
        label="Density"
        name="density"
        value={value.density}
        options={DENSITIES.map((density) => ({ value: density, label: DENSITY_LABELS[density] }))}
        onChange={(density) => set("density", density)}
      />

      <div className="flex flex-col gap-2.5">
        <Toggle
          label="Sidebar"
          hint={SIDEBAR_HINT}
          checked={value.sidebarVisible}
          onChange={(checked) => set("sidebarVisible", checked)}
        />
        <Toggle
          label="Sync badges"
          hint={SYNC_BADGES_HINT}
          checked={value.showSyncBadges}
          onChange={(checked) => set("showSyncBadges", checked)}
        />
        <Toggle
          label="Status bar"
          hint={STATUS_BAR_HINT}
          checked={value.showStatusBar}
          onChange={(checked) => set("showStatusBar", checked)}
        />
      </div>

      <div className="flex flex-col gap-1.5">
        <span className="text-11 uppercase tracking-wide text-faint">Preview</span>
        <PreferencePreview preferences={value} />
        <span className="text-11 text-faint">
          Everything applies to the whole app as you pick it, this preview included.
          Nothing is stored until you save.
        </span>
      </div>
    </FormCard>
  );
}
