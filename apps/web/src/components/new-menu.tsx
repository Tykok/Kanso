"use client";

import { menuItems } from "./menu-items";
import { Menu } from "./menu";
import type { ActionContext } from "@/lib/actions";

/**
 * The three things a person creates, in one place, filtered by what they may do — a
 * member sees no Team without a second rule written here, because `when` already says so.
 *
 * `project.create` and `team.create` both open their dialog pre-filled by
 * `creationSeed` (see `lib/creation-seed.ts`), computed from wherever you are standing:
 * on a team or one of its projects, a new project lands in that team and a new team
 * becomes its sub-team. There is no separate "in this team" / "sub-team" action to
 * dedupe against — one id per entity, always offered, seeded rather than duplicated.
 */
export function NewMenu({ ctx }: { ctx: ActionContext }) {
  const items = menuItems(ctx, ["ticket.create", "project.create", "team.create"]);

  return (
    <div className="new-menu">
      <Menu label="New" trigger="New" items={items} />
    </div>
  );
}
