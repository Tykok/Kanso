import {
  ACTIVITY_KINDS,
  type ActivityRow,
  type ProjectHealth,
  type TicketStatus,
} from "@/lib/api";
import { PROJECT_HEALTH_LABELS, STATUS_LABELS } from "@/lib/status";
import { MONTHS } from "./project-copy";

/**
 * Every sentence the activity feed says, with no React in it.
 *
 * Cut out of `project-copy.ts` at 422 lines, which is past the length one maintainer can
 * re-read in a sitting — and this half was the worst possible place to stop re-reading,
 * because it is where `KAN-77`'s three guard layers live. It came out whole rather than
 * being split further: the payload readers, the kind vocabulary and the `switch` have one
 * reason to exist between them, which is turning a row nobody can read into a line a
 * person can.
 *
 * It is a `.ts` and not a `.tsx` on purpose, and structurally rather than stylistically:
 * `apps/web`'s vitest runs `.ts` under `environment: "node"`, so a truth table in a `.tsx`
 * is a truth table with no test. `activity-feed.tsx` above it only draws.
 *
 * `MONTHS` is imported rather than copied. It is the one thing the two halves still share
 * — `dayLabel` names a project's bound with it and [activityTime] names a feed's day — and
 * a second month table is a second place for `Sep`/`Sept` to disagree. The import points
 * this way round because `project-copy.ts` is where it was and where the older reader is;
 * nothing points back, so the two files do not form a cycle.
 */

/**
 * `KAN-142`, or absent — what every branch below calls the thing that changed.
 *
 * Read by key and never assumed to be there, like every other payload reader here. It was
 * assumed for longer than the others deserved: nothing in the API wrote `ref` at all until
 * `KAN-84`, so every sentence this file can produce said "a ticket", including the one
 * `V36__github.sql` documents in words. `ActivityService.refsFor` is the writer now, and
 * it resolves rather than stores — so this stays `string | undefined` for the two cases it
 * always had, a draft with no identifier and a ticket that has since been destroyed.
 */
const ref = (payload: Record<string, unknown>): string | undefined =>
  typeof payload.ref === "string" ? payload.ref : undefined;

/** The health twin of [statusOf]: a separate reader, because they are separate vocabularies. */
const healthOf = (payload: Record<string, unknown>, key: "from" | "to"): string | undefined => {
  const value = payload[key];
  return typeof value === "string" && value in PROJECT_HEALTH_LABELS
    ? PROJECT_HEALTH_LABELS[value as ProjectHealth]
    : undefined;
};
/** An estimate off either end of a re-sizing. Absent and null both read as "no size". */
const points = (payload: Record<string, unknown>, key: "from" | "to"): number | undefined =>
  typeof payload[key] === "number" ? (payload[key] as number) : undefined;

/** A payload string that is there and says something. Blank and absent are one answer. */
const named = (payload: Record<string, unknown>, key: string): string | undefined => {
  const value = payload[key];
  return typeof value === "string" && value.trim().length > 0 ? value.trim() : undefined;
};

/**
 * A custom field's value, whichever of `V35`'s four types it is.
 *
 * The only reader here that cannot know its own type, because `field_set` is deliberately
 * one kind for `text`, `number`, `boolean` and `select`. A shape it does not recognise
 * answers `undefined` and the branch reads that as "no value" — printing `[object Object]`
 * beside a field name is the failure every payload reader in this file exists to avoid.
 *
 * A boolean becomes "yes"/"no" rather than "true"/"false": nothing else in the app has a
 * word for one — `ticket-fields.tsx` draws it as a checkbox — so the feed has to coin one,
 * and a sentence a person reads is not a place to print a wire value.
 */
const fieldValue = (payload: Record<string, unknown>, key: "from" | "to"): string | undefined => {
  const value = payload[key];
  if (typeof value === "string") return value.trim().length > 0 ? value.trim() : undefined;
  if (typeof value === "number") return String(value);
  if (typeof value === "boolean") return value ? "yes" : "no";
  return undefined;
};

/**
 * [ACTIVITY_KINDS] as a membership test, because a `kind` off the wire is a string first
 * and a member of that union only by the type's word for it.
 */
const KNOWN_KINDS: ReadonlySet<string> = new Set(ACTIVITY_KINDS);

/**
 * The pull request a GitHub-driven row came from — `#418`, or absent.
 *
 * `payload.via_pr` is what `V36` reserves for it, and this is the only reader. Normalised
 * to carry exactly one `#`, because the two plausible writers disagree: a handler that
 * stores GitHub's `number` writes `418` and one that stores the reference writes `#418`,
 * and a feed that says "via ##418" or "via 418" for the same event depending on which
 * landed is a feed nobody trusts. A number is accepted as well as a string for the same
 * reason — JSON has one number type and `payload` is `jsonb`.
 *
 * The suffix is what makes the actorless line honest rather than mysterious: *Moved
 * KAN-142 to Done* invites "by whom?", and *Moved KAN-142 to Done via #418* answers it
 * without claiming a person.
 */
const viaPr = (payload: Record<string, unknown>): string | undefined => {
  const value = payload.via_pr;
  if (typeof value === "number") return `#${value}`;
  if (typeof value !== "string") return undefined;
  const trimmed = value.trim();
  if (trimmed.length === 0) return undefined;
  return trimmed.startsWith("#") ? trimmed : `#${trimmed}`;
};

const statusOf = (payload: Record<string, unknown>, key: "from" | "to"): string | undefined => {
  const value = payload[key];
  return typeof value === "string" && value in STATUS_LABELS
    ? STATUS_LABELS[value as TicketStatus]
    : undefined;
};

/**
 * One row of the feed, as a sentence.
 *
 * The drawing gives three: "Tykok a passé KAN-142 en cours", "2 pages poussées vers
 * Notion", "Léa a terminé KAN-131". Two name a person and one does not, which is exactly
 * the shape `actor: null` has — `activity.actor_id` is `ON DELETE SET NULL`, and the
 * mirror is not a person at all.
 *
 * `payload` carries the before and after of a scalar change and nothing else, so every
 * branch below has to read as a sentence with an empty payload too: a row that arrived
 * from a service that recorded less than this one hoped for must not print `undefined`.
 */
export function activitySentence(row: ActivityRow): string {
  const who = row.actor?.displayName;
  const what = ref(row.payload);

  // Written as the verb phrase first, so the actor is prepended once rather than in
  // seventeen branches that could each get the spacing wrong.
  const phrase = ((): string => {
    // TWO GUARDS, BECAUSE THERE ARE TWO DIFFERENT MISTAKES.
    //
    // The one below is for a kind the *database* holds and this bundle has never heard of:
    // `ACTIVITY_KINDS` is a third copy of a CHECK that has been widened six times, and no
    // test can reconcile it because Vitest has no database. It had fallen two kinds behind
    // when KAN-77 was written, and a browser holding yesterday's bundle is one deploy away
    // from the same gap at any time. So the type's promise about `row.kind` is checked
    // rather than believed, and the sentence says plainly that Kanso has no words for the
    // row instead of throwing on `phrase[0]` further down.
    //
    // The other guard is the `switch` having no `default`, and it is the one that catches
    // the mistake a person makes: annotated `(): string`, a kind added to `ACTIVITY_KINDS`
    // with no case here is TS2366 at build — "Function lacks ending return statement". A
    // `default` would answer both mistakes with one branch and silently disarm that, which
    // is why the unknown kind is turned away *before* the switch and not inside it. Adding
    // a `default` to this switch would undo half of KAN-77.
    if (!KNOWN_KINDS.has(row.kind)) return "made a change nobody has taught this feed to say";

    switch (row.kind) {
      case "created":
        return what ? `created ${what}` : "created a ticket";
      case "status_changed": {
        const to = statusOf(row.payload, "to");
        // `KAN-74`. The pull request is a suffix on the existing three sentences rather
        // than a fourth branch, because it is *why* the status changed and not *what*
        // changed — and because it has to read correctly with and without an actor. The
        // two lines it produces are the pair this whole feature is about:
        //
        //   nobody linked  →  "Moved KAN-142 to Done via #418"
        //   consented      →  "Elie moved KAN-142 to Done via #418"
        //
        // Appended after the whole phrase and not after `to`, so a row with no readable
        // `to` still says where it came from: "Changed a status via #418" is thin, but it
        // is not wrong, and dropping the suffix there would lose the only fact that row has.
        const pr = viaPr(row.payload);
        const suffix = pr ? ` via ${pr}` : "";
        if (what && to) return `moved ${what} to ${to}${suffix}`;
        if (to) return `moved a ticket to ${to}${suffix}`;
        return `changed a status${suffix}`;
      }
      case "pull_request_linked": {
        // `V36` reserves this kind for the link itself, distinct from the transition it may
        // cause: `actor_id` is null for a link the parser drew off a branch name and set
        // for one a member drew by hand, so this sentence has to work both ways too.
        const pr = viaPr(row.payload);
        const it = what ?? "a ticket";
        if (pr) return `linked ${pr} to ${it}`;
        return `linked a pull request to ${it}`;
      }
      case "priority_changed":
        return what ? `reprioritised ${what}` : "changed a priority";
      case "assigned":
        return what ? `took ${what}` : "took a ticket";
      case "unassigned":
        return what ? `let go of ${what}` : "let go of a ticket";
      case "renamed":
        return what ? `renamed ${what}` : "renamed a ticket";
      case "scheduled":
        return what ? `scheduled ${what}` : "scheduled a ticket";
      case "archived":
        return what ? `archived ${what}` : "archived a ticket";
      case "commented":
        return what ? `commented on ${what}` : "left a comment";
      case "labelled":
        return what ? `labelled ${what}` : "labelled a ticket";
      case "mirror_pushed":
        return "pushed to Notion";
      case "carried_over": {
        // The only branch that names a *cycle* rather than a field of the ticket, and
        // the only one whose "to" is a number: closing a cycle moves what did not fit,
        // and the sentence has to say where it went or the reader has to go looking.
        const to = typeof row.payload.to === "number" ? row.payload.to : undefined;
        if (what && to) return `carried ${what} into cycle ${to}`;
        if (to) return `carried unfinished work into cycle ${to}`;
        return what ? `carried ${what} into the next cycle` : "carried work into the next cycle";
      }
      case "health_posted": {
        // The only branch whose `to` is a *health* and not a status, and the only one about
        // a project rather than a ticket. The word "health" is in the sentence deliberately:
        // "moved this to At risk", printed in a feed beside a project reading In progress,
        // is the one line where a reader could take the two vocabularies for one.
        const to = healthOf(row.payload, "to");
        const from = healthOf(row.payload, "from");
        if (to && from) return `posted health ${to}, from ${from}`;
        if (to) return `posted health ${to}`;
        // No `from` on the first update anybody posts — the server omits the key rather
        // than sending a null — and no `to` only if the row arrived from a writer that
        // recorded less than this hoped for.
        return "posted a health update";
      }
      case "estimated": {
        // Both ends are optional and they mean different things by their absence: no
        // `to` is an estimate withdrawn, no `from` is one arrived at for the first
        // time. Printing "sized KAN-142 at undefined" for either is the failure the
        // whole payload-read-by-key discipline in this file exists to avoid.
        const from = points(row.payload, "from");
        const to = points(row.payload, "to");
        const it = what ?? "a ticket";
        if (from !== undefined && to !== undefined) return `re-sized ${it} from ${from} to ${to}`;
        if (to !== undefined) return `sized ${it} at ${to}`;
        if (from !== undefined) return `un-sized ${it}, from ${from}`;
        return `re-sized ${it}`;
      }
      case "token_revoked": {
        // `V30`'s kind, and the only one whose entity is a person's *account* rather than a
        // unit of work — which is why `ActivityEntity` had to gain `"user"` in the same
        // breath. The row is all that survives the token: `V27` revokes by deleting it, so
        // there is no `revoked_at` anywhere to read the fact off afterwards.
        //
        // `ref` is not consulted: the payload names a token, never a ticket.
        const name = named(row.payload, "name");
        // Name and prefix are the two halves a person recognises, and the digest is neither
        // — `ApiTokenService` will not put one in a payload, and this would not print it.
        const prefix = named(row.payload, "prefix");
        if (name) return `revoked the API token ${name}`;
        if (prefix) return `revoked an API token starting ${prefix}`;
        return "revoked an API token";
      }
      case "field_set": {
        // `V35`'s one word for all four field types and for all three gestures. The field
        // is the scalar here, so its *name* carries the sentence — and it travels in the
        // payload beside its id for `labelled`'s reason: a definition can be renamed or
        // deleted, and a row holding only an id would have nothing left to print.
        const name = named(row.payload, "name");
        const to = fieldValue(row.payload, "to");
        const from = fieldValue(row.payload, "from");
        const it = what ?? "a ticket";
        if (name && to) return `set ${name} on ${it} to ${to}`;
        // Clearing is an absent `to`, never a word on the wire: the shared mapper omits
        // nulls, so "had a value and has none" is exactly a `from` with no `to`.
        if (name && from) return `cleared ${name} on ${it}`;
        // A name and neither end: the row was written by something that recorded less than
        // this hoped for, and the field is still the one fact worth saying.
        if (name) return `changed ${name} on ${it}`;
        return `changed a field on ${it}`;
      }
    }
  })();

  // No actor: the phrase becomes the sentence, capitalised, because "nobody pushed to
  // Notion" is not what happened.
  return who ? `${who} ${phrase}` : phrase[0].toUpperCase() + phrase.slice(1);
}

/**
 * When it happened, at the resolution the reader needs.
 *
 * The hour today, the word "yesterday" for the day before, the day itself for anything
 * older — the drawing's own three resolutions. Unlike a project bound, an activity row is
 * a *moment*, so it is converted into the reader's zone: 23:30 UTC is already tomorrow in
 * Paris, and a feed saying "yesterday" about something done an hour ago would be wrong.
 *
 * [now] and [timeZone] are arguments rather than read from the environment: a function
 * that asks the clock passes at 14:20 and fails at midnight.
 */
export function activityTime(createdAt: string, now: Date, timeZone: string): string {
  const parts = (date: Date) =>
    new Intl.DateTimeFormat("en-GB", {
      timeZone,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    })
      .format(date)
      // en-GB gives `17/08/2026, 14:20`, which splits cleanly on the punctuation.
      .split(/[/,: ]+/);

  const at = parts(new Date(createdAt));
  const today = parts(now);
  const sameDay = at[0] === today[0] && at[1] === today[1] && at[2] === today[2];
  if (sameDay) return `${at[3]}:${at[4]}`;

  // A day earlier in the reader's zone, computed by asking the formatter about "24 hours
  // ago" rather than by subtracting from the calendar — a month boundary is not a
  // subtraction and neither is a daylight-saving change.
  const yesterday = parts(new Date(now.getTime() - 24 * 60 * 60 * 1000));
  if (at[0] === yesterday[0] && at[1] === yesterday[1] && at[2] === yesterday[2]) {
    return "yesterday";
  }

  return `${Number(at[0])} ${MONTHS[Number(at[1]) - 1]}`;
}
