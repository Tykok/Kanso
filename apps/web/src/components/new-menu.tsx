"use client";

import { menuItems } from "./menu-items";
import { Menu } from "./menu";
import type { ActionContext } from "@/lib/actions";

/**
 * The three things a person creates, in one place, filtered by what they may do — a
 * member sees no Team without a second rule written here, because `when` already says so.
 *
 * `project.createInTeam` is offered ahead of `project.create` when the scope is a team,
 * so the project lands where you were standing. Exactly one of the two ever passes its
 * `when`, so the menu never shows both.
 */
export function NewMenu({ ctx }: { ctx: ActionContext }) {
  const items = menuItems(ctx, [
    "ticket.create",
    "project.createInTeam",
    "project.create",
    "team.createChild",
    "team.create",
  ]);

  return (
    <div className="new-menu">
      <Menu label="New" trigger="New" items={items} />
    </div>
  );
}
