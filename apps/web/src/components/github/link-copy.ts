import type { GithubLink } from "@/lib/api";

/**
 * What the GitHub card says, in one place and with no JSX around it.
 *
 * The same split `pr-copy.ts` and `inbox/copy.ts` established, and `apps/web`'s vitest
 * config makes it structural rather than stylistic: the suite is `environment: "node"`
 * over `.ts` only, so a rule that lives inside a `.tsx` is a rule with no test. What is
 * here is a five-state table over two independent booleans and a four-word vocabulary,
 * which is exactly the shape that is wrong in a way nobody notices — every branch still
 * renders *something*.
 */

/** What the card is for, which is not the same as what it can do. */
export type GithubLinkStage =
  /** No App on the instance. There is nothing to consent *to* yet. */
  | "no-app"
  /** An App exists, this member has not consented. */
  | "offer"
  /** Consented, and the token is usable. */
  | "linked"
  /** Consented, and the token needs the consent screen walked again. */
  | "reconnect";

/**
 * Which of the four states the card is in.
 *
 * `no-app` wins over everything, including a link that already exists — and that ordering
 * is deliberate rather than incidental. A member can be linked on an instance whose App
 * was later removed or whose credentials were unset; their row survives, their name keeps
 * appearing on old feed lines, and the button that would re-consent has nothing to point
 * at. Saying "no App" there is the truth; drawing an active Connect button would send
 * somebody to a GitHub error page.
 *
 * `expired` is the only token state that asks for anything. `refreshable` deliberately
 * does not: a refresh token means GitHub can renew it without the member, so telling them
 * to reconnect would be asking for work nobody needs — see the note in `GithubTokenState`
 * about the difference between writing *as* a member and *naming* them.
 */
export function githubLinkStage(link: GithubLink): GithubLinkStage {
  if (!link.appConfigured) return "no-app";
  if (!link.linked) return "offer";
  return link.tokenState === "expired" ? "reconnect" : "linked";
}

/**
 * The sentence under the heading.
 *
 * The `linked` sentence names the account and **not** the token's health, because the
 * question a member has when they look at this card is "did it work". The token's state is
 * the [githubLinkDetail] line below it, where it is a footnote rather than the headline —
 * a card that led with "expires in 8 hours" would read as a problem every single day.
 */
export function githubLinkSummary(link: GithubLink): string {
  switch (githubLinkStage(link)) {
    case "no-app":
      return "No GitHub App is connected to this instance yet, so there is nothing to link to. An owner sets one up first.";
    case "offer":
      return "Link your GitHub account and Kanso can put your name on the pull requests you open, instead of your GitHub handle. Skipping this changes nothing else.";
    case "linked":
      return `Linked to @${link.login ?? "your account"}.`;
    case "reconnect":
      return `Linked to @${link.login ?? "your account"}, but the token has expired. Your name still appears on what you have already done; reconnect before Kanso writes to GitHub as you again.`;
  }
}

/**
 * The footnote: what the token can still do, in the member's terms rather than the
 * schema's.
 *
 * `undefined` for a member who has not linked, so the caller draws nothing rather than an
 * empty line. `permanent` gets a sentence and not silence, because "this never expires" is
 * information — the alternative is a card that looks identical to one whose expiry the
 * server failed to send.
 *
 * None of these four mention attribution, and that absence is the point: the name on a
 * feed line reads the row's existence and never its clock, so a token state is never the
 * reason a person's name is or is not there.
 */
export function githubLinkDetail(link: GithubLink): string | undefined {
  if (!link.linked) return undefined;
  switch (link.tokenState) {
    case "permanent":
      return "This instance's App issues tokens that do not expire, so this link lasts until you unlink it.";
    case "active":
      return "Kanso can write to GitHub as you.";
    case "refreshable":
      return "The token has expired and Kanso can renew it on its own — nothing for you to do.";
    case "expired":
      return "Kanso cannot write to GitHub as you until you reconnect.";
    // A state this build does not know, from a server one deploy newer. Silence rather
    // than a guess: this is a footnote, and a wrong footnote about somebody's credentials
    // is worse than none.
    default:
      return undefined;
  }
}

/** What the primary button says. `undefined` when there is nothing to press. */
export function githubLinkAction(link: GithubLink): string | undefined {
  switch (githubLinkStage(link)) {
    case "no-app":
      return undefined;
    case "offer":
      return "Connect GitHub";
    case "linked":
      return undefined;
    case "reconnect":
      return "Reconnect";
  }
}
