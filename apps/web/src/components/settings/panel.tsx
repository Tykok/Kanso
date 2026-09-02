"use client";

import Link from "next/link";
import { ACCENTS, DENSITIES, SIDEBAR_MODES, THEMES, type Preferences } from "@/lib/api";
import { DENSITY_LABELS, SIDEBAR_MODE_LABELS, THEME_LABELS } from "@/lib/preferences-copy";
import { useMe, usePreferences, useSavePreferences, useSyncStatus } from "@/lib/queries";
import { canConfigure as configures } from "@/lib/seat";
import { Backdrop } from "@/components/overlays";
import { Button } from "@/components/ui/button";

export { DENSITY_LABELS, THEME_LABELS };

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
    <div
      className="inline-flex overflow-hidden rounded-md border border-border"
      role="group"
      aria-label={label}
    >
      {options.map((option) => (
        <button
          key={option.value}
          type="button"
          aria-pressed={option.value === value}
          onClick={() => onChange(option.value)}
          className="border-l border-border px-3 py-1 text-12 text-muted-foreground first:border-l-0 hover:bg-accent aria-pressed:bg-accent-soft aria-pressed:font-medium aria-pressed:text-foreground"
        >
          {option.label}
        </button>
      ))}
    </div>
  );
}

/**
 * Shown/Hidden, the either/or most panel settings reduce to — but no longer the sidebar,
 * which has three modes and so reaches for [Segmented] directly. `bare` drops the
 * visible label: the compact `,` panel wants the row to carry it, but
 * `AppearanceSection` already draws a label and a hint of its own next to the
 * control, through `SettingsField` — with both, the name would be printed twice.
 */
export function Toggle({
  label,
  value,
  onChange,
  bare,
}: {
  label: string;
  value: boolean;
  onChange: (value: boolean) => void;
  bare?: boolean;
}) {
  const control = (
    <Segmented
      label={label}
      value={value ? "shown" : "hidden"}
      options={[
        { value: "shown", label: "Shown" },
        { value: "hidden", label: "Hidden" },
      ]}
      onChange={(next) => onChange(next === "shown")}
    />
  );

  if (bare) return control;

  return (
    <div className="flex min-h-[26px] items-center gap-3">
      <span className="flex-1 text-13">{label}</span>
      {control}
    </div>
  );
}

/**
 * The six accent presets, drawn as bare circles rather than as a radio group: six
 * unlabelled swatches read faster than six named ones, and the name is still in the
 * accessibility tree through `aria-label`. Each swatch wears the accent it stands
 * for as a `data-accent` attribute, which is what lets it show that colour without
 * this component stating a single one of the six itself — `tokens.css` scopes
 * `--primary` to whatever carries the attribute, and `bg-primary` reads it back.
 *
 * Used here and in `AppearanceSection`; the wizard's own accent picker needs a
 * visible label next to each swatch and is a different composition, not this one.
 */
export function AccentSwatches({
  value,
  onChange,
}: {
  value: Preferences["accent"];
  onChange: (accent: Preferences["accent"]) => void;
}) {
  return (
    <div className="flex gap-2.5" role="group" aria-label="Accent">
      {ACCENTS.map((accent) => (
        <button
          key={accent}
          type="button"
          data-accent={accent}
          title={accent}
          aria-label={accent}
          aria-pressed={accent === value}
          onClick={() => onChange(accent)}
          className="size-[22px] shrink-0 rounded-full bg-primary aria-pressed:outline-2 aria-pressed:outline-primary aria-pressed:outline-offset-[3px]"
        />
      ))}
    </div>
  );
}

export function SettingsPanel({ onClose }: { onClose: () => void }) {
  const me = useMe();
  const preferences = usePreferences();
  const save = useSavePreferences();
  const sync = useSyncStatus();

  const set = (patch: Partial<Preferences>) => save.mutate(patch);
  const canReconfigure = me.data !== undefined && configures(me.data.user.instanceRole);

  const instanceSummary = !sync.data
    ? "Instance status unavailable"
    : sync.data.mirrorEnabled
      ? `Notion mirror on · ${sync.data.bootstrapped ? "bootstrapped" : "not bootstrapped yet"}`
      : "Notion mirror off";

  return (
    <Backdrop onClose={onClose}>
      <div data-testid="panel-header" className="flex items-center gap-2.5 px-4 py-3">
        <h2 className="flex-1 text-15 font-medium text-foreground">Settings</h2>
        <Button type="button" variant="outline" size="sm" onClick={onClose}>
          Close
        </Button>
      </div>

      {/* Escape and the list shortcuts stay with the window handler in page.tsx,
          which already refuses to act on the list while an overlay is open. */}
      <div className="flex max-h-[70vh] flex-col gap-5 overflow-y-auto px-4 pb-4">
        <div className="flex flex-col gap-2.5">
          <div className="text-11 font-medium uppercase tracking-wide text-faint">Appearance</div>

          <div className="flex min-h-[26px] items-center gap-3">
            <span className="flex-1 text-13">Theme</span>
            <Segmented
              label="Theme"
              value={preferences.theme}
              options={THEMES.map((theme) => ({ value: theme, label: THEME_LABELS[theme] }))}
              onChange={(theme) => set({ theme })}
            />
          </div>

          <div className="flex min-h-[26px] items-center gap-3">
            <span className="flex-1 text-13">Accent</span>
            <AccentSwatches value={preferences.accent} onChange={(accent) => set({ accent })} />
          </div>

          <div className="flex min-h-[26px] items-center gap-3">
            <span className="flex-1 text-13">Density</span>
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

          {/* The row `Toggle` would have drawn, drawn by hand: three modes do not fit
              through a Shown/Hidden control, and the `,` panel has no room for a hint
              explaining what the two quiet ones do — `/settings` carries that copy. */}
          <div className="flex min-h-[26px] items-center gap-3">
            <span className="flex-1 text-13">Sidebar</span>
            <Segmented
              label="Sidebar"
              value={preferences.sidebarMode}
              options={SIDEBAR_MODES.map((mode) => ({
                value: mode,
                label: SIDEBAR_MODE_LABELS[mode],
              }))}
              onChange={(sidebarMode) => set({ sidebarMode })}
            />
          </div>
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
          <Toggle
            label="View controls"
            value={preferences.showViewControls}
            onChange={(showViewControls) => set({ showViewControls })}
          />

          {save.isError && (
            <div className="text-11 text-urgent">
              Not saved: {(save.error as Error).message}. The instance kept its previous setting.
            </div>
          )}
        </div>

        {/*
          Deliberately shallow. This overlay exists so a theme is two keystrokes
          away without leaving the list; account, people and connections are a
          page because they are forms, and a form in a transient overlay loses
          what you typed the moment something else takes focus.

          §6.5's shortcuts table is not here either, and it is the clearest case of
          the rule rather than an exception to it. Three reasons, any one of them
          enough. It is sixty-nine rows across four columns with a search box over
          them — this panel is 70vh of a column narrower than the table's own
          header. Its interactive part is a *key capture*, inside an overlay whose
          own `Escape` closes it: the two gestures collide on the one key that has
          to mean "cancel this capture" and does not, so a reader escaping a wrong
          combination would lose the panel instead. And a keyboard is not judged by
          looking at it, which is the whole argument for putting appearance in a
          transient overlay — you judge it by pressing the keys somewhere else, and
          the place you can do that from is a page you can leave behind. So the
          panel points at it, and that is the entire integration.
        */}
        <div className="flex flex-col gap-2.5">
          <div className="text-11 font-medium uppercase tracking-wide text-faint">Instance</div>
          <div className="text-11 text-faint">{instanceSummary}</div>
          <div className="flex min-h-[26px] items-center gap-3">
            <span className="text-11 text-faint">
              {canReconfigure
                ? "Shortcuts, account, people and connections."
                : "Shortcuts, account and your Notion identity."}
            </span>
            <Button asChild variant="outline" size="sm">
              <Link href="/settings" onClick={onClose}>
                All settings
              </Link>
            </Button>
          </div>
        </div>
      </div>
    </Backdrop>
  );
}
