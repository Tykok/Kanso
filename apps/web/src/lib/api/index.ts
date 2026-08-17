/**
 * One import path, one file per slice.
 *
 * `core` is the client every screen already had. The rest are added by the branches that
 * need them — comments and labels here, documents, cycles, notifications and the public
 * projection in their own — so six of them can be written at once without six edits to
 * one 567-line file. Nothing about the split is visible to a caller: `@/lib/api` exports
 * what it always did.
 */
export * from "./core";
