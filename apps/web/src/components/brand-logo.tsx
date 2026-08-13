import { Seal } from "./ui/seal";

/**
 * What the interface shows while it is still finding out who you are.
 *
 * Deliberately static: no spinner, no pulse. These screens are meant to be gone inside a
 * second, and something that only begins moving as it disappears draws the eye to the
 * wait rather than covering it. Kanso is judged on how fast it answers a keystroke; an
 * animation here would advertise the one moment it cannot.
 */
export function BrandSplash({ label }: { label: string }) {
  return (
    <div className="centered">
      <Seal size={120} title="Kanso 簡素" />
      <span style={{ color: "var(--text-dim)" }}>{label}</span>
    </div>
  );
}
