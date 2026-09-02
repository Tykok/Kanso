/**
 * The hooks, split the way `lib/api` is and for the same reason. A slice adds
 * `queries/<slice>.ts` beside `core` and one re-export line here.
 */
export * from "./core";
export * from "./social";
export * from "./docs";
export * from "./trash";
export * from "./inbox";
export * from "./views";
export * from "./organise";
export * from "./oauth";
export * from "./favourites";
export * from "./project-updates";
export * from "./me-stats";
