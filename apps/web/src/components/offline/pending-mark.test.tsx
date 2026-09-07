import { render, screen } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useOffline } from "@/store/offline";
import { PendingWriteMark } from "./pending-mark";

/**
 * What a row says about a write of its own that is still on disk — `KAN-89`.
 *
 * The mark and not the status: the main list is the grouped answer, where a guess is
 * answered by a refetch that offline never lands, so the row keeps the status the server
 * last gave it. Moving the row anyway would need a bucket count only SQL can produce.
 *
 * No jest-dom in this project, so the assertions read the DOM directly — see
 * `vitest.setup.dom.mts`, which installs cleanup and nothing else.
 */

/** Refuses whatever the flush sends, the way a server that answered 403 would. */
vi.mock("@/lib/api", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/api")>()),
  sendRaw: () => Promise.reject({ status: 403, message: "read-only seat" }),
}));

const write = (path: string) => ({
  reference: "KAN-142",
  summary: "status → Done",
  request: { path, method: "PATCH", body: { status: "done" } },
});

describe("the mark on a row with a write waiting", () => {
  beforeEach(async () => {
    for (const held of useOffline.getState().writes) {
      await useOffline.getState().discard(held.id);
    }
  });

  it("draws nothing when the queue holds nothing of this row's", async () => {
    await useOffline.getState().hold(write("/api/tickets/t2"));

    const { container } = render(<PendingWriteMark ticketId="t1" />);

    // Every row on every screen renders this. Anything but nothing here would be a
    // column of marks on a list where nobody is offline.
    expect(container.innerHTML).toBe("");
  });

  it("says the write is queued, and puts the sentence in the title", async () => {
    await useOffline.getState().hold(write("/api/tickets/t1"));

    render(<PendingWriteMark ticketId="t1" />);

    const mark = screen.getByTestId("pending-write");
    expect(mark.textContent).toBe("queued");
    // The row has room for a word, not for a sentence — and somebody wondering what the
    // word means will hover it.
    expect(mark.getAttribute("title")).toContain("not saved yet");
  });

  it("says refused once the server has turned the write down", async () => {
    await useOffline.getState().hold(write("/api/tickets/t1"));
    // A flush against a server that refuses is what moves a write to `rejected` — the
    // state the banner offers Retry and Discard for, and the one a row must not call
    // merely queued.
    await useOffline.getState().flush();

    render(<PendingWriteMark ticketId="t1" />);

    expect(screen.getByTestId("pending-write").textContent).toBe("refused");
  });
});
