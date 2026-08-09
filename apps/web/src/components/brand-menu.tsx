"use client";

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
 */
export function BrandMenu({ ctx }: { ctx: ActionContext }) {
  const me = useMe();
  const user = me.data?.user;
  const apiVersion = me.data?.version;

  const items = menuItems(ctx, ["app.settings", "app.help", "app.palette", "app.logout"]);

  return (
    <div className="brand">
      <Menu
        label="Account and settings"
        items={items}
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
      <div className="brand-name">
        <strong>Kanso</strong>
        <span>簡素</span>
      </div>
    </div>
  );
}
