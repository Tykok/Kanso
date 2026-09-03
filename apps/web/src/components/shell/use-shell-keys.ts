"use client";

import { useCallback, useEffect, useRef } from "react";
import { usePathname, useRouter } from "next/navigation";
import {
  chordOf,
  claim,
  permits,
  type ActionContext,
  type ShortcutMode,
} from "@/lib/actions";
import { chordsFor, resolveShortcut } from "@/lib/shortcuts";
import { useBindings } from "@/lib/use-bindings";
import { useUi } from "@/store/ui";
import type { PageShell } from "./topbar-slot";

/**
 * The application's one `keydown`.
 *
 * There were six. `app/page.tsx`, `app/inbox/page.tsx`, `saved-view.tsx`,
 * `triage-view.tsx`, `views/shell.tsx` and `timeline/arrows.tsx` each attached their own,
 * "next row" was written out by hand three times, and `⇧e`, `⌘k`, `Escape`, `F` and the
 * four triage rulings were registry actions in none of them — so `?` could not list them
 * and nothing could ever remap them.
 *
 * Slice 1 folded four of the six into this hook and left the ticket list's alone, because
 * that one also drove the inline rename, the dependency picker and `⇧↵`, "none of which
 * the registry can express with a `shortcut` field that holds one bare key". The chord
 * grammar removes that excuse, so this now serves `/` too and `PageShell.ownsKeyboard` is
 * gone.
 *
 * One listener still stands apart and must: `timeline/arrows.tsx`. It listens during a
 * mouse drag in order to cancel it — a gesture's escape hatch, alive only while the
 * pointer is down, and not a shortcut at all.
 *
 * What a page cannot express as a registry action, it *claims* — see `lib/actions/
 * claims.ts`. That is how `⇧e`, `⇧↵`, `⇧p`, `Mod+v`, `x` on a saved view, `⇧↑↓` and the
 * four triage rulings dispatch from here without `ActionContext` growing an inbox
 * mutation, a router and a queue cursor it has no business carrying.
 */

/**
 * A bare key belongs to whoever is typing. Same three tags the deleted handlers tested.
 *
 * This is also what keeps the browser's own reflexes: `Mod+f` and `Mod+v` deliberately
 * shadow find-in-page and paste (§6.4, argued in §11), and they shadow them *over a list
 * of rows*, where neither did anything. Inside every input, textarea and select the
 * dispatcher stands down before it has resolved anything, so find and paste work exactly
 * where they mean something. Do not narrow this guard without moving those two chords.
 */
const isTypingTarget = (target: EventTarget | null) =>
  target instanceof HTMLElement &&
  (target.tagName === "INPUT" || target.tagName === "TEXTAREA" || target.tagName === "SELECT");

/**
 * Which screen's keys are in force, from the route and — on `/` alone — the drawing.
 *
 * `/` is three drawings of one query, so it asks the store: `h` is the chart's and the
 * board's and neither's in the list. `/views/[id]` and `/triage` have a mode each because
 * one key means something else there and nowhere else — `x` selects a row, `x` closes a
 * triage ticket — and `mode` is how the registry has always expressed that.
 *
 * Everything else is `list`, which is `views/shell.tsx`'s choice kept for its reason:
 * these screens draw records, a queue or a report, so the chart's `h` `l` `[` `]` are not
 * theirs to answer. Read off `useUi().view` instead, the trash would answer the timeline's
 * keys because the reader last looked at a Gantt.
 */
function modeFor(pathname: string, view: ShortcutMode): ShortcutMode {
  if (pathname === "/") return view;
  if (pathname === "/triage") return "triage";
  if (pathname.startsWith("/views/")) return "savedView";
  return "list";
}

/**
 * What a page does for an action the registry cannot run itself.
 *
 * Called with an object literal, so the handlers are listed as dependencies rather than
 * the object — the same discipline `usePageShell` imposes for the same reason. Every
 * handler has to be stable across renders (a `useCallback`, or a function that closes over
 * nothing that moves), or this re-registers on every render.
 *
 * A ref holds the current handlers and the claim reads it at event time, so a handler that
 * *is* rebuilt on every render still costs one registration rather than one per render.
 */
export function usePageActions(handlers: Record<string, () => void>): void {
  const latest = useRef(handlers);
  // After every render, and never during one: the ref is what lets a page pass inline
  // arrows without re-registering, and a write during render is a side effect React is
  // entitled to run twice.
  useEffect(() => {
    latest.current = handlers;
  });

  // The ids, sorted and joined: the set of claimed actions is what this effect depends on,
  // and the bodies are read out of the ref. A page's id list is a literal, so it never
  // changes for the life of the page — which is the whole reason this can be one effect.
  const ids = Object.keys(handlers).sort().join(" ");

  useEffect(() => {
    const withdrawals = ids
      .split(" ")
      .filter(Boolean)
      .map((id) => claim(id, () => latest.current[id]?.()));
    return () => withdrawals.forEach((withdraw) => withdraw());
  }, [ids]);
}

export function useShellKeys({ ctx, page }: { ctx: ActionContext; page: PageShell }): {
  /** What `Escape` does, and what the `×` does. One function, two ways in. */
  leave: () => void;
} {
  const router = useRouter();
  const pathname = usePathname();
  const { overlay, dialog, view, close } = useUi();
  const { keys, index } = useBindings();

  /**
   * Close what is open, then leave.
   *
   * Two meanings in one gesture, in the order a reader expects: whatever is drawn over
   * the page goes first, and only with nothing left to close does the page itself. That
   * order is `views/shell.tsx`'s, kept verbatim.
   *
   * `router.back()` and not `push("/")`, which is the whole complaint about the four
   * `Back` links this replaces: they were `<Link href="/">`, so they did not go back,
   * they went home — and a ticket page is reached from the list, the board, the palette
   * and a link inside a document. With no history to go back into, a pasted link in a
   * fresh tab, it lands on the list, because a key that does nothing is indistinguishable
   * from a key that is broken.
   *
   * At `/` there is nowhere to leave to, so the gesture stops at closing. The `×` is not
   * drawn there either — home is not somewhere you went.
   */
  const leave = useCallback(() => {
    if (overlay !== "none" || dialog.kind !== "none") {
      close();
      return;
    }
    // A page that means something of its own by `Escape` — clearing a bulk selection, on
    // the one route that has one — takes it here and says so by returning true.
    if (page.onEscape?.()) return;
    if (pathname === "/") return;
    if (window.history.length > 1) router.back();
    else router.push("/");
  }, [overlay, dialog, close, page, pathname, router]);

  /**
   * Everything the handler reads, in a ref, written after every render.
   *
   * `ctx` used to be a dependency of the effect below, and that made the listener one
   * React commit late — not by design, by the route the value takes. A page's context
   * reaches this hook through `usePageShell`: the page renders with a new cursor, *its*
   * effect publishes, `AppShell` sets state, and only then does this effect tear the
   * listener down and put a current one up. Two keys pressed inside that window both ran
   * against the context from before the first one.
   *
   * That is not a theoretical window. `p` then `3` on the ticket list moved the cursor and
   * then set the status of **the row the cursor had just left** — a write to a ticket
   * nobody was looking at, answered 200, whose only visible symptom was a key that seemed
   * to do nothing. `keyboard.spec.ts`' "dispatched once, not once per shell" caught it as
   * a flake, which is the closest anything came to noticing.
   *
   * The remedy is the one [usePageActions] above already applies to a page's claims: the
   * ref is written after every render and the handler reads it when the key arrives, so
   * there is no window — and one registration for the life of the shell rather than one
   * per published context. `mode` and the `back` set are derived per event for the same
   * reason; a keystroke is rare enough to afford a `Set`.
   */
  const latest = useRef({ ctx, overlay, dialog, view, pathname, keys, index, leave });
  useEffect(() => {
    latest.current = { ctx, overlay, dialog, view, pathname, keys, index, leave };
  });

  useEffect(() => {
    /*
     * Which chords mean "close what is open, then leave".
     *
     * `app.back` is in the registry — so `?` lists it and §6.5 can rebind it — but it is
     * the one action the shell runs itself: leaving a page needs the router, the overlay
     * state and the page's own claim on the key, and `ActionContext` carries none of the
     * three and should not. So the *binding* is data and the body is here, next to the
     * `leave` the `×` in the top bar also calls.
     *
     * `Escape` is in the set whatever the binding says, and is the one key in the app that
     * cannot be taken away. §6.5 refuses to assign it for the same reason from the other
     * side — it and `Tab` are how a reader escapes a capture that went wrong — and a
     * preference that left somebody with no way out of an overlay is the class of bug
     * `mergeBindings` refuses rather than throws over.
     */
    const onKeyDown = (event: KeyboardEvent) => {
      const { ctx, overlay, dialog, view, pathname, keys, index, leave } = latest.current;
      const mode = modeFor(pathname, view);
      const back = new Set(["Escape", ...chordsFor("app.back", keys)]);
      const chord = chordOf(event);

      /*
       * Overlays and dialogs own their own keys; nothing behind them may react.
       *
       * `Mod+k` is inside this guard and not before it, and that placement is a bug fix
       * somebody paid for: the composer's title input stops propagation but its four
       * `<select>`s do not, so `⌘K` from one of them used to throw away a typed title by
       * opening the palette over it. It used to have to be handled ahead of the registry
       * because a bare `KeyboardEvent.key` could not hold a modifier; now it is
       * `app.palette`'s binding and goes through the same path as `c`.
       */
      if (overlay !== "none" || dialog.kind !== "none") {
        if (back.has(chord)) leave();
        return;
      }

      const typing = isTypingTarget(event.target);
      if (back.has(chord)) {
        // Not while typing: a field's own `Escape` clears it or gives the focus back, and
        // the list behind it must not also be left. `tickets.tsx`'s inline title editor
        // and the filter box both handle theirs, which is why the rename that used to
        // need a flag in this handler no longer does.
        if (typing) return;
        event.preventDefault();
        leave();
        return;
      }
      if (typing) return;

      const action = resolveShortcut(chord, mode, index);
      // One predicate answers both "may I show this" and "may I run it", so a key whose
      // action is unavailable stays inert rather than half-firing. `permits` adds the seat
      // to that predicate, so a reader's keyboard is as quiet as their menus.
      if (!action || !permits(action, ctx)) return;
      event.preventDefault();
      action.run(ctx);
    };

    window.addEventListener("keydown", onKeyDown);
    return () => window.removeEventListener("keydown", onKeyDown);
    // Empty on purpose: every value the handler needs is read out of `latest` at event
    // time. A dependency here would put the window this fixes straight back.
  }, []);

  return { leave };
}
