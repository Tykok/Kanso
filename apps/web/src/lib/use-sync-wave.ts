import { useState } from "react";

/**
 * How far the current wave has drained, or `null` when there is nothing in flight.
 *
 * A wave starts at the first non-zero reading and ends at zero. Its size is the largest
 * number of remaining jobs seen in between, so a queue that grows mid-wave raises the peak
 * and the bar steps back rather than passing 100%. Remembered here rather than on the
 * server: a reload restarts the bar from what is left, and the count beside it stays the
 * authoritative number. A server-side wave would need a rule for what opens one and a row
 * to keep it in, for a bar that is decoration on a correct count.
 *
 * State adjusted during render rather than in an effect, so the bar never paints one frame
 * with the previous wave's peak.
 */
export function useSyncWave(remaining: number): number | null {
  const [peak, setPeak] = useState(0);
  const next = remaining > 0 ? Math.max(peak, remaining) : 0;
  if (next !== peak) setPeak(next);
  return remaining > 0 ? (next - remaining) / next : null;
}
