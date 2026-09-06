import { describe, expect, it } from "vitest";
import { ApiError, type DocBlock, type DocBlockLock } from "@/lib/api";
import {
  freesInSeconds,
  heldByOther,
  lockBadge,
  lockRefusal,
  lockSentence,
  refusalMessage,
  renewDelayMs,
} from "./doc-locks";

const NOW = new Date("2026-09-04T12:00:00.000Z");

const at = (secondsFromNow: number) =>
  new Date(NOW.getTime() + secondsFromNow * 1000).toISOString();

const lock = (extra: Partial<DocBlockLock> = {}): DocBlockLock => ({
  userId: "marie",
  displayName: "Marie",
  freesAt: at(30),
  takenAt: NOW.toISOString(),
  ...extra,
});

/**
 * The fixture **omits** `lockedBy` rather than setting it to null, which is what the wire
 * actually sends: Jackson drops nulls, so an unlocked block has no such key. A builder
 * writing `lockedBy: null` would test a shape the server never produces — the same policy
 * `time-entries.test.ts` states, and the reason `Last used Invalid Date` shipped here.
 */
const block = (extra: Partial<DocBlock> = {}): DocBlock => ({
  id: "b1",
  pageId: "p1",
  position: 0,
  kind: "paragraph",
  content: { text: "one" },
  ticketIds: [],
  ...extra,
});

describe("renewDelayMs", () => {
  it("renews at a third of the window, so two renewals fit before it lapses", () => {
    expect(renewDelayMs(lock({ freesAt: at(30) }), NOW)).toBe(10_000);
  });

  it("follows the server's TTL down instead of facing it with a constant", () => {
    // An instance tuned to three seconds — what the e2e stack runs. A hard-coded 10 s here
    // would let every caret lose its block between renewals.
    expect(renewDelayMs(lock({ freesAt: at(3) }), NOW)).toBe(1_000);
  });

  it("never spins into a request loop on a lock that is already expiring", () => {
    expect(renewDelayMs(lock({ freesAt: at(0) }), NOW)).toBe(1_000);
    expect(renewDelayMs(lock({ freesAt: at(-60) }), NOW)).toBe(1_000);
  });

  it("survives a freesAt it cannot read", () => {
    expect(renewDelayMs(lock({ freesAt: "not a date" }), NOW)).toBe(1_000);
  });
});

describe("freesInSeconds", () => {
  it("counts down", () => {
    expect(freesInSeconds(lock({ freesAt: at(25) }), NOW)).toBe(25);
  });

  it("stops at zero rather than describing a state nothing is in", () => {
    expect(freesInSeconds(lock({ freesAt: at(-4) }), NOW)).toBe(0);
  });
});

describe("heldByOther", () => {
  it("is nothing when nobody holds the block", () => {
    // The absent key, not a null one.
    expect(heldByOther(block(), "elie")).toBeUndefined();
  });

  it("is nothing when the reader holds it themselves", () => {
    expect(heldByOther(block({ lockedBy: lock({ userId: "elie" }) }), "elie")).toBeUndefined();
  });

  it("is the holder when somebody else has it", () => {
    expect(heldByOther(block({ lockedBy: lock() }), "elie")?.displayName).toBe("Marie");
  });

  it("treats an unknown reader as somebody who may not type", () => {
    // `useMe()` in flight. Drawing the block as writable for that one paint and refusing
    // the keystroke a moment later is worse than drawing it locked.
    expect(heldByOther(block({ lockedBy: lock() }), undefined)?.displayName).toBe("Marie");
  });
});

describe("what the person who cannot type reads", () => {
  it("names the holder, says it frees itself, and says when", () => {
    const sentence = lockSentence("Marie", at(25), NOW);

    expect(sentence).toContain("Marie");
    expect(sentence).toContain("frees itself");
    expect(sentence).toContain("25 s");
  });

  it("does not count into the negative once the claim has lapsed", () => {
    expect(lockSentence("Marie", at(-3), NOW)).toBe(
      "Marie is editing this block. It is freeing itself now.",
    );
  });

  it("badges the block with a name and a countdown", () => {
    expect(lockBadge(lock({ freesAt: at(12) }), NOW)).toBe("Marie · 12 s");
    expect(lockBadge(lock({ freesAt: at(-1) }), NOW)).toBe("Marie · freeing");
  });
});

describe("lockRefusal", () => {
  const refused = (body: unknown) => new ApiError(409, "Marie is editing this block.", body);

  it("reads the two facts off the problem document", () => {
    expect(lockRefusal(refused({ holder: "Marie", freesAt: at(30) }))).toEqual({
      holder: "Marie",
      freesAt: at(30),
    });
  });

  it("is nothing for a 409 from somewhere else", () => {
    // A disposition conflict carries `counts`, not `holder`. Reading it as a lock would
    // put "undefined is editing this block" on screen.
    expect(lockRefusal(refused({ counts: { tickets: 3 } }))).toBeUndefined();
  });

  it("is nothing for another status, however shaped", () => {
    expect(lockRefusal(new ApiError(403, "not your team", { holder: "Marie", freesAt: at(5) })))
      .toBeUndefined();
  });

  it("refuses a holder that arrived as the wrong type", () => {
    expect(lockRefusal(refused({ holder: 42, freesAt: at(5) }))).toBeUndefined();
    expect(lockRefusal(refused({ holder: "Marie", freesAt: 5 }))).toBeUndefined();
  });

  it("is nothing for something that is not an ApiError at all", () => {
    expect(lockRefusal(new Error("offline"))).toBeUndefined();
    expect(lockRefusal(undefined)).toBeUndefined();
  });
});

describe("refusalMessage", () => {
  it("builds the countdown from the instant, not from the server's sentence", () => {
    const error = new ApiError(409, "Marie is editing this block. It frees itself in a moment.", {
      holder: "Marie",
      freesAt: at(18),
    });

    expect(refusalMessage(error, NOW)).toBe("Marie is editing this block. It frees itself in 18 s.");
  });

  it("still says something when the document could not be parsed", () => {
    // The half that matters: every path says *something*. A refusal this screen cannot
    // read reaches the person as the server's own sentence, which names the holder too.
    const error = new ApiError(409, "Marie is editing this block.", { unexpected: true });

    expect(refusalMessage(error, NOW)).toBe("Marie is editing this block.");
  });

  it("falls back to a plain error's message rather than to silence", () => {
    expect(refusalMessage(new Error("Failed to fetch"), NOW)).toBe("Failed to fetch");
  });
});
