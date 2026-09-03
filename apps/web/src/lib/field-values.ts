import type { CustomFieldType, CustomFieldValue } from "./api";

/**
 * What a DOM control's string becomes on the wire.
 *
 * This file exists because of one trap, and it is the trap the server's whole type story is
 * built to catch: **every HTML input hands back a string.** `<input type="number">` included
 * — `event.target.value` for a number box is `"3"`, not `3`. Sent as it comes, a number field
 * would be refused by `FieldValueCodec` with "Field \"Size\" expects a number; got the string
 * \"3\"", which is the guard working correctly and a bug on this side of the wire.
 *
 * So the coercion happens once, here, rather than at each of the four controls. Extracted
 * rather than inlined for the reason `lib/seat.ts` gives about its two predicates: the inline
 * spelling was already the thing that went wrong, and a named function is what a test can
 * reach. `field-values.test.ts` is that test — the repo tests extracted helpers, not
 * rendering.
 *
 * Returns `null` for "clear this field", which the server reads as deleting the row. An empty
 * text box and an empty number box both mean that; a `select` returns null only for its
 * explicit blank option, since a blank is never one of its choices.
 */
export function valueFromControl(
  type: CustomFieldType,
  raw: string,
): CustomFieldValue | null {
  switch (type) {
    case "text":
      // Trimmed here as well as on the server. Not redundant: this is what decides whether
      // the request says `null` or `"  "`, and the server would read the second as a clear
      // anyway — so without the trim, clearing a *required* text field would look like a
      // value to this client and be refused by the server, with no way to tell why.
      return raw.trim() === "" ? null : raw.trim();

    case "number": {
      if (raw.trim() === "") return null;
      const parsed = Number(raw);
      // `Number("")` is 0 and `Number("abc")` is NaN, and neither may be sent: the first is a
      // real estimate nobody typed, and the second is not JSON. A half-typed "-" or "1e"
      // lands here too, which is why this returns null rather than throwing — the control is
      // mid-edit, not wrong.
      return Number.isFinite(parsed) ? parsed : null;
    }

    case "boolean":
      // A checkbox's `checked` is stringified by the caller, so this is the one type whose
      // "empty" is a real value: an unticked box is `false`, not absent.
      return raw === "true";

    case "select":
      return raw === "" ? null : raw;
  }
}

/**
 * How a value reads in a control, which is the inverse and is *not* symmetric.
 *
 * `undefined` becomes the empty string because that is what an unset input shows; `false`
 * becomes `"false"` rather than the empty string, because an unticked checkbox is a value.
 * Collapsing those two is how a boolean field would lose the difference between "no" and
 * "not answered" — a difference the table keeps by having no row for the second.
 */
export function valueToControl(value: CustomFieldValue | undefined): string {
  if (value === undefined) return "";
  return String(value);
}

/**
 * What a value looks like read back, for the places that print rather than edit — a chip on
 * a ticket page, a cell in a list.
 *
 * Booleans read as words rather than as `true`/`false`: a chip saying `Regression true` is a
 * developer's rendering of a question somebody asked in English.
 */
export function formatValue(value: CustomFieldValue | undefined): string {
  if (value === undefined) return "—";
  if (typeof value === "boolean") return value ? "Yes" : "No";
  return String(value);
}
