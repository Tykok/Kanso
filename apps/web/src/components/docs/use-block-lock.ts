"use client";

import { useEffect, useRef, useState } from "react";
import { docsApi } from "@/lib/api";
import { refusalMessage, renewDelayMs } from "@/lib/doc-locks";
import { setCaretBlock } from "@/lib/realtime-events";

/**
 * The lock's lifecycle, tied to where the caret is — `KAN-25`.
 *
 * Focus takes the block, a timer holds it, blur lets it go. Nothing here draws anything:
 * whether a paragraph is greyed out is `heldByOther`'s answer over what the server last
 * sent, and this is only the half that *claims*.
 *
 * **Not a react-query mutation.** Three of the four things this does are timers and refs,
 * and the fourth — the refusal — is reported through `useReportError` rather than held as
 * state. Wrapping it in `useMutation` would re-render the whole document on every renewal,
 * which is once every ten seconds under a caret that is not moving.
 *
 * **Everything mutable lives in a ref, and the returned pair is built once.** The renewal
 * schedules itself, so a `useCallback` would have to reference itself before it was
 * declared; and `report`'s identity changes with the shell's channel, so closing over it
 * would rebuild the loop under a caret that had not moved. A lazily initialised `useState`
 * is what holds the pair — not `useRef`, which may not be read during render, and not
 * `useMemo`, which is a cache React is allowed to discard: discarding this one would drop
 * the timer holding somebody's block while their caret sat still in it.
 *
 * What happens to a laptop closed mid-paragraph is decided by what is *absent* here:
 * there is no `beforeunload` handler. A `DELETE` fired from one is unreliable in every
 * browser and impossible from a lid that closes, so relying on it would be a release path
 * that works in the demo and not in the case it exists for. The lock lapses instead —
 * `V40`'s expiry is a timestamp compared on read, so it frees itself whether this tab, the
 * API, or the machine is still alive. The renewal below is the only thing keeping it, and
 * a closed laptop stops renewing by definition.
 */
export function useBlockLock(report: (message: string | null) => void) {
  const held = useRef<string | undefined>(undefined);
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);

  // The latest reporter, without the loop below depending on its identity.
  const reportRef = useRef(report);
  useEffect(() => {
    reportRef.current = report;
  }, [report]);

  const [api] = useState(() => {
    const stopRenewing = () => {
      if (timer.current !== undefined) clearTimeout(timer.current);
      timer.current = undefined;
    };

    /**
     * Renews, then schedules the next one from the answer it just got.
     *
     * The delay comes out of the lock rather than a constant — `renewDelayMs` argues why —
     * so an instance tuned to a three-second TTL renews every second without this file
     * knowing the number. Re-scheduled from each answer instead of on an interval, so a
     * slow request delays the next renewal rather than stacking a second one behind it.
     *
     * A refusal mid-renewal means the claim lapsed and somebody else took it — the laptop
     * came back. It is reported like any other, and the timer stops rather than retrying:
     * the next keystroke asks again, and a loop here would be a client arguing with the
     * server about a block it has already lost.
     */
    const renew = (blockId: string) => {
      docsApi
        .takeLock(blockId)
        .then((lock) => {
          if (held.current !== blockId) return;
          timer.current = setTimeout(() => renew(blockId), renewDelayMs(lock, new Date()));
        })
        .catch((error: unknown) => {
          if (held.current !== blockId) return;
          held.current = undefined;
          setCaretBlock(undefined);
          stopRenewing();
          reportRef.current(refusalMessage(error, new Date()));
        });
    };

    return {
      /**
       * The caret landed in a block: claim it.
       *
       * [setCaretBlock] is told first and unconditionally, before the request has answered.
       * That flag is what stops the applier repainting this page under the reader's own
       * keystrokes, and it has to be true for the whole time they are typing — including
       * the round trip of the very first claim, which is exactly when the first commit can
       * land. Being wrong in this direction costs one skipped repaint of a block this
       * reader is looking at; being wrong the other way replaces their draft.
       */
      hold: (blockId: string) => {
        if (held.current === blockId) return;
        stopRenewing();
        held.current = blockId;
        setCaretBlock(blockId);
        reportRef.current(null);
        renew(blockId);
      },

      /**
       * The caret left: let go, and do not wait to find out whether it worked.
       *
       * The `catch` is deliberate and empty. A failed release is not something to tell
       * anybody about — the claim expires on its own within the TTL, which is the whole
       * point of the design — and a red line under the top bar saying so would be the app
       * complaining about a state it has already recovered from.
       */
      release: () => {
        const blockId = held.current;
        stopRenewing();
        held.current = undefined;
        setCaretBlock(undefined);
        if (blockId) void docsApi.releaseLock(blockId).catch(() => {});
      },
    };
  });

  // Navigating away is a blur nobody dispatched. Without this, walking from one document
  // to another through a mention would leave a block held for the whole TTL.
  useEffect(() => api.release, [api]);

  return api;
}
