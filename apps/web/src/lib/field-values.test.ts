import { describe, expect, it } from "vitest";

import { formatValue, valueFromControl, valueToControl } from "./field-values";

describe("field-values", () => {
  /**
   * The trap this file exists for. `<input type="number">` hands back `"3"`, and a `"3"` in a
   * number field is refused by the server — correctly, which is what makes it a bug here
   * rather than there.
   */
  it("turns a number box's string into an actual number", () => {
    expect(valueFromControl("number", "3")).toBe(3);
    expect(valueFromControl("number", "3.5")).toBe(3.5);
    expect(valueFromControl("number", "-2")).toBe(-2);
    // Not the string.
    expect(valueFromControl("number", "3")).not.toBe("3");
  });

  it("reads an empty box as a clear, for every type that has one", () => {
    expect(valueFromControl("text", "")).toBeNull();
    expect(valueFromControl("text", "   ")).toBeNull();
    expect(valueFromControl("number", "")).toBeNull();
    expect(valueFromControl("select", "")).toBeNull();
  });

  /**
   * `Number("")` is 0 and `Number("abc")` is NaN. Sending the first would be an estimate
   * nobody typed; the second is not JSON at all and would reach a jsonb column as `NaN`.
   */
  const NOT_NUMBERS = ["abc", "-", "1e", "1.2.3", "  "];
  it.each(NOT_NUMBERS)("refuses to invent a number from %o", (raw) => {
    expect(valueFromControl("number", raw)).toBeNull();
  });

  it("trims text, so two values that read identically are identical", () => {
    expect(valueFromControl("text", "  high ")).toBe("high");
  });

  /**
   * The one type whose "empty" is a real value. An unticked box is `false`, not absent —
   * collapsing the two would lose the difference between "no" and "nobody answered", which
   * is the difference the table keeps by having no row for the second.
   */
  it("treats an unticked box as false rather than as unset", () => {
    expect(valueFromControl("boolean", "false")).toBe(false);
    expect(valueFromControl("boolean", "true")).toBe(true);
    expect(valueFromControl("boolean", "false")).not.toBeNull();
  });

  it("round-trips a value back into its control", () => {
    expect(valueToControl("high")).toBe("high");
    expect(valueToControl(3)).toBe("3");
    expect(valueToControl(true)).toBe("true");
    // Absent shows as an empty control...
    expect(valueToControl(undefined)).toBe("");
    // ...and `false` does not, for the reason above.
    expect(valueToControl(false)).toBe("false");
  });

  it("prints a boolean as a word, because a chip is read in English", () => {
    expect(formatValue(true)).toBe("Yes");
    expect(formatValue(false)).toBe("No");
    expect(formatValue(undefined)).toBe("—");
    expect(formatValue("high")).toBe("high");
    expect(formatValue(3.5)).toBe("3.5");
  });

  /**
   * The property the four cases above are each half of: whatever a control produces,
   * feeding it back in draws the same thing. A field that lost its value on a re-render
   * would be a form that silently discards typing.
   */
  const ROUND_TRIP = [
    { type: "text", raw: "high" },
    { type: "number", raw: "42" },
    { type: "boolean", raw: "true" },
    { type: "boolean", raw: "false" },
    { type: "select", raw: "low" },
  ] as const;
  it.each(ROUND_TRIP)("survives a control round trip for $type $raw", ({ type, raw }) => {
    const value = valueFromControl(type, raw);
    expect(value).not.toBeNull();
    expect(valueToControl(value as string | number | boolean)).toBe(raw);
  });
});
