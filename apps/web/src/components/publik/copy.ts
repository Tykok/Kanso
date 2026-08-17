/**
 * The words the public surfaces say, and where each one comes from.
 *
 * Every claim here is already made somewhere the project can be held to: the licence,
 * the governance and the install line are `Kanso - Vitrine`'s own copy; the review
 * promise and "no CLA" are screen 28's; the install command is the one the drawing
 * prints. Nothing on a persuasion surface is invented here — a landing page that
 * promises something no other document does is a promise nobody has to keep.
 *
 * In English because the application is: `layout.tsx` declares `lang="en"` and every
 * label in the app is English, including the statuses screen 28 draws. The bundle drew
 * these two screens in French, and a French shop window bolted onto an English product
 * would be the one inconsistency a visitor sees first.
 */

export const LICENCE = "AGPL-3.0";

/**
 * Where "I'll take it" and "ask a question" go.
 *
 * They have to go somewhere real, and today that is the repository: the ticket's own
 * discussion is the foundation's `comments` table, which is not in the schema yet, and
 * a stranger has no account to post one with either way. The default is the repository
 * the design bundle records (`github.md`: `repo: Tykok/Kanso`) rather than a guess, and
 * an instance that lives elsewhere overrides it.
 */
export const REPO_URL = process.env.NEXT_PUBLIC_KANSO_REPO_URL ?? "https://github.com/Tykok/Kanso";
export const DISCUSSIONS_URL = `${REPO_URL}/discussions`;
export const CONTRIBUTING_URL = `${REPO_URL}/blob/main/CONTRIBUTING.md`;

/** Screen 28's checklist, in the drawing's order. */
export const BEFORE_YOU_START = [
  { text: "Clone it and run", code: "docker compose up kanso" },
  { text: "Read the contributing guide (5 min)" },
  { text: "Say hello in the ticket's discussion" },
  { text: "Open a pull request, even an incomplete one" },
] as const;

/** The promise the drawing makes under "who can help". */
export const REVIEW_PROMISE = "Reviewed within a week. No CLA to sign.";

/**
 * The closing note on screen 28, and the most load-bearing sentence on it: it tells a
 * visitor that what they are reading is a deliberately published subset, not the ticket.
 */
export const WHY_YOU_CAN_SEE_THIS =
  "This ticket is visible because it is marked public. The same page seen from inside " +
  "shows the activity and the cycle as well.";

/** The landing page's three open-source columns — `Vitrine`'s own three lists. */
export const OPEN_SOURCE = {
  install: {
    title: "Install",
    command: "docker compose up kanso",
    points: ["Postgres and a single container", "Notion import in one step", "No telemetry"],
  },
  contribute: {
    title: "Contribute",
    points: [
      "The design system is in the repository",
      "Tickets marked “good first step”",
      "Reviewed within a week",
    ],
  },
  governance: {
    title: "Governance",
    points: ["Decisions discussed in public", "The roadmap is the tickets", "No CLA"],
  },
} as const;
