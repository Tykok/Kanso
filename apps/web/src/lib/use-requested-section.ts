import { usePathname, useRouter, useSearchParams } from "next/navigation";

/**
 * The settings tab, read from `?section=` and written back to it.
 *
 * The address is the only source of truth, because every other arrangement ignores a link
 * sooner or later. Read once at mount, a link followed while already on `/settings` — the
 * sidebar's status, the connections card's `Open the sync queue` — changes the URL and
 * nothing else, since a same-route navigation does not remount the page. Following only
 * *changes* in the request still ignores the second click on the same link after a tab was
 * pressed, because the URL it leads to is the one already in the bar. Pressing a tab writes
 * the URL, so every link lands. `replace` rather than `push`: switching tabs is not
 * somewhere the back button should step through.
 *
 * An unknown value falls back rather than rendering nothing, which is what a hand-typed or
 * stale link deserves.
 */
export function useRequestedSection<T extends string>(
  names: Record<T, string>,
  // The tab set comes from `names`; a literal fallback must not narrow it to itself.
  fallback: NoInfer<T>,
): [T, (section: T) => void] {
  const requested = useSearchParams().get("section");
  const pathname = usePathname();
  const router = useRouter();
  const section = requested !== null && requested in names ? (requested as T) : fallback;
  const choose = (next: T) => router.replace(`${pathname}?section=${next}`, { scroll: false });
  return [section, choose];
}
