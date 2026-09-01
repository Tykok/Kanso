"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { favouritesApi, type Favourite, type FavouriteTarget } from "../api";

/**
 * KAN-10 — one person's pins.
 *
 * One key, with nothing keyed on it. Unlike `keys.teams` and `keys.projects` there is no
 * archived flag in the key, because the request carries none: a person's pins are a
 * handful of rows and the server sends all of them with an `archived` on each, so the
 * sidebar's toggle is a filter over one cache entry rather than a second question.
 *
 * The first segment is what `applyEvents` reaches for. A team or a project deleted by
 * somebody else has already taken its pin with it — `V22`'s cascade — so the list on
 * screen is wrong until it is asked again, and `lib/realtime-events.ts` invalidates this
 * key on the back of the event that entity already publishes. Nothing new goes on the bus
 * for a favourite itself: every realtime destination is broadcast to every client, and one
 * person's sidebar is nobody else's business.
 */
export const favouriteKeys = { all: ["favourites"] as const };

export const useFavourites = () =>
  useQuery({ queryKey: favouriteKeys.all, queryFn: favouritesApi.list });

/**
 * Pin, or un-pin. One hook, because the sidebar's star and the `s` key are one gesture,
 * and the caller knows which way it is going from the list it is already drawing.
 *
 * Optimistic, and this is the one place in the sidebar where that is worth the code: a
 * star is pressed while somebody is looking straight at it, and a row that appears a round
 * trip later reads as a key that did not work. `onError` puts back exactly what was there
 * — no re-derivation, so a failed pin cannot invent an order — and `onSettled` asks the
 * server anyway, since only it can say what a new pin's label is.
 */
export function useToggleFavourite() {
  const client = useQueryClient();

  return useMutation({
    mutationFn: ({ target, pinned }: { target: FavouriteTarget; pinned: boolean }) =>
      pinned ? favouritesApi.remove(target) : favouritesApi.add(target),

    onMutate: async ({ target, pinned }) => {
      await client.cancelQueries({ queryKey: favouriteKeys.all });
      const previous = client.getQueryData<Favourite[]>(favouriteKeys.all);
      if (!previous) return { previous };

      client.setQueryData<Favourite[]>(
        favouriteKeys.all,
        pinned
          ? previous.filter((row) => !(row.kind === target.kind && row.id === target.id))
          : // Appended, because insertion order is the order and a new pin goes last —
            // the same answer the server will give when it replies. The label is the
            // server's to resolve and it has not answered yet, so the row carries none
            // and `FavouriteRow` draws a placeholder for the moment it takes: an id in
            // the sidebar would teach the reader the wrong thing about what a pin is.
            [...previous, { ...target, label: "", archived: false }],
      );
      return { previous };
    },

    onError: (_error, _variables, context) => {
      if (context?.previous) client.setQueryData(favouriteKeys.all, context.previous);
    },

    onSettled: () => client.invalidateQueries({ queryKey: favouriteKeys.all }),
  });
}
