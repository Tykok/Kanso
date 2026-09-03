import type { Metadata, Viewport } from "next";
import type { CSSProperties, ReactNode } from "react";
import { Public_Sans } from "next/font/google";
import { PREFERENCE_BOOTSTRAP_SCRIPT } from "@/lib/theme";
import { Providers } from "./providers";
import "./globals.css";

/**
 * The description is the one sentence a search result shows, so it says what Kanso
 * manages rather than what it resembles: a reader who only learns that it is like two
 * products they already pay for has no reason to run it.
 */
export const metadata: Metadata = {
  title: "Kanso",
  description:
    "Kanso runs a project end to end — projects, tickets, cycles, a roadmap, a timeline, workload — and everyone else reads the same data in Notion.",
};

/**
 * The colour the OS paints its own chrome with when Kanso is installed — the title bar of
 * a standalone window, the status bar on a phone (KAN-24).
 *
 * A pair rather than one value, and that is the point of putting it here instead of in
 * `manifest.ts`: a manifest holds a single `theme_color`, so an installed dark instance
 * would wear a white title bar above a near-black app. These are `--background` from
 * `styles/tokens.css` converted to sRGB — `oklch(0.988 0.002 262)` and
 * `oklch(0.185 0.008 262)` — so the chrome is the same ground as the page under it.
 *
 * Keyed on `prefers-color-scheme` and so on the *system* setting, which is the only signal
 * the browser has before any script runs. Somebody who has chosen light inside Kanso on a
 * dark system gets a dark title bar over a light app; the alternative is a `<meta>` the
 * preference bootstrap rewrites, which would flash the wrong colour on every load to fix
 * a mismatch only that reader can see.
 */
export const viewport: Viewport = {
  themeColor: [
    { media: "(prefers-color-scheme: light)", color: "#fafbfc" },
    { media: "(prefers-color-scheme: dark)", color: "#111316" },
  ],
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
