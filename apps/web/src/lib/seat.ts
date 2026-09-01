import type { InstanceRole } from "./api";

/**
 * What a seat may do, as the client understands it.
 *
 * Two predicates, one file, and both are mirrors of properties on `InstanceRole` in
 * `domain/Model.kt` — `canConfigureInstance` and `mayWrite`. The server is the authority:
 * nothing here is a permission, it decides what to *draw*. The point of drawing it right
 * is that a reader offered a composer that comes back 403 has been lied to, and a menu of
 * eight things that will all fail is worse than a menu of two that work.
 *
 * They live here rather than inline because the inline spelling was already wrong. Four
 * screens asked `instanceRole !== "member"`, which was true of exactly the two configuring
 * roles right up until a fourth value existed — and would then have handed a read-only
 * seat the settings screen. A predicate that names what it means cannot go stale that way:
 * a new role has to be classified here, once, in the open.
 */
export function canConfigure(role: InstanceRole | undefined): boolean {
  return role === "owner" || role === "admin";
}

/**
 * Everyone but a viewer. Written as an exclusion rather than as a list of the three who
 * may, so that a role added later writes by default and only a deliberate second
 * read-only seat has to be named — the same shape as `InstanceRole.mayWrite`, which is
 * `this != VIEWER` for the same reason.
 */
export function mayWrite(role: InstanceRole | undefined): boolean {
  return role !== "viewer";
}
