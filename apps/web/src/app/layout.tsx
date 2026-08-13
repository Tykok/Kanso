import type { Metadata } from "next";
import type { CSSProperties, ReactNode } from "react";
import { Public_Sans } from "next/font/google";
import { PREFERENCE_BOOTSTRAP_SCRIPT } from "@/lib/theme";
import { Providers } from "./providers";
import "./globals.css";

export const metadata: Metadata = {
  title: "Kanso",
  description: "A keyboard-first task tracker, mirrored to Notion",
};

/**
 * next/font/google downloads Public Sans at build time and serves it from the
 * app's own origin: nothing leaves the browser for Google at runtime, which is
 * what makes a web font acceptable in a container that claims no telemetry.
 * `adjustFontFallback` (on by default) sizes a fallback to the same metrics, so
 * the swap from system font to Public Sans doesn't reflow the page under it.
 * 400 and 500 are the only weights the design system uses; there is no bold.
 */
const publicSans = Public_Sans({
  subsets: ["latin"],
  weight: ["400", "500"],
  variable: "--font-public-sans",
  display: "swap",
});

/*
 * The seal's two kanji aren't in Public Sans, and the container this ships in may
 * have no CJK font installed at all — so a fallback stack matters here in a way it
 * doesn't for Latin text. This variable names only the family the @font-face in
 * globals.css declares; the fallback stack (Hiragino Sans, Yu Gothic) lives in
 * tokens.css alongside the rest of the type tokens, as `--font-seal`.
 */
const sealFontStyle = {
  "--font-noto-sans-jp": '"Noto Sans JP Seal"',
} as CSSProperties;

/**
 * suppressHydrationWarning covers <html> only: the bootstrap script sets its
 * attributes during parsing, so the markup React hydrates against is deliberately
 * not the markup the server sent.
 */
export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    <html
      lang="en"
      className={publicSans.variable}
      style={sealFontStyle}
      suppressHydrationWarning
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: PREFERENCE_BOOTSTRAP_SCRIPT }} />
      </head>
      <body>
        <Providers>{children}</Providers>
      </body>
    </html>
  );
}
