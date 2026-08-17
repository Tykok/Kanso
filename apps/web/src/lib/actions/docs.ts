import type { Action } from "./types";

/**
 * Slice B — 07 and 22, documents. Deliberately still empty, which is a decision rather
 * than an omission.
 *
 * The four keys screen 25 lists under "Dans un document" cannot live in this registry:
 *
 *  - `/`, `#` and `@` are *typed characters inside a text field*. The registry
 *    dispatches on a bare keypress reaching the shell, which is the one thing that must
 *    not happen while somebody is writing a sentence containing a slash.
 *  - `c` is already `ticket.create` in `core.ts`, and `indexActions` throws at module
 *    load on two actions claiming one key — correctly, since `c` in a document means
 *    something narrower: a ticket already attached to this page, not the composer.
 *    Registering it here would either break the app at import time or, with a `mode`
 *    of its own, add a fourth value to `View` in a file this branch may not edit.
 *
 * So the document owns its own keys, on its own `onKeyDown`, where the caret is — see
 * `components/docs/document.tsx`. Nothing about that needs a registry entry, and giving
 * it one would put a keystroke's meaning two files away from the text it applies to.
 */
export const docsActions: readonly Action[] = [];
