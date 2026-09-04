import { describe, expect, it } from "vitest";
import type { GithubLink } from "@/lib/api";
import {
  githubLinkAction,
  githubLinkDetail,
  githubLinkStage,
  githubLinkSummary,
} from "./link-copy";

/**
 * The default is the state almost every instance is in: an App nobody configured, and
 * therefore nothing to consent to. Every other state is built from it by naming what
 * changed, so a test that forgets a field gets the honest baseline rather than a
 * half-linked shape the server cannot produce.
 */
const link = (over: Partial<GithubLink> = {}): GithubLink => ({
  appConfigured: false,
  appManagedByEnvironment: false,
  linked: false,
  ...over,
});

describe("which state the card is in", () => {
  it("has nothing to offer before an App exists", () => {
    expect(githubLinkStage(link())).toBe("no-app");
    expect(githubLinkAction(link())).toBeUndefined();
  });

  it("offers the link once an App exists and nobody has consented", () => {
    expect(githubLinkStage(link({ appConfigured: true }))).toBe("offer");
    expect(githubLinkAction(link({ appConfigured: true }))).toBe("Connect GitHub");
  });

  it("is linked, with nothing to press, for a usable token", () => {
    for (const tokenState of ["permanent", "active", "refreshable"] as const) {
      const state = link({ appConfigured: true, linked: true, login: "tykok", tokenState });
      expect(githubLinkStage(state)).toBe("linked");
      expect(githubLinkAction(state)).toBeUndefined();
    }
  });

  /**
   * `refreshable` deliberately does not ask for anything, and that is the distinction
   * worth a test of its own: a refresh token means GitHub renews it without the member, so
   * a "Reconnect" button there is asking for work nobody needs. Only `expired` — an expiry
   * with nothing to renew it — is the member's problem.
   */
  it("asks for a reconnection only when there is nothing left to renew with", () => {
    const expired = link({ appConfigured: true, linked: true, login: "tykok", tokenState: "expired" });
    expect(githubLinkStage(expired)).toBe("reconnect");
    expect(githubLinkAction(expired)).toBe("Reconnect");

    const refreshable = link({
      appConfigured: true,
      linked: true,
      login: "tykok",
      tokenState: "refreshable",
    });
    expect(githubLinkStage(refreshable)).toBe("linked");
  });

  /**
   * The ordering that is deliberate rather than incidental: a member can be linked on an
   * instance whose App was later removed. Their row survives and their name keeps appearing
   * on the feed lines it already earned — but the button that would re-consent has nothing
   * to point at, so drawing an active one would send somebody to a GitHub error page.
   */
  it("says the App is missing even for a member who is already linked", () => {
    const orphaned = link({ appConfigured: false, linked: true, login: "tykok", tokenState: "active" });
    expect(githubLinkStage(orphaned)).toBe("no-app");
    expect(githubLinkAction(orphaned)).toBeUndefined();
  });
});

describe("what the card says", () => {
  /**
   * The offer has to say that skipping it is free. The whole flow is optional by design —
   * a member who never walks it is the documented case, not a degraded one — and a consent
   * screen that does not say so reads as a requirement.
   */
  it("tells a member that skipping the link costs them nothing else", () => {
    expect(githubLinkSummary(link({ appConfigured: true }))).toContain("Skipping this changes nothing else");
  });

  it("names the account it is linked to", () => {
    expect(
      githubLinkSummary(link({ appConfigured: true, linked: true, login: "tykok", tokenState: "active" })),
    ).toBe("Linked to @tykok.");
  });

  /**
   * **The reassurance that has to be there**, because the card is where somebody will look
   * when their token expires and they wonder whether their history just changed. It did
   * not: attribution reads the row's existence, never its clock.
   */
  it("promises an expired member that what they have already done keeps their name", () => {
    const expired = githubLinkSummary(
      link({ appConfigured: true, linked: true, login: "tykok", tokenState: "expired" }),
    );
    expect(expired).toContain("Your name still appears on what you have already done");
  });

  /**
   * A login the server did not send must not print `@undefined`. It cannot happen —
   * `login` is present whenever `linked` is — but the shape allows it, and this is the
   * exact class of bug the "absent, not null" note on `GithubLink` exists to prevent.
   */
  it("does not print an undefined handle if the server sent none", () => {
    const summary = githubLinkSummary(link({ appConfigured: true, linked: true, tokenState: "active" }));
    expect(summary).not.toContain("undefined");
    expect(summary).toBe("Linked to @your account.");
  });

  it("says nothing about a token for a member who has not linked", () => {
    expect(githubLinkDetail(link({ appConfigured: true }))).toBeUndefined();
  });

  /**
   * A permanent token gets a sentence rather than silence: "this never expires" is
   * information, and the alternative is a card indistinguishable from one whose expiry the
   * server failed to send.
   */
  it("explains a token that never expires instead of leaving it blank", () => {
    expect(
      githubLinkDetail(link({ appConfigured: true, linked: true, login: "tykok", tokenState: "permanent" })),
    ).toContain("do not expire");
  });

  /**
   * A token state from a server one deploy newer. Silence rather than a guess — a wrong
   * footnote about somebody's credentials is worse than none — and, crucially, no throw:
   * this is the failure mode `ACTIVITY_KINDS` has today, where a kind the client does not
   * know reaches `phrase[0]` and dies.
   */
  it("says nothing rather than guessing at a state it does not know", () => {
    const future = { appConfigured: true, linked: true, login: "tykok", tokenState: "revoked" };
    expect(githubLinkDetail(future as unknown as GithubLink)).toBeUndefined();
    expect(githubLinkStage(future as unknown as GithubLink)).toBe("linked");
  });

  /**
   * None of the four footnotes mentions attribution, and the absence is the point: the name
   * on a feed line reads the row's existence and never its clock, so a token state is never
   * the reason a person's name is or is not there. A footnote saying otherwise would be the
   * screen teaching the mistake the server refuses to make.
   */
  it("never suggests that a token's state affects whose name appears", () => {
    for (const tokenState of ["permanent", "active", "refreshable", "expired"] as const) {
      const detail = githubLinkDetail(
        link({ appConfigured: true, linked: true, login: "tykok", tokenState }),
      );
      expect(detail).toBeDefined();
      expect(detail).not.toMatch(/feed|attribut|your name/i);
    }
  });
});
