import type { NotionParentPage, NotionParentPages } from "@/lib/api";

/**
 * What the parent-page picker says, and how it names a page.
 *
 * Pure and its own module for the reason `inbox/copy.ts` gives: this is the part of the
 * step that can be wrong in a way nobody notices. An empty list still renders — and an
 * empty list is precisely the case that needs a sentence, because it is the diagnosis of
 * the failure the old "paste 32 hex characters" field used to hide until bootstrap.
 */

/**
 * A page's name in the list.
 *
 * Notion allows a page with no title, so the picker meets one. Dropping it would leave
 * the page somebody just shared missing from the list with nothing said about it — the
 * exact failure the picker exists to remove. The head of the id is what makes two
 * untitled pages different rows, and it is also what the URL ends with, so it can be
 * checked against the page open in the other tab.
 */
export function pageLabel(page: NotionParentPage): string {
  const title = page.title?.trim();
  if (title) return title;
  return `Untitled page · ${page.id.replace(/-/g, "").slice(0, 8)}`;
}

export type PickerNotice = { heading: string; body: string };

/**
 * The one sentence the whole feature turns on.
 *
 * `available` false is a setup step that has not happened — no token, nothing to search.
 * `available` with no pages is a different thing entirely: Notion answered, and no page
 * is shared with the integration. That is the silent failure of the old flow, so it is
 * named as a diagnosis with the fix in it, in Notion's own words (`•••` → Connections),
 * plus the two things that make it survivable — the list can be reloaded, and the id can
 * still be entered by hand, because Notion's search is eventually consistent and can omit
 * a page shared ten seconds ago.
 */
export function pickerNotice({
  loading,
  error,
  answer,
}: {
  loading?: boolean;
  error?: string;
  answer?: NotionParentPages;
}): PickerNotice | null {
  if (error) {
    return { heading: "Kanso could not ask Notion", body: error };
  }
  if (loading || !answer) return null;

  if (!answer.available) {
    return {
      heading: "No page to choose from yet",
      body: answer.reason ?? "Kanso cannot list this workspace's pages.",
    };
  }

  if (answer.pages.length === 0) {
    return {
      heading: "Notion answered, and no page is shared with Kanso",
      body:
        "That is the whole problem the old form could not tell you about. In Notion, open the page " +
        "you want the four databases created under, then its ••• menu → Connections → and add this " +
        "integration. Reload the list afterwards. Notion's search lags a few seconds behind a page " +
        "you have just shared, so if it still does not appear, enter the page id by hand below.",
    };
  }

  return null;
}

/**
 * The page id in whatever was pasted.
 *
 * The escape hatch has to stay — Notion's search is eventually consistent, and a picker
 * that cannot offer the page you shared a moment ago with no way past it is worse than
 * the field it replaced. But nobody should have to slice a URL by hand to use it: the id
 * is the last 32-hex run of a page URL, with or without dashes. Anything that holds no
 * such run is returned trimmed and otherwise untouched, so typing into the field still
 * behaves like typing.
 */
export function pageIdFrom(raw: string): string {
  const value = raw.trim();
  const dashed = value.match(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i);
  if (dashed) return dashed[0];
  const plain = value.match(/[0-9a-f]{32}/gi);
  if (plain) return plain[plain.length - 1];
  return value;
}

/**
 * Whether two ids name the same page.
 *
 * Notion answers a dashed uuid and a person who read one out of a URL pasted the bare
 * 32-hex form, so the two spellings of one page have to compare equal — otherwise a page
 * that is already saved never shows as selected, and every instance set up before this
 * picker existed would look unconfigured. Empty matches nothing, including empty.
 */
export function sameId(a: string, b: string): boolean {
  const bare = (value: string) => value.trim().replace(/-/g, "").toLowerCase();
  return bare(a).length > 0 && bare(a) === bare(b);
}
