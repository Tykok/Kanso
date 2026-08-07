import type { Metadata } from "next";
import type { ReactNode } from "react";
import { PREFERENCE_BOOTSTRAP_SCRIPT } from "@/lib/theme";
import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Kanso",
  description: "A keyboard-first task tracker, mirrored to Notion",
};

/**
 * No web fonts: the system stack renders immediately and the interface is judged
 * on how fast it answers a keystroke, not on its typeface.
 *
 * suppressHydrationWarning covers <html> only: the bootstrap script sets its
 * attributes during parsing, so the markup React hydrates against is deliberately
 * not the markup the server sent.
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html lang="en" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: PREFERENCE_BOOTSTRAP_SCRIPT }} />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
