import { expect } from "@playwright/test";
import { ADMIN, apiAs } from "./support";

/**
 * Waits until the outbound mirror queue is empty.
 *
 * A ticket seeded through `POST /api/tickets` is not finished when the response arrives.
 * The same transaction enqueues a Notion job (`TicketService.kt`), `OutboundWorker` drains
 * on a 500 ms clock, and with the mirror off the handler still writes — it flips
 * `sync_state` to `disabled` so the screen can say "mirror off" instead of "pending"
 * forever. That write goes through `tickets_set_updated_at`, the `BEFORE UPDATE` trigger
 * `V2__sync_engine.sql` installed, so **`updated_at` is rewritten on every seeded ticket
 * somewhere between 0 and 500 ms after it was created** — later still on a populated
 * instance, because the drain takes ten jobs per pass and the queue is shared.
 *
 * The main list is `ORDER BY updated_at DESC, number DESC`. Once the pass is over the
 * order is unchanged, because the rows are rewritten in the order they were made — so
 * nothing about this is visible at rest. Half way through a pass it is: the rows that have
 * been rewritten carry a stamp half a second newer than the rows that have not, and the
 * list comes back in an order that is neither the creation order nor its reverse. Measured
 * on `8e04063`: three tickets seeded 10 ms apart, and the list handed the browser
 * `3, 1, 2`.
 *
 * Two specs can see that and no others can. `keyboard.spec.ts` and `24-shortcuts.spec.ts`
 * are the only ones that press a key to move a cursor and then assert *which* row it
 * landed on — everything else names the row it wants. So a fixture that has not settled
 * shows up as "the keyboard is broken", which is what it looked like for two tickets.
 *
 * Called after seeding and before the browser opens, so the rows the list is asked for
 * have the sort key they are going to keep. It is a condition and not a pause: with the
 * queue already empty it returns on the first poll, and when it cannot drain it fails
 * saying so rather than going quiet.
 *
 * Not the whole answer, and it should not be read as one. A mirror push counting as an
 * edit — a ticket nobody touched moving to the top of "recently updated" — is a bug in the
 * application, not in these specs, and it needs the trigger to leave `updated_at` alone
 * for a bookkeeping write. That is a migration and a ticket of its own.
 */
/**
 * Waits until the server's own copy of the reader's keyboard satisfies [stored].
 *
 * `useSavePreferences` is optimistic: `onMutate` writes the `/api/me` cache before the
 * `PUT` is sent, so a key appearing in the table is the *guess* appearing, not the row
 * being saved. `24-shortcuts.spec.ts` reloads immediately after asserting that — which is
 * the right intent, and says so ("reloading is what says it was stored rather than
 * drawn") — but a reload issued the moment the guess is drawn can outrun the request that
 * would have made it true, and then the reload reads the preferences from before the
 * capture. Two assertions in that file fail exactly there, one per reload.
 *
 * So the reload happens once the server agrees, which is the thing the reload was put
 * there to check. [stored] is asked of `preferences.shortcuts` as the server holds it.
 */
export async function preferencesStored(
  stored: (shortcuts: Record<string, string[]>) => boolean,
  what: string,
): Promise<void> {
  const api = await apiAs(ADMIN);
  try {
    await expect
      .poll(
        async () => {
          const response = await api.get("/api/me");
          if (!response.ok()) return false;
          const body = (await response.json()) as {
            preferences?: { shortcuts?: Record<string, string[]> };
          };
          return stored(body.preferences?.shortcuts ?? {});
        },
        { message: `The server never stored ${what}`, timeout: 15_000, intervals: [50] },
      )
      .toBe(true);
  } finally {
    await api.dispose();
  }
}

export async function mirrorQueueDrained(): Promise<void> {
  const api = await apiAs(ADMIN);
  try {
    await expect
      .poll(
        async () => {
          const response = await api.get("/api/admin/sync");
          // `-1` rather than a throw: one refused read during a redeploy is not the
          // answer to "has the queue drained", and the poll's own timeout is what says
          // it never will.
          if (!response.ok()) return -1;
          const body = (await response.json()) as { jobs?: { pending?: number } };
          // Omitted rather than zero when there is nothing waiting, which is the shape
          // `/api/admin/sync` actually sends.
          return body.jobs?.pending ?? 0;
        },
        {
          message: "The outbound mirror queue never drained, so the list's order is unsettled",
          timeout: 30_000,
          intervals: [50],
        },
      )
      .toBe(0);
  } finally {
    await api.dispose();
  }
}
