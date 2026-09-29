/**
 * One line a person can read, for an error a far side wrote for a machine.
 *
 * `last_error` keeps the raw text on purpose — it is the only record of what Notion said —
 * so this runs in the browser and never replaces it: `detail` is the raw text, and the row
 * shows it behind a disclosure.
 *
 * Notion answers a bad body with every branch of its union that failed, one line per
 * property per type, so a property that was fine can appear after the one that was not.
 * Order of appearance is the only signal it gives, which is why the names keep it.
 */
export type ReadableError = { summary: string; detail?: string };

const NOTION_VALIDATION = /^Notion API 400 \(validation_error\)/;
// `NotionOutboundHandler.blockedBy`: the dependency's own error follows, and it is that
// row's to explain, so the summary names only what this one is waiting on.
const BLOCKED = /^Blocked by (\w+) [0-9a-f-]+, which Notion refused:/;
// Lazy up to the type key, so a name with a space — `Kanso ID` — survives whole.
const REFUSED_PROPERTY = /body\.properties\.(.+?)\.[a-z_]+ should be/g;
const MAX_NAMES = 3;
const MAX_SUMMARY = 160;

export function readableError(raw: string | null | undefined): ReadableError | null {
  const text = raw?.trim();
  if (!text) return null;

  const blocked = text.match(BLOCKED);
  if (blocked) return { summary: `Waiting on a ${blocked[1]} Notion refused`, detail: text };

  if (NOTION_VALIDATION.test(text)) {
    const names = [...new Set([...text.matchAll(REFUSED_PROPERTY)].map((m) => m[1]))];
    if (names.length > 0) {
      const shown = names.slice(0, MAX_NAMES);
      const verb = shown.length === 1 ? "is" : "are";
      const summary = `Notion refused the page: ${shown.join(", ")} ${verb} invalid`;
      return { summary, detail: text };
    }
  }

  const first = text.split("\n")[0].trim();
  const summary = first.length > MAX_SUMMARY ? `${first.slice(0, MAX_SUMMARY - 1)}…` : first;
  return summary === text ? { summary } : { summary, detail: text };
}
