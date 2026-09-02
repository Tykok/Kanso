import type { ReactNode } from "react";
import { AppShell } from "@/components/shell/app-shell";

/**
 * The signed-in application, and the one place its frame is drawn.
 *
 * A route group, so nothing here changes a URL: `(app)` does not appear in a path, and
 * `/`, `/trash`, `/t/[key]` and the rest are exactly the addresses they were before the
 * folder existed. That is the property the group is for — every route in it gains the
 * sidebar, the breadcrumb and the way out without moving.
 *
 * What is deliberately *outside* it: `/login` and `/setup`, which have nothing to
 * navigate to yet; `/about`, `/roadmap` and `/roadmap/[key]`, a shop window for people
 * with no session; and `/design-system`, a contact sheet rather than a destination. A
 * sidebar on any of those would be a column of links that 401.
 *
 * A server component wrapping a client one, and nothing more. Everything it renders is a
 * client tree because the shell is: the auth gates read a cache, the keyboard reads a
 * store, and the scope mirror reads the address bar.
 */
export default function AppLayout({ children }: { children: ReactNode }) {
  return <AppShell>{children}</AppShell>;
}
