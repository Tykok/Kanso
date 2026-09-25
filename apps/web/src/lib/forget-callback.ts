/**
 * Removes an OAuth callback's answer from the address, and keeps the tab it landed on.
 *
 * The answer is stripped so a reload does not re-announce a connection made days ago. The
 * section stays, because the settings page reads its tab from `?section=` and Next follows
 * `replaceState`: clearing the whole query put a member who had just connected back on
 * Appearance, away from the note saying whether it worked.
 */
export function forgetCallback(section: string) {
  window.history.replaceState(null, "", `${window.location.pathname}?section=${section}`);
}
