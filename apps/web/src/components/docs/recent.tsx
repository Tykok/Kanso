"use client";

import Link from "next/link";
import type { DocFolder, DocPage, User } from "@/lib/api";
import { GroupLabel } from "../ui/group-label";
import { editedLabel } from "./outline";

/**
 * Screen 22's "recently changed": title, folder, who, how long ago.
 *
 * Four columns and the same grid for every row, so nothing has to be kept in line — and
 * the same `editedLabel` the document's own footer prints, minus its "Edited by" prefix,
 * because a column headed by nothing has no room to repeat the verb on every line.
 */
const COLS = "grid-cols-[1fr_150px_130px_90px]";

export function RecentlyChanged({
  pages,
  folders,
  people,
  now,
}: {
  pages: DocPage[];
  folders: DocFolder[];
  people: User[];
  /** Passed in so a server render and the first client render agree on the clock. */
  now?: Date;
}) {
  const names = Object.fromEntries(people.map((person) => [person.id, person.displayName]));
  const folderNames = Object.fromEntries(folders.map((folder) => [folder.id, folder.name]));

  if (pages.length === 0) {
    return (
      <section className="flex flex-col gap-2.5">
        <GroupLabel className="px-0 pt-0">Recently changed</GroupLabel>
        <span className="px-3 py-2 text-12 text-faint">
          Nothing written yet. A template above is the shortest way in.
        </span>
      </section>
    );
  }

  return (
    <section className="flex flex-col gap-2.5">
      <GroupLabel className="px-0 pt-0">Recently changed</GroupLabel>

      <div className="flex flex-col gap-row">
        {pages.map((page) => (
          <Link
            key={page.id}
            data-testid="doc-recent-row"
            href={`/docs/${page.id}`}
            className={`grid ${COLS} h-9 items-center gap-3.5 rounded-md px-3 text-muted-foreground hover:bg-accent`}
          >
            <span className="truncate text-foreground">{page.title}</span>
            <span className="truncate text-12 text-faint">
              {page.folderId ? (folderNames[page.folderId] ?? "") : ""}
            </span>
            <span className="truncate text-12">
              {page.editedById ? (names[page.editedById] ?? "") : ""}
            </span>
            {/* The prefix is dropped: "Edited by" would repeat on every line of a list
                whose third column is already the person's name. */}
            <span className="text-11 text-faint">
              {editedLabel({ ...page, editedById: undefined }, {}, now).replace("Edited ", "")}
            </span>
          </Link>
        ))}
      </div>
    </section>
  );
}
