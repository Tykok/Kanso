"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { cn } from "@/lib/utils";
import type { Favourite, FavouriteKind, FavouriteTarget } from "@/lib/api";
import { useFavourites, useToggleFavourite } from "@/lib/queries";
import { useUi, type Scope } from "@/store/ui";
import { GroupLabel } from "./ui/group-label";

/**
 * KAN-10 — the pinned rows, above everything else in the column.
 *
 * Its own file rather than four more blocks inside `sidebar.tsx`: that component is read
 * by five branches at once and its three sections are already the most contended JSX in
 * the app. This costs it four lines.
 */

/**
 * Where a kind is reached, and the only place in the app that says so.
 *
 * Two of the four are *routes* and two are *scopes*, which is not an inconsistency to
 * paper over — it is what the sidebar already does one section further down. A team and a
 * project select what the list is showing and stay on the page; a saved view and a
 * document are pages of their own. So the row is a `<Link>` for one pair and a `<button>`
 * for the other, and neither pretends to be the other.
 */
const routeOf = (favourite: Favourite): string | undefined =>
  favourite.kind === "view"
    ? `/views/${favourite.id}`
    : favourite.kind === "doc"
      ? `/docs/${favourite.id}`
      : undefined;

const scopeOf = (favourite: Favourite): Scope | undefined =>
  favourite.kind === "team" || favourite.kind === "project"
    ? { kind: favourite.kind, id: favourite.id }
    : undefined;

/**
 * A glyph per kind, because four kinds in one list need telling apart and a name cannot
 * do it — a view and a document may both be called "Sync debt".
 *
 * Text glyphs and not an icon set: the sidebar already draws its `+` and its `⋯` this way,
 * and four characters is not worth the twelve kilobytes of a second icon import.
 */
const GLYPH: Record<FavouriteKind, string> = {
  team: "◆",
  project: "●",
  view: "≡",
  doc: "▤",
};

const KIND_NAME: Record<FavouriteKind, string> = {
  team: "team",
  project: "project",
  view: "saved view",
  doc: "document",
};

export function Favourites({ onNavigate }: { onNavigate?: () => void }) {
  const { scope, setScope, showArchived } = useUi();
  const pathname = usePathname();
  const favourites = useFavourites();
  const { mutate: flip } = useToggleFavourite();

  /**
   * Filtered here rather than asked for: the server sends every pin with its `archived`
   * on it, and this toggle is `useUi` state, so honouring it is a filter over one cache
   * entry and flipping it repaints instead of refetching.
   */
  const rows = (favourites.data ?? []).filter((row) => showArchived || !row.archived);

  /**
   * Nothing at all until something is pinned — not an empty state, and not a heading over
   * a blank space. The section is the reader's own doing, and a caption for a list they
   * have not started is a permanent instruction at the top of every screen.
   */
  if (rows.length === 0) return null;

  return (
    <div>
      <GroupLabel className="pt-0">Favourites</GroupLabel>

      {rows.map((favourite) => {
        const route = routeOf(favourite);
        const target = scopeOf(favourite);
        const current = route
          ? pathname === route
          : scope.kind !== "all" && scope.kind === favourite.kind && scope.id === favourite.id;

        const label = favourite.label || "…";

        return (
          <div
            key={`${favourite.kind}-${favourite.id}`}
            data-testid="nav-item"
            data-kind={favourite.kind}
            data-favourite="true"
            data-current={current}
            data-archived={favourite.archived}
            className={cn(
              "nav-item",
              "group flex items-center gap-1 rounded-md pr-1.5",
              current
                ? "bg-accent-soft font-medium text-foreground"
                : "text-muted-foreground hover:bg-accent",
            )}
          >
            {route ? (
              <Link
                className={cn(
                  "flex min-w-0 flex-1 items-center gap-2 py-[5px] pl-1.5 text-left",
                  favourite.archived && "opacity-55",
                )}
                href={route}
                aria-current={current}
                onClick={onNavigate}
                title={`${label} — ${KIND_NAME[favourite.kind]}`}
              >
                <Glyph kind={favourite.kind} />
                <span className="truncate">{label}</span>
              </Link>
            ) : (
              <button
                className={cn(
                  "flex min-w-0 flex-1 items-center gap-2 py-[5px] pl-1.5 text-left",
                  favourite.archived && "opacity-55",
                )}
                aria-current={current}
                onClick={() => {
                  if (target) setScope(target);
                  onNavigate?.();
                }}
                title={`${label} — ${KIND_NAME[favourite.kind]}`}
              >
                <Glyph kind={favourite.kind} />
                <span className="truncate">{label}</span>
              </button>
            )}

            {/*
              The direction lives here, not in the registry: `Action.label` is one static
              string and `favourite.toggle` has to be true of both halves of a flip, so
              the palette says "Favourite" and this button says which way this press goes.
              Filled star, always — every row in this section is pinned by definition, and
              a hollow one would ask the reader to work out what it means.
            */}
            <button
              type="button"
              className="flex size-5 shrink-0 items-center justify-center rounded-sm text-11 text-primary hover:bg-accent"
              aria-label={`Remove ${label} from favourites`}
              title={`Remove ${label} from favourites`}
              onClick={() => flip({ target: { kind: favourite.kind, id: favourite.id }, pinned: true })}
            >
              ★
            </button>
          </div>
        );
      })}
    </div>
  );
}

/**
 * The star a page header carries, for the two kinds whose page is not the ticket list.
 *
 * A control and not a key, and that is the honest shape rather than a shortfall. `s` is
 * dispatched by `app/page.tsx` and by `views/shell.tsx`, which are the two surfaces where
 * the subject is a team or a project; `/views/[id]` runs a hand-written key handler that
 * never reaches the registry and `/docs/[id]` gives its keys to the caret, where `s` is a
 * letter somebody is typing. Neither route mounts the command palette either. So the
 * gesture those two screens get is the one every reader already knows, sitting next to
 * the thing it is about — which is a better answer than a key nobody can press.
 *
 * It draws its own state, unlike the sidebar's star: a page header has no section heading
 * saying "these are pinned", so hollow and filled is the only thing that says which.
 */
export function FavouriteStar({ target, label }: { target: FavouriteTarget; label: string }) {
  const favourites = useFavourites();
  const { mutate: flip } = useToggleFavourite();

  // Undefined until the list has loaded, and the button waits rather than guessing: a
  // star that starts hollow and fills in on its own would have been lying for a moment,
  // and a press during that moment would pin something already pinned.
  const pinned = favourites.data?.some((row) => row.kind === target.kind && row.id === target.id);
  if (pinned === undefined) return null;

  const say = pinned ? `Remove ${label} from favourites` : `Add ${label} to favourites`;

  return (
    <button
      type="button"
      className={cn(
        "flex size-[22px] shrink-0 items-center justify-center rounded-sm hover:bg-accent",
        pinned ? "text-primary" : "text-faint hover:text-foreground",
      )}
      aria-pressed={pinned}
      aria-label={say}
      title={say}
      onClick={() => flip({ target, pinned })}
    >
      {pinned ? "★" : "☆"}
    </button>
  );
}

/** Hidden from the accessibility tree: the `title` above already names the kind in words. */
function Glyph({ kind }: { kind: FavouriteKind }) {
  return (
    <span aria-hidden="true" className="w-3 shrink-0 text-center text-11 text-faint">
      {GLYPH[kind]}
    </span>
  );
}
