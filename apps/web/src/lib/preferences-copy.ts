import type { Accent, Density, SidebarMode, Theme } from "./api";

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

export const SIDEBAR_MODE_LABELS: Record<SidebarMode, string> = {
  pinned: "Pinned",
  hover: "On hover",
  hidden: "Hidden",
};

/**
 * Three modes, and the hint has to say what the two quiet ones leave you: a reader who
 * picks "Hidden" and finds no way back is the complaint this whole pass answers, so the
 * way back is printed here rather than left to be discovered.
 */
export const SIDEBAR_HINT =
  "Teams and projects down the left. “On hover” reveals it from the left edge; " +
  "“Hidden” leaves only the panel button in the top bar. Hidden below 720px either way.";

export const SYNC_BADGES_HINT =
  "Per-row state of the Notion mirror. Worth keeping while the mirror is new.";

export const STATUS_BAR_HINT = "The keyboard reminders along the bottom.";

/**
 * No chords printed here on purpose. They are `Mod`-prefixed, so their text depends on
 * the platform *and* on what the reader has remapped — `formatChord` is the one place
 * that knows both, and a literal “⌘F” in this string would be wrong on Linux and wrong
 * again the moment somebody rebinds it.
 */
export const VIEW_CONTROLS_HINT =
  "Filter, Group and Order as buttons in the top bar. The keyboard reaches them either way.";
