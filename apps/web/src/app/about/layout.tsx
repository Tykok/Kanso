import type { Metadata } from "next";
import type { ReactNode } from "react";
import { PublicShell } from "@/components/publik/shell";

export const metadata: Metadata = {
  title: "Kanso — the board and the page, without the two tools",
  description:
    "A keyboard-first task tracker that keeps a team's tickets and the documents " +
    "explaining them in one model. AGPL-3.0, self-hosted.",
};

/**
 * `/about`, not `/`. `/` is the application, and a landing page that took it would put
 * a shop window in front of the people who use the thing every day.
 */
export default function AboutLayout({ children }: { children: ReactNode }) {
  return <PublicShell current="about">{children}</PublicShell>;
}
