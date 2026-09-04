import { render, screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import type { ActivityRow } from "@/lib/api";

/**
 * The feed, rendered — because `KAN-85` mounted it on a second screen and the two things
 * that could go wrong there are not things a `.ts` test can see.
 *
 * `activity-copy.test.ts` already proves every sentence this draws, and proves them faster.
 * What it cannot prove is that the component survives the row at all: `KAN-76` declined to
 * mount this feed on a ticket precisely because it believed `activitySentence` would throw
 * on `phrase[0]` for an actorless line, and a truth table over a pure function is not what
 * answers that — a render is. The other is the two halves of a line running together for
 * want of `display: flex`, which has shipped here twice.
 *
 * `useActivity` is stubbed rather than a `QueryClientProvider` stood up around a stubbed
 * `fetch`: the subject is what the component does with a list, and a query client would
 * put retry, caching and two ticks of asynchrony between the list and the assertion.
 */
const useActivity = vi.hoisted(() => vi.fn());
vi.mock("@/lib/queries", () => ({ useActivity }));

/** The ticket the mount `KAN-85` added is drawn for. Read by the stub, not by a request. */
const TICKET = "b0a1c2d3-0000-0000-0000-000000000000";

/** `entityType` is `ticket` throughout: it is the mount `KAN-85` added. */
function row(kind: string, payload: Record<string, unknown>, actor: string | null = null): ActivityRow {
  return {
    id: `row-${kind}-${JSON.stringify(payload)}`,
    entityType: "ticket",
    entityId: TICKET,
    actor: actor === null ? null : ({ displayName: actor } as ActivityRow["actor"]),
    kind: kind as ActivityRow["kind"],
    payload,
    createdAt: "2026-09-04T09:20:00Z",
  } as ActivityRow;
}

function draw(rows: ActivityRow[] | undefined) {
  useActivity.mockReturnValue({ data: rows });
  return render(<ActivityFeedUnderTest entityType="ticket" entityId={TICKET} />);
}

/** Imported after the mock is registered, which is what `vi.hoisted` above is for. */
const { ActivityFeed: ActivityFeedUnderTest } = await import("./activity-feed");

describe("the feed on a ticket", () => {
  it("draws nothing at all when there is nothing to read", () => {
    const { container } = draw([]);
    expect(container.innerHTML).toBe("");
    expect(screen.queryByText("Activity")).toBeNull();
  });

  it("draws nothing while the read is in flight or refused", () => {
    const { container } = draw(undefined);
    expect(container.innerHTML).toBe("");
  });

  /**
   * The line `V36__github.sql` documents in words, and the join between `KAN-84` and this
   * ticket: without a writer for `payload.ref` the same row reads "Moved a ticket to Done
   * via #418", which is the sentence that made `KAN-84` worth doing before this one.
   */
  it("says which ticket moved, and where the move came from", () => {
    draw([row("status_changed", { ref: "KAN-142", to: "done", via_pr: "#418" })]);
    expect(screen.getByText("Moved KAN-142 to Done via #418")).toBeTruthy();
  });

  it("names the person when there is one, and does not capitalise the verb", () => {
    draw([row("status_changed", { ref: "KAN-142", to: "done" }, "Élie Treport")]);
    expect(screen.getByText("Élie Treport moved KAN-142 to Done")).toBeTruthy();
  });

  /**
   * `KAN-77`'s third layer, rendered rather than called: this is the row `KAN-76` expected
   * to throw on `phrase[0]`. A kind the bundle has never heard of has no actor to prepend,
   * so it takes the capitalising branch — the one that reads `phrase[0]`.
   */
  it("survives a kind it has never heard of, on the actorless path", () => {
    draw([row("resized" as ActivityRow["kind"], {})]);
    expect(screen.getByText("Made a change nobody has taught this feed to say")).toBeTruthy();
  });

  /**
   * The twice-shipped defect: the time and the sentence are two boxes, not two inline runs.
   *
   * Asserted on the class and not on geometry, and that is a real limit rather than a
   * preference — happy-dom computes no layout, so the collision itself is invisible to
   * every test in this repo. What can be checked is that the row is still the flex
   * container `activity-feed.tsx` says it is, which is the thing whose removal caused it.
   */
  it("keeps the two halves of a line in separate boxes", () => {
    draw([row("mirror_pushed", {})]);
    const line = screen.getByRole("listitem");
    expect(line.className.split(/\s+/)).toContain("flex");
    expect(line.querySelector("time")).toBeTruthy();
  });
});
