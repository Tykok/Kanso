import { TicketPageView } from "@/components/views/ticket-page";

/**
 * Screen 03. `[key]` is the identifier people read out loud — `KAN-142`, not a UUID —
 * because a URL that cannot be dictated is not a link anybody shares.
 *
 * `params` is a promise in this version of Next; awaiting it here keeps the client
 * component below free of the routing API entirely.
 */
export default async function TicketRoute({ params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  return <TicketPageView ticketKey={decodeURIComponent(key)} />;
}
