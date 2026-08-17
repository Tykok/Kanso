/**
 * The hooks, split the way `lib/api` is and for the same reason. A slice adds
 * `queries/<slice>.ts` beside `core` and one re-export line here.
 */
export * from "./core";
export * from "./trash";
