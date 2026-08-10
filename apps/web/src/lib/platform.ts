/**
 * Which modifier this reader's keyboard carries.
 *
 * Read at render rather than resolved once at import, and safe to do so: `page.tsx`
 * returns `Loading…` while `me`, `authMode` or `setup` are in flight, and on the server
 * all three always are — so the server's HTML contains no menu, no overlay and no status
 * bar. None of the surfaces that print a key exists at hydration, so none can mismatch.
 * `view.tsx` already makes the same trade when it resolves the reader's timezone.
 *
 * `navigator.platform` is deprecated and is still the only thing every browser answers
 * the same way; the user-agent string is the fallback, and a wrong guess costs a printed
 * label, never a keystroke — `page.tsx` answers ⌘K and Ctrl+K with one test either way.
 */
export function isMac(): boolean {
  if (typeof navigator === "undefined") return false;
  return /mac/i.test(navigator.platform || navigator.userAgent);
}
