import type { Ticket, TicketLink, TicketLinkType } from "@/lib/api";

/**
 * The panel's rule, with no React and no DOM — `environment: "node"`, like
 * `lib/virtual.ts` and `components/me/work-buckets.ts`, and for the same reason: this is
 * where the rule is proved.
 *
 * One row of the wire becomes one of five sentences, and which one depends on the type
 * *and* the direction. A directed edge genuinely says two different things from its two
 * ends — "this blocks that" and "that is blocked by this" are the same row read by two
 * different readers — and collapsing them into one label would make the panel say the
 * opposite of the truth on half the tickets it draws.
 */
export type LinkGroup = { label: string; type: TicketLinkType; tickets: Ticket[] };

/**
 * Read from the point of view of the ticket whose page this is: the label completes the
 * sentence "this ticket …".
 *
 * A symmetric type gets one label whichever end asked, which is the whole content of
 * `symmetric` arriving on the wire — the client is not entitled to its own list of which
 * types have a direction, because a fourth type would then need changing in two places
 * and the copy that was forgotten would be this one.
 */
const labelOf = (link: TicketLink): string => {
  if (link.symmetric) return "Related to";
  if (link.type === "blocks") return link.outgoing ? "Blocks" : "Blocked by";
  return link.outgoing ? "Duplicates" : "Duplicated by";
};

/**
 * Fixed, and not the order the rows arrived in.
 *
 * "Blocked by" leads because it is the only one of the five that is about somebody else
 * owing this ticket something — the reader's next action, if there is one, is there. Then
 * what this ticket is holding up, then the two that retire it, then the loosest. A panel
 * whose sections moved about as edges were drawn would be a panel nobody learns the shape
 * of.
 */
const ORDER = ["Blocked by", "Blocks", "Duplicates", "Duplicated by", "Related to"];

/**
 * The panel's sections, in a fixed order, with the empty ones absent rather than drawn
 * as a heading over nothing.
 */
export function linkGroups(links: TicketLink[]): LinkGroup[] {
  const byLabel = new Map<string, LinkGroup>();
  for (const link of links) {
    const label = labelOf(link);
    const group = byLabel.get(label) ?? { label, type: link.type, tickets: [] };
    group.tickets.push(link.ticket);
    byLabel.set(label, group);
  }
  return ORDER.flatMap((label) => byLabel.get(label) ?? []);
}
