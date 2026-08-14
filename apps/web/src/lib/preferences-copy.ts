import type { Accent, Density, Theme } from "./api";

/**
 * How a preference is named and explained, wherever it is set.
 *
 * Shared between the settings page, the `,` quick panel and the onboarding wizard's
 * preferences step so the same choice is never worded three different ways —
 * before this module existed the wizard kept its own copy of every label.
 */
export const THEME_LABELS: Record<Theme, string> = {
  system: "System",
  light: "Light",
  dark: "Dark",
};

export const THEME_HINT = "“System” follows the OS setting.";

export const DENSITY_LABELS: Record<Density, string> = {
  comfortable: "Comfortable",
  compact: "Compact",
};

export const DENSITY_HINT = "Compact removes space, not text size.";

export const ACCENT_LABELS: Record<Accent, string> = {
  indigo: "Indigo",
  blue: "Blue",
  green: "Green",
  amber: "Amber",
  rose: "Rose",
  violet: "Violet",
};

export const ACCENT_HINT = "Selection, the primary action, keyboard focus.";

export const SIDEBAR_HINT = "Teams and projects down the left. Hidden below 720px either way.";

export const SYNC_BADGES_HINT =
  "Per-row state of the Notion mirror. Worth keeping while the mirror is new.";

export const STATUS_BAR_HINT = "The keyboard reminders along the bottom.";
