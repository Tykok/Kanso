"use client";

import { BrandLogo } from "./brand-logo";
import { menuItems } from "./menu-items";
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

  const items = menuItems(ctx, ["app.settings", "app.help", "app.palette", "app.logout"]);

  return (
    <div className="brand">
      <Menu
        // The visible label is "Kanso 簡素", so the accessible name has to start with
        // it (WCAG 2.5.3, Label in Name): "Account and settings" alone left a
        // voice-control user saying "click Kanso" with nothing to click. Still true now
        // that the label is drawn rather than typed — 2.5.3 is about the words a sighted
        // user can see, and an image of text is text they can see. This label is also
        // why the logo itself takes no alt: it sits inside this button, and an
        // `aria-label` on a button replaces whatever the button contains.
        label="Kanso — account and settings"
        items={items}
        // The logo carries the wordmark and the kanji itself, so the text that used to
        // stand here would now say everything twice. 100px is a floor, not a taste: the
        // wordmark is about an eighth of the mark's height, so it reads at this size and
        // turns to a grey smudge much below it — which is why the sidebar's 216px is what
        // makes putting the full lockup here possible at all.
        trigger={<BrandLogo width={100} />}
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
