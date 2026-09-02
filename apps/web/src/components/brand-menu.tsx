"use client";

import { Seal } from "./ui/seal";
import { useMenuItems } from "./menu-items";
import { Menu } from "./menu";
import type { ActionContext } from "@/lib/actions";
import { useMe } from "@/lib/queries";
import { WEB_VERSION } from "@/lib/version";

/**
 * Who am I, how do I configure this, how do I leave. Nothing in the interface answered
 * the first question outside dev mode.
 *
 * Identity and version ride along inside the menu's popover as its header and footer,
 * not as siblings in the sidebar: neither is focusable, neither responds to a click,
 * and the popover is the one place they only appear once summoned.
 *
 * The version is not a menu item either. Two lines appear when the web bundle and the
 * API disagree, because a skew is exactly what you want written down in a bug report.
 * Both halves are the short commit the image was built from — the same `KANSO_COMMIT`
 * reaches `NEXT_PUBLIC_KANSO_COMMIT` and the API's Gradle build — so the comparison
 * has something to compare. While the API answered with a hand-edited `0.1.0` the two
 * were different kinds of string, never equal, and the skew warning was permanently on.
 */
export function BrandMenu({ ctx }: { ctx: ActionContext }) {
  const me = useMe();
  const user = me.data?.user;
  const apiVersion = me.data?.version;

  const items = useMenuItems(ctx, ["app.settings", "app.help", "app.palette", "app.logout"]);

  return (
    <div data-testid="brand-menu">
      <Menu
        // The visible mark reads "簡素", so the accessible name has to start with
        // "Kanso" for the ear as much as the eye (WCAG 2.5.3, Label in Name):
        // "Account and settings" alone left a voice-control user saying "click Kanso"
        // with nothing to click. This label is also why the seal itself takes no
        // title: it sits inside this button, and an `aria-label` on a button replaces
        // whatever the button contains.
        label="Kanso — account and settings"
        items={items}
        // `asChild`, rather than the default wrapper: the default trigger draws its
        // own 20×20 `⋯` glyph, which is the wrong shape for a row that fills the
        // sidebar's width. This button is the trigger itself, so it draws its own
        // shape instead of fighting that base.
        asChild
        trigger={
          <button type="button" data-testid="brand-trigger" className="flex w-full items-center rounded-md p-1.5 hover:bg-accent">
            <Seal size={14} />
          </button>
        }
        header={
          user && (
            <>
              <strong>{user.displayName}</strong>
              <span>
                {user.email} · {user.instanceRole}
              </span>
            </>
          )
        }
        footer={
          apiVersion && apiVersion !== WEB_VERSION ? (
            <>
              <span>web {WEB_VERSION}</span>
              <span>api {apiVersion}</span>
            </>
          ) : (
            <span>{WEB_VERSION}</span>
          )
        }
      />
    </div>
  );
}
