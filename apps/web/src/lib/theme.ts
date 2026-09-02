import {
  ACCENTS,
  DENSITIES,
  DEFAULT_PREFERENCES,
  SIDEBAR_MODES,
  THEMES,
  type Preferences,
  type Theme,
} from "./api";

/**
 * Appearance is expressed as attributes on <html> and read by globals.css. Nothing
 * else in the app knows a colour: a preference changes one attribute and the whole
 * interface follows, including anything rendered by components this module has
 * never heard of.
 */
export const PREFERENCES_STORAGE_KEY = "kanso.preferences";

export function applyPreferences(preferences: Preferences) {
  const root = document.documentElement;
  root.dataset.theme = preferences.theme;
  root.dataset.accent = preferences.accent;
  root.dataset.density = preferences.density;
  root.dataset.syncBadges = preferences.showSyncBadges ? "on" : "off";
  // Not for the shell, which reads `preferences.sidebarMode` like any other component.
  // This is the attribute the bootstrap below can also set, so the grid is the right
  // shape in the first paint rather than a 248px column that collapses on hydration.
  // Set in both places so the two cannot disagree about what mode is in force.
  root.dataset.sidebar = preferences.sidebarMode;
  // The legacy palette resolves light-dark() off `color-scheme`, which data-theme
  // narrows; the shadcn tokens select on .dark. Two mechanisms, one source, set
  // together here so they cannot drift while the interface migrates between them.
  root.classList.toggle("dark", prefersDark(preferences.theme));
}

/** `system` is not a palette but a deferral, so it has to be resolved before use. */
export function prefersDark(theme: Theme): boolean {
  if (theme === "dark") return true;
  if (theme === "light") return false;
  return window.matchMedia("(prefers-color-scheme: dark)").matches;
}

/**
 * The mirror of the server copy, kept only so the first paint has something to go
 * on. Values are checked one by one rather than trusted: this string outlives
 * deploys and is one devtools edit away from being nonsense.
 */
export function readCachedPreferences(): Preferences {
  if (typeof window === "undefined") return DEFAULT_PREFERENCES;
  try {
    const raw = window.localStorage.getItem(PREFERENCES_STORAGE_KEY);
    if (!raw) return DEFAULT_PREFERENCES;
    const parsed = JSON.parse(raw) as Partial<Preferences>;
    return {
      ...DEFAULT_PREFERENCES,
      theme: oneOf(THEMES, parsed.theme) ?? DEFAULT_PREFERENCES.theme,
      accent: oneOf(ACCENTS, parsed.accent) ?? DEFAULT_PREFERENCES.accent,
      density: oneOf(DENSITIES, parsed.density) ?? DEFAULT_PREFERENCES.density,
      sidebarMode: oneOf(SIDEBAR_MODES, parsed.sidebarMode) ?? DEFAULT_PREFERENCES.sidebarMode,
      showSyncBadges: bool(parsed.showSyncBadges, DEFAULT_PREFERENCES.showSyncBadges),
      showStatusBar: bool(parsed.showStatusBar, DEFAULT_PREFERENCES.showStatusBar),
      showViewControls: bool(parsed.showViewControls, DEFAULT_PREFERENCES.showViewControls),
      shortcuts: bindings(parsed.shortcuts),
    };
  } catch {
    return DEFAULT_PREFERENCES;
  }
}

export function cachePreferences(preferences: Preferences) {
  try {
    window.localStorage.setItem(PREFERENCES_STORAGE_KEY, JSON.stringify(preferences));
  } catch {
    // Private browsing, a full quota: the server copy still holds, only the first
    // paint of the next load loses its head start.
  }
}

function oneOf<T extends string>(values: readonly T[], candidate: unknown): T | undefined {
  return values.find((value) => value === candidate);
}

function bool(candidate: unknown, fallback: boolean): boolean {
  return typeof candidate === "boolean" ? candidate : fallback;
}

/**
 * The cached overrides, entry by entry, dropping whatever is not an action id mapped to
 * chords. Shape only — the same split the server draws, and for the mirrored reason:
 * `mergeBindings` is what decides whether an id is an action and whether a chord is free.
 *
 * Carried through the cache at all so a remapped key works from the first keystroke
 * rather than from whenever /api/me lands; a bad entry is dropped rather than taking the
 * whole object down with it, because one unreadable binding must not cost the reader the
 * rest of their keyboard.
 */
function bindings(candidate: unknown): Record<string, string[]> {
  if (typeof candidate !== "object" || candidate === null || Array.isArray(candidate)) return {};
  return Object.fromEntries(
    Object.entries(candidate as Record<string, unknown>).filter(
      (entry): entry is [string, string[]] =>
        Array.isArray(entry[1]) && entry[1].every((chord) => typeof chord === "string"),
    ),
  );
}

/**
 * Runs in <head>, inline and blocking, and it has to be all three.
 *
 * The preferences live behind /api/me, so React cannot know the theme until a round
 * trip completes — and an external script, or the same code in an effect, both run
 * after the browser has already painted. A dark-mode user would get a white page
 * first. Parsed inline in the head, this executes before any pixel is drawn.
 *
 * Kept in this module so the storage key and the fallbacks cannot drift from the
 * ones the running application uses.
 *
 * The sidebar mode is here for the same reason the theme is: the column is part of the
 * layout, so a first paint that guesses `pinned` and hydrates into `hover` moves the
 * whole page sideways once. `SIDEBAR_MODES` is inlined rather than imported because this
 * string is executed before any module exists — which is also why the check is written
 * out by hand instead of reusing `oneOf`.
 */
export const PREFERENCE_BOOTSTRAP_SCRIPT = `(function(){try{
var p=JSON.parse(localStorage.getItem(${JSON.stringify(PREFERENCES_STORAGE_KEY)})||"{}"),d=document.documentElement;
var t=p.theme||${JSON.stringify(DEFAULT_PREFERENCES.theme)};
d.dataset.theme=t;
d.dataset.accent=p.accent||${JSON.stringify(DEFAULT_PREFERENCES.accent)};
d.dataset.density=p.density||${JSON.stringify(DEFAULT_PREFERENCES.density)};
d.dataset.syncBadges=p.showSyncBadges===false?"off":"on";
d.dataset.sidebar=${JSON.stringify(SIDEBAR_MODES)}.indexOf(p.sidebarMode)<0?${JSON.stringify(DEFAULT_PREFERENCES.sidebarMode)}:p.sidebarMode;
d.classList.toggle("dark",t==="dark"||(t!=="light"&&window.matchMedia("(prefers-color-scheme: dark)").matches));
}catch(e){}})()`;
