import { readdirSync, readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { DEFAULT_PREFERENCES } from "./core";
import { ACTIVITY_KINDS } from "./social";

/** `apps/api`'s migrations, four levels up from `lib/api` and across. */
const MIGRATIONS = fileURLToPath(
  new URL("../../../../api/src/main/resources/db/migration", import.meta.url),
);

/**
 * The newest migration that *states* `activity_kind_chk`, and the words it states.
 *
 * This is the house's own rule — "the authority is the most recent migration that laid the
 * list down, found by grep" — done by the machine instead of by hand. The migrations are
 * walked newest first and the first one carrying the statement wins, which is why `V37`
 * discussing the list without re-stating it is correctly skipped.
 *
 * `ADD CONSTRAINT … CHECK` and not a mention: every migration that widens this list argues
 * about it in prose first, and `V36`'s prose even names `V35` as its authority. A grep for
 * the identifier alone would have returned a comment.
 */
const kindsTheDatabaseAllows = (): { file: string; kinds: string[] } => {
  const newestFirst = readdirSync(MIGRATIONS)
    .filter((name) => name.endsWith(".sql"))
    .map((name) => ({ name, version: Number(/^V(\d+)__/.exec(name)?.[1] ?? -1) }))
    .filter((file) => file.version >= 0)
    .sort((a, b) => b.version - a.version);

  for (const file of newestFirst) {
    const sql = readFileSync(`${MIGRATIONS}/${file.name}`, "utf8");
    const check = /ADD CONSTRAINT activity_kind_chk\s+CHECK \(kind IN \(([^)]*)\)\)/i.exec(sql);
    if (check) {
      return { file: file.name, kinds: [...check[1].matchAll(/'([a-z_]+)'/g)].map((m) => m[1]) };
    }
  }
  // Loudly, and naming the path: a skip here would look exactly like a passing suite.
  throw new Error(`No migration under ${MIGRATIONS} states activity_kind_chk`);
};

describe("the shared client", () => {
  /**
   * Screen 02 says the setting lives in the preferences; slice 0 is what made that true.
   * The default is the panel because that is what the app did before the setting existed —
   * a preference that changes behaviour for everyone who never set it is not a preference.
   */
  it("defaults openTicket to the panel", () => {
    expect(DEFAULT_PREFERENCES.openTicket).toBe("panel");
  });

  /**
   * KAN-77's real answer, and it replaces a hand-written count.
   *
   * What stood here asserted `toHaveLength(15)` and explained that nothing could check the
   * client list against the CHECK "because Vitest has no database". That premise was the
   * bug: the authority on a closed vocabulary is not the running database, it is **the
   * migration that laid the list down** — which is a file, on disk, in this repository, and
   * so is readable from a test in `environment: "node"`.
   *
   * The count tripwire had already failed at its job twice. It went stale as `V30` and
   * `V35` widened the CHECK, and it could only ever say *how many* words were missing,
   * never which — so the number was edited to match the list rather than the list to match
   * the server, which is the failure mode of every guard that asserts an arity.
   *
   * This is one of KAN-77's three layers and the outermost. A migration that widens
   * `activity_kind_chk` turns this red in its own commit; the `switch` with no `default` in
   * `activity-copy.ts` then refuses to compile until the new word has a sentence; and
   * `activitySentence`'s unknown-kind branch catches the case no test can reach, a browser
   * holding a bundle older than the server it is talking to.
   */
  it("knows every kind the newest migration to state the CHECK allows", () => {
    const { file, kinds } = kindsTheDatabaseAllows();

    expect(kinds.length).toBeGreaterThan(10);
    expect([...ACTIVITY_KINDS].sort(), `client list against ${file}`).toEqual([...kinds].sort());
  });

  /** The shape a renderer relies on, which the list above does not by itself promise. */
  it("names each of those kinds once, and none of them blank", () => {
    expect(new Set(ACTIVITY_KINDS).size).toBe(ACTIVITY_KINDS.length);
    expect(ACTIVITY_KINDS.every((kind) => kind.length > 0)).toBe(true);
  });
});
