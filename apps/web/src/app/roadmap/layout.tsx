import type { Metadata } from "next";
import type { ReactNode } from "react";
import { PublicShell } from "@/components/publik/shell";

export const metadata: Metadata = {
  title: "Roadmap — Kanso",
  description: "What we are working on. The tickets marked public, with open voting.",
};

/**
 * A nested layout rather than an edit to the root one, which is frozen for the fan-out.
 * It cannot remove the app's providers — the query client and the realtime connection
 * wrap every route — but it replaces the chrome, which is what decides whether a page
 * reads as a site or as a tool.
 */
export default function RoadmapLayout({ children }: { children: ReactNode }) {
  return <PublicShell current="roadmap">{children}</PublicShell>;
}
