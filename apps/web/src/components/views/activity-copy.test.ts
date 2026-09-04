import { describe, expect, it } from "vitest";
import type { ActivityRow } from "@/lib/api";
import { activitySentence, activityTime } from "./activity-copy";

/**
 * The feed's sentences, moved here whole with the module they test (`KAN-87`).
 *
 * Nothing in these two blocks changed in the move, and that is the assertion the move
 * itself needed: a refactor of the file that carries `KAN-77`'s three guard layers is only
 * safe if the tests that prove them are the same tests. The `switch` still has no
 * `default` — see `activity-copy.ts` — so a kind added to `ACTIVITY_KINDS` without a branch
 * is `TS2366` at build rather than a sentence this file would never have thought to ask for.
 */

describe("activitySentence", () => {
  const row = (kind: ActivityRow["kind"], payload: Record<string, unknown> = {}): ActivityRow => ({
    id: "row",
    entityType: "project",
    entityId: "project-1",
    // The whole `User`, because that is what `/api/activity` sends and what the row
    // type now says. The feed reads two of its fields; the fixture cannot narrow it.
    actor: {
      id: "user-1",
      email: "tykok@kanso.dev",
      displayName: "Tykok",
      instanceRole: "owner",
      hasPassword: true,
    },
    kind,
    payload,
    createdAt: "2026-08-17T14:20:00Z",
  });

  /**
   * The drawing's own three lines are the specification: "Tykok a passé KAN-142 en
   * cours", "2 pages poussées vers Notion", "Léa a terminé KAN-131". Two of the three
   * name a person and one does not, which is exactly the shape `actor: null` has.
   */
  it("names who did what, and what it was done to", () => {
    expect(activitySentence(row("status_changed", { ref: "KAN-142", to: "in_progress" }))).toBe(
      "Tykok moved KAN-142 to In progress",
    );
    expect(activitySentence(row("created", { ref: "KAN-150" }))).toBe("Tykok created KAN-150");
    expect(activitySentence(row("assigned", { ref: "KAN-142" }))).toBe(
      "Tykok took KAN-142",
    );
  });

  /** Closing a cycle writes this row on every ticket that did not fit into it. */
  it("says where work carried out of a closed cycle went", () => {
    expect(activitySentence(row("carried_over", { ref: "KAN-142", from: 24, to: 25 }))).toBe(
      "Tykok carried KAN-142 into cycle 25",
    );
    expect(activitySentence(row("carried_over"))).toBe("Tykok carried work into the next cycle");
  });

  /**
   * Re-sizing is the decision somebody comes back looking for, so the sentence carries
   * both sizes. The two one-ended rows are not the same event and must not read alike:
   * the server omits a null, so an estimate arrived at has no `from` and one withdrawn
   * has no `to`.
   */
  it("says what a ticket was re-sized from and to", () => {
    expect(activitySentence(row("estimated", { ref: "KAN-142", from: 3, to: 13 }))).toBe(
      "Tykok re-sized KAN-142 from 3 to 13",
    );
    expect(activitySentence(row("estimated", { ref: "KAN-142", to: 5 }))).toBe(
      "Tykok sized KAN-142 at 5",
    );
    expect(activitySentence(row("estimated", { ref: "KAN-142", from: 8 }))).toBe(
      "Tykok un-sized KAN-142, from 8",
    );
    expect(activitySentence(row("estimated"))).toBe("Tykok re-sized a ticket");
  });

  /** The mirror is not a person, and "nobody pushed 2 pages" is not a sentence. */
  it("leaves the actor out when there was not one", () => {
    expect(activitySentence({ ...row("mirror_pushed"), actor: null })).toBe("Pushed to Notion");
  });

  /**
   * **`KAN-74`'s pair, and the assertion the ticket cares about most.**
   *
   * The same event, twice: once for a member who linked their GitHub account and once for
   * an author who never did. The second line is exactly what `V36`'s header calls the
   * documented fallback — no blank, no "unknown", no error, and no missing reason either.
   * Consent adds the name and takes nothing away.
   */
  it("names a consented member on a merge, and says via #418 for one who never linked", () => {
    const merged = { ref: "KAN-142", to: "done", via_pr: "#418" };

    expect(activitySentence(row("status_changed", merged))).toBe(
      "Tykok moved KAN-142 to Done via #418",
    );
    expect(activitySentence({ ...row("status_changed", merged), actor: null })).toBe(
      "Moved KAN-142 to Done via #418",
    );
  });

  /**
   * A member's own hand-driven move is unchanged by any of this. The suffix appears only
   * when a pull request caused the move, so the ordinary line cannot grow a "via" nobody
   * asked for.
   */
  it("leaves a move nobody made through a pull request exactly as it was", () => {
    expect(activitySentence(row("status_changed", { ref: "KAN-142", to: "done" }))).toBe(
      "Tykok moved KAN-142 to Done",
    );
    expect(
      activitySentence({ ...row("status_changed", { ref: "KAN-142", to: "done" }), actor: null }),
    ).toBe("Moved KAN-142 to Done");
  });

  /**
   * One `#`, whichever writer landed.
   *
   * A handler storing GitHub's `number` writes `418`; one storing the reference writes
   * `#418`. Both are plausible, `payload` is untyped jsonb, and a feed that says "via
   * ##418" for one and "via 418" for the other is a feed somebody has to explain.
   */
  it("prints one hash whether the payload carried a number, a string, or a hash", () => {
    const line = (via: unknown) =>
      activitySentence(row("status_changed", { ref: "KAN-142", to: "done", via_pr: via }));

    expect(line(418)).toBe("Tykok moved KAN-142 to Done via #418");
    expect(line("418")).toBe("Tykok moved KAN-142 to Done via #418");
    expect(line("#418")).toBe("Tykok moved KAN-142 to Done via #418");
    // Neither a number nor readable text is no reason at all, not "via undefined".
    expect(line(null)).toBe("Tykok moved KAN-142 to Done");
    expect(line("  ")).toBe("Tykok moved KAN-142 to Done");
    expect(line({ number: 418 })).toBe("Tykok moved KAN-142 to Done");
  });

  /**
   * The link itself, which `V36` gives its own kind rather than folding into the
   * transition. `actor_id` is null for a link the parser drew off a branch name and set for
   * one a member drew by hand, so both readings have to be sentences.
   */
  it("says a pull request was linked, with or without somebody having done it", () => {
    expect(activitySentence(row("pull_request_linked", { ref: "KAN-142", via_pr: "#418" }))).toBe(
      "Tykok linked #418 to KAN-142",
    );
    expect(
      activitySentence({
        ...row("pull_request_linked", { ref: "KAN-142", via_pr: "#418" }),
        actor: null,
      }),
    ).toBe("Linked #418 to KAN-142");
    expect(activitySentence(row("pull_request_linked"))).toBe(
      "Tykok linked a pull request to a ticket",
    );
  });

  /**
   * `payload` carries the before and after of a scalar change and nothing else, so a row
   * whose payload is empty still has to read as a sentence rather than as `undefined`.
   */
  it("reads as a sentence with an empty payload", () => {
    expect(activitySentence(row("renamed"))).toBe("Tykok renamed a ticket");
    expect(activitySentence(row("status_changed"))).toBe("Tykok changed a status");
  });

  /**
   * The first kind written against a *project*, and the only one whose `to` is a health
   * rather than a status. The sentence has to say "health" out loud: a feed line reading
   * "Tykok moved this to At risk" beside a project whose status is In progress is the one
   * place the two vocabularies could be mistaken for each other.
   */
  it("says a health was posted, and where it moved from", () => {
    expect(activitySentence(row("health_posted", { to: "at_risk", from: "on_track" }))).toBe(
      "Tykok posted health At risk, from On track",
    );
  });

  it("leaves out a from that the first update never had", () => {
    expect(activitySentence(row("health_posted", { to: "on_track" }))).toBe(
      "Tykok posted health On track",
    );
  });

  it("still reads as a sentence when the health did not travel", () => {
    expect(activitySentence(row("health_posted"))).toBe("Tykok posted a health update");
  });

  /**
   * `V30`'s kind, and the only one whose feed belongs to an *account*: `entityType` is
   * `"user"`, which the client type had never named either. The row is the whole history
   * the revoke left behind — `V27` deletes the token rather than flagging it — so a
   * sentence that dropped the token's name would leave the event unanswerable.
   */
  it("names the API token that was revoked, on an account's own feed", () => {
    const revoked: ActivityRow = {
      ...row("token_revoked", { name: "CI deploy", prefix: "kan_7f2a" }),
      entityType: "user",
      entityId: "user-1",
    };
    expect(activitySentence(revoked)).toBe("Tykok revoked the API token CI deploy");
  });

  it("falls back to the prefix, and never needs a digest", () => {
    expect(activitySentence(row("token_revoked", { prefix: "kan_7f2a" }))).toBe(
      "Tykok revoked an API token starting kan_7f2a",
    );
    expect(activitySentence(row("token_revoked"))).toBe("Tykok revoked an API token");
  });

  /**
   * `V35`'s single word for four field types and three gestures, so these are one branch
   * reading a payload whose `to` may be any of them. A boolean is the interesting one: the
   * app draws it as a checkbox and has no word for it anywhere, so the feed coins "yes".
   */
  it("says which field was set, and to what, whatever type it is", () => {
    expect(
      activitySentence(row("field_set", { ref: "KAN-142", name: "Severity", to: "high" })),
    ).toBe("Tykok set Severity on KAN-142 to high");
    expect(
      activitySentence(row("field_set", { ref: "KAN-142", name: "Story points", to: 8 })),
    ).toBe("Tykok set Story points on KAN-142 to 8");
    expect(
      activitySentence(row("field_set", { ref: "KAN-142", name: "Regression", to: true })),
    ).toBe("Tykok set Regression on KAN-142 to yes");
  });

  /**
   * Clearing is an absent `to` and never a word on the wire — the shared mapper omits nulls
   * — so "cleared" is a fact this branch infers from a `from` standing alone. And a row that
   * arrived with neither end still has the field's name, which is the one thing worth saying.
   */
  it("reads a cleared field as cleared, and a bare one as changed", () => {
    expect(
      activitySentence(row("field_set", { ref: "KAN-142", name: "Severity", from: "high" })),
    ).toBe("Tykok cleared Severity on KAN-142");
    expect(activitySentence(row("field_set", { ref: "KAN-142", name: "Severity" }))).toBe(
      "Tykok changed Severity on KAN-142",
    );
    expect(activitySentence(row("field_set"))).toBe("Tykok changed a field on a ticket");
  });

  /**
   * THE GUARD KAN-77 EXISTS FOR, and the one no type can provide.
   *
   * `ACTIVITY_KINDS` is a copy of a CHECK that has been widened six times, so a bundle can
   * be older than the server it is talking to — and the feed's `switch` has no `default`,
   * on purpose, because a `default` would disarm the build-time exhaustiveness check that
   * catches the *other* mistake. Without the membership test in front of the switch,
   * `phrase` is `undefined` and `phrase[0]` throws a `TypeError` — and it throws only on
   * the actorless line, which is exactly the line the GitHub webhook writes. That is why
   * this row carries `actor: null`: the crash was one feature away, not hypothetical.
   *
   * The sentence says Kanso has no words for the row rather than pretending to translate
   * it. A feed that renders every other row and admits one is a feed a reader can trust;
   * one that throws takes the whole page down with it.
   */
  it("says plainly that it cannot say a kind it has never heard of", () => {
    const fromTheFuture: ActivityRow = {
      ...row("created"),
      kind: "invented_by_a_later_migration" as ActivityRow["kind"],
      actor: null,
    };
    expect(activitySentence(fromTheFuture)).toBe(
      "Made a change nobody has taught this feed to say",
    );
  });

  it("keeps the actor on a kind it cannot say either", () => {
    expect(
      activitySentence({
        ...row("created"),
        kind: "invented_by_a_later_migration" as ActivityRow["kind"],
      }),
    ).toBe("Tykok made a change nobody has taught this feed to say");
  });
});

describe("activityTime", () => {
  // Fixed instants rather than `new Date()`: a test that reads the clock passes at 14:20
  // and fails at midnight.
  const now = new Date("2026-08-17T18:00:00Z");

  it("gives the hour for something that happened today", () => {
    expect(activityTime("2026-08-17T14:20:00Z", now, "UTC")).toBe("14:20");
  });

  it("says yesterday rather than a date nobody has to decode", () => {
    expect(activityTime("2026-08-16T09:04:00Z", now, "UTC")).toBe("yesterday");
  });

  it("gives the day for anything older", () => {
    expect(activityTime("2026-08-12T09:04:00Z", now, "UTC")).toBe("12 Aug");
    expect(activityTime("2025-12-31T09:04:00Z", now, "UTC")).toBe("31 Dec");
  });

  /**
   * An activity row is a *moment*, unlike a project bound, so it is converted into the
   * reader's zone — 23:30 UTC is already tomorrow in Paris, and a feed that said
   * "yesterday" about something the reader did an hour ago would be wrong.
   */
  it("converts, because a moment is not a day", () => {
    expect(activityTime("2026-08-17T23:30:00Z", new Date("2026-08-18T01:00:00Z"), "Europe/Paris")).toBe(
      "01:30",
    );
  });
});
