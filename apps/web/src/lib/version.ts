/**
 * Injected at build time, the same way NEXT_PUBLIC_API_URL is — Next inlines
 * NEXT_PUBLIC_* into the bundle, so this is a build argument, not a runtime variable.
 * `dev` is what a local `pnpm dev` reports, and saying so is more useful than pretending
 * to a version number nobody bumped.
 */
export const WEB_VERSION = process.env.NEXT_PUBLIC_KANSO_COMMIT ?? "dev";

/**
 * The tag `release.yml` was triggered by — `v0.2.0` — and absent everywhere else: a
 * source build has no tag, and inventing one would be the hand-bumped number above.
 *
 * Shown beside the commit, never instead of it. The commit is what was compiled and what
 * the skew check compares against `/api/me`; a tag is a name someone can move. But a tag
 * is what an operator asks for when they want to know whether an instance is behind, and
 * answering that with forty hex characters sends them to `git describe` to translate.
 */
export const RELEASE = process.env.NEXT_PUBLIC_KANSO_RELEASE || undefined;
