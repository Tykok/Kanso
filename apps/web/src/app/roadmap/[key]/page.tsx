import type { Metadata } from "next";
import { Contributor } from "@/components/publik/contributor";

/**
 * `params` is a promise in this version of Next, so the segment is awaited here and the
 * key handed to a client component. The fetch itself stays in the browser: these pages
 * are anonymous, and rendering them on the server would mean the Next process asking the
 * API on a visitor's behalf — a second, quieter caller of the only routes in the
 * application that answer without a session.
 */
export async function generateMetadata({
  params,
}: {
  params: Promise<{ key: string }>;
}): Promise<Metadata> {
  const { key } = await params;
  return { title: `${key} — Kanso` };
}

export default async function PublicTicketPage({ params }: { params: Promise<{ key: string }> }) {
  const { key } = await params;
  return <Contributor ticketKey={key.toUpperCase()} />;
}
