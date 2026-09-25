import { useState } from "react";

/**
 * The settings tab: the one the address asked for, then whatever is pressed.
 *
 * Read at mount, as before, so pressing a tab never has to write the URL. A *new* request
 * — the sidebar's link to the queue, followed while already on `/settings` — is a
 * same-route navigation that does not remount the page, and read-once would leave the tab
 * where it was. So a change in the request is followed, and nothing else is.
 */
export function useRequestedSection<T extends string>(
  requested: T | null,
  fallback: T,
): [T, (section: T) => void] {
  const [section, setSection] = useState<T>(requested ?? fallback);
  const [seen, setSeen] = useState(requested);
  if (requested !== seen) {
    setSeen(requested);
    if (requested !== null) setSection(requested);
  }
  return [section, setSection];
}
