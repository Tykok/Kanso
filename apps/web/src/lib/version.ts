/**
 * Injected at build time, the same way NEXT_PUBLIC_API_URL is — Next inlines
 * NEXT_PUBLIC_* into the bundle, so this is a build argument, not a runtime variable.
 * `dev` is what a local `pnpm dev` reports, and saying so is more useful than pretending
 * to a version number nobody bumped.
 */
export const WEB_VERSION = process.env.NEXT_PUBLIC_KANSO_COMMIT ?? "dev";
