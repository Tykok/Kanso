import { describe, expect, it } from "vitest";

import { applyTemplate, clearTemplate, type Filled, type Touched } from "./template-fill";
import type { ResolvedTemplate } from "./api";

const seed = (): Filled => ({
  title: "",
  description: "",
  priority: "none",
  estimate: "",
  labelIds: [],
  fieldValues: {},
});

const resolved = (over: Partial<ResolvedTemplate> = {}): ResolvedTemplate => ({
  title: "[Bug] ",
  description: "## What happens\n",
  priority: "high",
  estimate: null,
  labelIds: ["label-bug"],
  fieldValues: {},
  unresolved: { labels: [], fields: [] },
  ...over,
});

const touched = (...keys: (keyof Filled)[]): Touched => new Set(keys);

describe("applyTemplate", () => {
  it("places every field the template names", () => {
    const filled = applyTemplate(seed(), touched(), resolved(), seed());
    expect(filled.title).toBe("[Bug] ");
    expect(filled.description).toBe("## What happens\n");
    expect(filled.priority).toBe("high");
    expect(filled.labelIds).toEqual(["label-bug"]);
  });

  it("leaves a field the person has edited alone", () => {
    const current = { ...seed(), title: "Login is broken" };
    const filled = applyTemplate(current, touched("title"), resolved(), seed());
    expect(filled.title).toBe("Login is broken");
    // Everything they did not touch still comes from the template.
    expect(filled.description).toBe("## What happens\n");
  });

  it("re-resolving replaces what the template placed, not what the person typed", () => {
    const first = applyTemplate(seed(), touched(), resolved(), seed());
    const edited = { ...first, description: `${first.description}I clicked login.\n` };

    const second = applyTemplate(
      edited,
      touched("description"),
      resolved({ labelIds: ["label-other"] }),
      seed(),
    );

    expect(second.description).toBe("## What happens\nI clicked login.\n");
    expect(second.labelIds).toEqual(["label-other"]);
  });

  /**
   * The failure this guards is subtle: without falling back to the seed, the second
   * template's silence would leave the first template's title on screen, and nothing on the
   * form would say where it came from.
   */
  it("an unnamed key falls back to the seed, not to the last template", () => {
    const first = applyTemplate(seed(), touched(), resolved(), seed());
    const second = applyTemplate(first, touched(), resolved({ title: null }), seed());
    expect(second.title).toBe("");
  });

  it("carries an estimate across as the select's string", () => {
    const filled = applyTemplate(seed(), touched(), resolved({ estimate: 3 }), seed());
    expect(filled.estimate).toBe("3");
  });

  it("keeps a field value in the type the server normalised it to", () => {
    const filled = applyTemplate(
      seed(),
      touched(),
      resolved({ fieldValues: { "field-1": true, "field-2": 3 } }),
      seed(),
    );
    expect(filled.fieldValues).toEqual({ "field-1": true, "field-2": 3 });
  });
});

describe("clearTemplate", () => {
  it("restores the seed for untouched fields and keeps hand edits", () => {
    const filled = applyTemplate(seed(), touched(), resolved(), seed());
    const edited = { ...filled, title: "Login is broken" };

    const cleared = clearTemplate(edited, touched("title"), seed());

    expect(cleared.title).toBe("Login is broken");
    expect(cleared.description).toBe("");
    expect(cleared.labelIds).toEqual([]);
  });
});
