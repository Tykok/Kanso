import { CycleView } from "@/components/organise/cycle-view";

/**
 * Screen 19. `[number]` is a cycle number or the literal `current`, which is what the
 * sidebar's Cycle row links to — resolving "which cycle is in progress" is the server's
 * job, not a guess the client makes before it has the list.
 *
 * `params` is a Promise in Next 16, so this stays a server component and awaits it; the
 * screen itself is a client component because it owns a keyboard and four queries.
 */
export default async function CyclePage({ params }: { params: Promise<{ number: string }> }) {
  const { number } = await params;
  return <CycleView number={number} />;
}
