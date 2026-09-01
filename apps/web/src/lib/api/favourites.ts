/**
 * What one person pinned to the top of their own sidebar.
 *
 * The wire shapes come straight from `dev.kanso.favourites.FavouriteController`, and one
 * thing it deliberately does not send is a URL: which route a kind is reached at is this
 * side's business, and a path spelled in Kotlin would go stale the next time `app/` moves.
 * `components/favourites.tsx` is where that mapping lives instead.
 */

import { request } from "./core";

/**
 * Closed, and closed again by `V22` — which does it with one nullable foreign key per
 * kind and a `CHECK`, so a fifth kind is a column and a migration rather than a string.
 *
 * Tickets are not on the list on purpose. A favourite is a place you go back to and a
 * ticket is work that finishes, so pinning one either rots at the top of the column or
 * needs an unpin-on-done rule that throws away a decision somebody made; and it does not
 * draw, since a ticket is recognised as `KAN-142 · <title>`, which is two facts in a
 * column that holds one. Doc folders are out for a flatter reason: `/docs` draws the tree
 * and a folder has no route of its own to send anybody to.
 */
export const FAVOURITE_KINDS = ["team", "project", "view", "doc"] as const;
export type FavouriteKind = (typeof FAVOURITE_KINDS)[number];

/** What a favourite names. The pair the toggle takes, and the pair the row draws from. */
export type FavouriteTarget = { kind: FavouriteKind; id: string };

export type Favourite = FavouriteTarget & {
  /** A name somebody chose. The whole reason the server resolves these rather than the client. */
  label: string;
  /**
   * Whether the thing behind the pin has been put away. Every pin comes back whatever this
   * says, and the sidebar's own "Show archived" toggle decides which of them to draw — that
   * toggle is `useUi` state and never reaches the server, so the server does not guess.
   */
  archived: boolean;
};

const path = ({ kind, id }: FavouriteTarget) => `/api/me/favourites/${kind}/${id}`;

export const favouritesApi = {
  list: () => request<Favourite[]>("/api/me/favourites"),
  /** `PUT`, because the gesture is a toggle and its second press is a duplicate. */
  add: (target: FavouriteTarget) => request<void>(path(target), { method: "PUT" }),
  remove: (target: FavouriteTarget) => request<void>(path(target), { method: "DELETE" }),
};
