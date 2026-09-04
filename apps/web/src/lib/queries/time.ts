"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { timeApi, type CorrectTimeBody, type LogTimeBody, type StartTimerBody } from "../api";

/**
 * KAN-26 — one ticket's hours.
 *
 * **Its own first key segment, deliberately not under `["tickets"]`.** `queries/core.ts`'s
 * optimistic writer walks every entry in that family and paints a guessed ticket page into
 * it; a timesheet caught by that would have its rows replaced by a shape that has none.
 * `queries/me-stats.ts` states the same caution for the same reason.
 *
 * No `staleTime`. Every other read in this app can afford to be a minute old; a running
 * clock cannot — a person who stops a timer in one tab and looks at another should not be
 * shown a clock that is still going. The rows are a handful and the read is one indexed
 * `SUM`, so the cost of asking again is not worth a stale total on an invoice line.
 */
export const timeKeys = {
  all: ["time"] as const,
  ofTicket: (ticketId: string) => ["time", "ticket", ticketId] as const,
};

export const useTicketTime = (ticketId: string) =>
  useQuery({ queryKey: timeKeys.ofTicket(ticketId), queryFn: () => timeApi.ofTicket(ticketId) });

/**
 * Every write, invalidating and never optimistic.
 *
 * The one deliberate refusal in this file. A star is worth painting before the server
 * answers — `useToggleFavourite` argues that, and it is right, because a star that appears a
 * round trip later reads as a key that did not work. A **duration** is not: guessing it
 * would mean the client computing a total the server is the authority on, and the two
 * disagreeing for a moment on a number somebody is about to invoice. Worse, the writes here
 * are the ones with refusals behind them — a second clock is a 409, a typo is a 400 — so an
 * optimistic row would appear, then vanish, having briefly told the person a lie about their
 * own timesheet.
 *
 * `timeKeys.all` and not the one ticket's key: starting a clock settles nothing but it *can*
 * be refused because of a clock on another ticket, and a stop reaches a row this screen may
 * not be showing. The family is small and refetching it is one query.
 */
function useTimeWrite<TArgs>(mutationFn: (args: TArgs) => Promise<unknown>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn,
    onSettled: () => client.invalidateQueries({ queryKey: timeKeys.all }),
  });
}

export const useLogTime = (ticketId: string) =>
  useTimeWrite((body: LogTimeBody) => timeApi.log(ticketId, body));

export const useStartTimer = (ticketId: string) =>
  useTimeWrite((body: StartTimerBody) => timeApi.start(ticketId, body));

export const useStopTimer = () => useTimeWrite((entryId: string) => timeApi.stop(entryId));

export const useCorrectTime = () =>
  useTimeWrite(({ entryId, body }: { entryId: string; body: CorrectTimeBody }) =>
    timeApi.correct(entryId, body),
  );

export const useRemoveTime = () => useTimeWrite((entryId: string) => timeApi.remove(entryId));
