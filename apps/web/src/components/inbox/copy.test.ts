import { describe, expect, it } from "vitest";
import type { Notification } from "@/lib/api";
import { groupOf, relativeTime, rowCopy } from "./copy";

const row = (over: Partial<Notification>): Notification => ({
  id: "n1",
  kind: "assigned",
  entityType: "ticket",
  entityId: "t1",
  reference: "KAN-139",
  subject: "Timeline: route the arrows around the bars",
  actor: { id: "u1", displayName: "A. Okonkwo" },
  payload: {},
  createdAt: "2026-08-17T10:00:00Z",
  ...over,
});

describe("what an inbox row says", () => {
  it("summarises Notion's validation list and keeps the raw text to unfold", () => {
    const error =
      "Notion API 400 (validation_error): body failed validation. Fix one:\n" +
      "body.properties.Start.date should be defined, instead was `undefined`.";
    const copy = rowCopy(
      row({ kind: "sync_failed", actor: undefined, payload: { destination: "notion", error } }),
    );
    expect(copy.detail).toBe("Notion refused the page: Start is invalid");
    expect(copy.raw).toBe(error);
  });

  it("names the person who assigned it, and the ticket underneath", () => {
    expect(rowCopy(row({}))).toEqual({
      sentence: "A. Okonkwo assigned this ticket to you",
      detail: "Timeline: route the arrows around the bars",
    });
  });

  it("names the status a ticket was moved to, not just that it moved", () => {
    const copy = rowCopy(
      row({
        kind: "status_moved",
        actor: { id: "u2", displayName: "M. Rey" },
        payload: { from: "in_progress", to: "in_review" },
      }),
    );
    // "moved the ticket" is what the drawing says and is one word short of useful:
    // the whole reason to be told is which column it is in now.
    expect(copy.sentence).toBe("M. Rey moved this ticket to In review");
  });

  it("falls back to a status it does not recognise rather than dropping the sentence", () => {
    const copy = rowCopy(row({ kind: "status_moved", payload: { to: "triaged" } }));
    expect(copy.sentence).toBe("A. Okonkwo moved this ticket to triaged");
  });

  it("quotes the document a mention was written in, and the sentence it was in", () => {
    const copy = rowCopy(
      row({
        kind: "mentioned",
        entityType: "doc",
        reference: undefined,
        subject: "Sync contract",
        actor: { id: "u3", displayName: "J. Salas" },
        payload: { excerpt: "@you — queue on disk or in memory?" },
      }),
    );
    expect(copy).toEqual({
      sentence: "J. Salas mentioned you in “Sync contract”",
      detail: "“@you — queue on disk or in memory?”",
    });
  });

  it("says how far a project slipped, because nothing else can", () => {
    const copy = rowCopy(
      row({
        kind: "project_slipped",
        entityType: "project",
        reference: undefined,
        subject: "Notion mirror",
        actor: undefined,
        payload: { days: 3 },
      }),
    );
    expect(copy).toEqual({
      sentence: "The project “Notion mirror” slipped by 3 days",
      detail: "Recalculated from the critical path",
    });
  });

  it("uses the singular for a project that slipped by one day", () => {
    const copy = rowCopy(
      row({ kind: "project_slipped", subject: "Notion mirror", payload: { days: 1 } }),
    );
    expect(copy.sentence).toBe("The project “Notion mirror” slipped by 1 day");
  });

  it("puts the mirror's own reason under a refused push, not a paraphrase of it", () => {
    const copy = rowCopy(
      row({
        id: undefined,
        kind: "sync_failed",
        actor: undefined,
        payload: {
          jobId: 41,
          destination: "notion",
          attempts: 5,
          error: "The target page is locked by another workspace",
        },
      }),
    );
    expect(copy).toEqual({
      sentence: "The Notion mirror refused this write",
      detail: "The target page is locked by another workspace",
    });
  });

  it("still says something when the mirror gave no reason", () => {
    const copy = rowCopy(row({ kind: "sync_failed", actor: undefined, payload: {} }));
    expect(copy.detail).toBe("The change is kept in the queue.");
  });

  // The outbox serves more than Notion now, so the row has to name who refused —
  // "the Notion mirror refused this" on a push that never went near Notion sends
  // somebody to the wrong settings page.
  it("names the destination that refused, when it is not Notion", () => {
    const copy = rowCopy(
      row({ kind: "sync_failed", actor: undefined, payload: { destination: "github" } }),
    );
    expect(copy.sentence).toBe("The github refused this write");
  });

  it("falls back to Notion for a server too old to say where the push was going", () => {
    const copy = rowCopy(row({ kind: "sync_failed", actor: undefined, payload: {} }));
    expect(copy.sentence).toBe("The Notion mirror refused this write");
  });

  it("names the field two versions disagree about", () => {
    const copy = rowCopy(
      row({
        kind: "conflict",
        actor: undefined,
        payload: { field: "title", mine: "a", theirs: "b" },
      }),
    );
    expect(copy.sentence).toBe("Two versions of the title");
  });

  it("survives an actor whose account is gone", () => {
    expect(rowCopy(row({ actor: undefined })).sentence).toBe("This ticket was assigned to you");
  });

  it("survives a ticket that has since been deleted", () => {
    expect(rowCopy(row({ subject: undefined })).detail).toBeUndefined();
  });
});

describe("which group a row belongs to", () => {
  it("splits on read, not on time — the drawing's two headings are Unread and Earlier", () => {
    expect(groupOf(row({}))).toBe("unread");
    expect(groupOf(row({ readAt: "2026-08-17T10:05:00Z" }))).toBe("earlier");
  });
});

describe("how long ago", () => {
  const now = new Date("2026-08-17T12:00:00Z");
  const ago = (iso: string) => relativeTime(iso, now);

  it("counts in minutes, then hours, then days", () => {
    expect(ago("2026-08-17T11:56:00Z")).toBe("4 min ago");
    expect(ago("2026-08-17T11:38:00Z")).toBe("22 min ago");
    expect(ago("2026-08-17T11:00:00Z")).toBe("1 h ago");
    expect(ago("2026-08-17T04:00:00Z")).toBe("8 h ago");
  });

  it("says yesterday once, and counts days after that", () => {
    expect(ago("2026-08-16T11:00:00Z")).toBe("yesterday");
    expect(ago("2026-08-15T11:00:00Z")).toBe("2 days");
  });

  it("says now for anything under a minute, including a clock skewed forwards", () => {
    expect(ago("2026-08-17T11:59:30Z")).toBe("just now");
    // The server stamps `created_at`; a browser clock a few seconds behind it must
    // not produce "in 4 minutes", which would read as a bug in the inbox.
    expect(ago("2026-08-17T12:00:30Z")).toBe("just now");
  });
});
