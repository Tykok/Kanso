import Link from "next/link";
import type { ReactNode } from "react";
import { Seal } from "@/components/ui/seal";
import { cn } from "@/lib/utils";
import { DISCUSSIONS_URL, LICENCE, REPO_URL } from "./copy";

/**
 * The chrome for the three surfaces that are not the application.
 *
 * `app/layout.tsx` is frozen for the fan-out and wraps every route in the app's
 * providers, so a public page cannot escape being *inside* the app — but it can look
 * like a site rather than a tool, and this is what does it: a rule under a horizontal
 * bar, a wide measured column instead of a sidebar, and no scope, no filter, no `⌘K`.
 * Two segments (`/roadmap`, `/about`) render it through their own nested layouts rather
 * than through a route group, so neither directory has to move.
 *
 * `nav-items.ts` is deliberately untouched: none of these routes belongs in the app's
 * sidebar, because the people they are for do not have an account.
 */
export function PublicShell({
  children,
  current,
}: {
  children: ReactNode;
  /** Which of the two public segments this is — one highlight, resolved per layout. */
  current: "about" | "roadmap";
}) {
  return (
    <div className="flex min-h-screen flex-col bg-background">
      <header className="flex items-center gap-6 border-b border-rule bg-card px-6 py-4 md:px-10">
        <Link href="/about" className="flex items-center gap-2.5 text-foreground">
          <span className="text-primary">
            <Seal size={16} title="Kanso" />
          </span>
          <span className="text-15 font-medium">Kanso</span>
          {/* The two kanji as text beside the mark, as the drawing has them. Not a
              Tailwind utility: `--font-seal` has no `@theme` key, and adding one would
              be an edit to the frozen token layer. */}
          <span className="text-12 text-faint" style={{ fontFamily: "var(--font-seal)" }}>
            簡素
          </span>
        </Link>
        <span className="flex-1" />
        <nav className="flex items-center gap-5 text-12">
          <PublicNavLink href="/about" active={current === "about"}>
            About
          </PublicNavLink>
          <PublicNavLink href="/roadmap" active={current === "roadmap"}>
            Roadmap
          </PublicNavLink>
          <PublicNavLink href={DISCUSSIONS_URL} external active={false}>
            Discussions
          </PublicNavLink>
          <PublicNavLink href={REPO_URL} external active={false}>
            Repository
          </PublicNavLink>
        </nav>
      </header>

      {children}

      <footer className="mt-auto flex flex-wrap items-center gap-4 border-t border-rule px-6 py-8 text-12 text-faint md:px-10">
        <span className="text-faint">
          <Seal size={14} />
        </span>
        <span>Kanso 簡素</span>
        <span className="flex-1" />
        <Link href="/about" className="hover:text-foreground">
          About
        </Link>
        <Link href="/about#contribute" className="hover:text-foreground">
          Contribute
        </Link>
        <span>{LICENCE}</span>
      </footer>
    </div>
  );
}

/**
 * `rel="noreferrer"` on the outbound ones: a self-hosted instance's hostname is not
 * something a visitor asked to hand to GitHub.
 */
function PublicNavLink({
  href,
  children,
  active,
  external,
}: {
  href: string;
  children: ReactNode;
  active: boolean;
  external?: boolean;
}) {
  const className = cn("hover:text-foreground", active ? "text-foreground" : "text-faint");
  return external ? (
    <a href={href} className={className} target="_blank" rel="noreferrer">
      {children}
    </a>
  ) : (
    <Link href={href} className={className} aria-current={active ? "page" : undefined}>
      {children}
    </Link>
  );
}

/** The measured column every public section sits in. */
export function PublicSection({
  children,
  className,
}: {
  children: ReactNode;
  className?: string;
}) {
  return <section className={cn("px-6 md:px-10", className)}>{children}</section>;
}
