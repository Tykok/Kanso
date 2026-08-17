"use client";

import type { DocTemplate } from "@/lib/api";
import { GroupLabel } from "../ui/group-label";

/**
 * Screen 22's three cards.
 *
 * The stripes are the drawing's own: three bars at the widths it gives them, standing for
 * the shape of the writing rather than its words. They are decoration and say so — the
 * name and the caption underneath carry everything a reader needs.
 */
const STRIPES: Record<string, string[]> = {
  "cycle-note": ["70%", "88%", "52%"],
  decision: ["60%", "82%", "74%"],
  "incident-report": ["78%", "46%", "66%"],
};

const DEFAULT_STRIPES = ["72%", "84%", "58%"];

export function TemplateCards({
  templates,
  disabled,
  onChoose,
}: {
  templates: DocTemplate[];
  /** True when there is no team the reader may write in — the cards say so rather than 403. */
  disabled: boolean;
  onChoose: (template: DocTemplate) => void;
}) {
  return (
    <section className="flex flex-col gap-3.5">
      <GroupLabel className="px-0 pt-0">Start from a template</GroupLabel>

      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
        {templates.map((template) => (
          <button
            key={template.slug}
            data-testid="doc-template"
            disabled={disabled}
            title={disabled ? "You have no team to write in yet" : template.summary}
            className="flex flex-col gap-2 rounded-lg bg-card px-4 py-3.5 text-left shadow-flat hover:bg-accent disabled:cursor-default disabled:opacity-55 disabled:hover:bg-card"
            onClick={() => onChoose(template)}
          >
            <span className="text-13 font-medium">{template.name}</span>
            <span aria-hidden className="flex flex-col gap-[3px]">
              {(STRIPES[template.slug] ?? DEFAULT_STRIPES).map((width, index) => (
                <span
                  key={index}
                  className="h-1 rounded-[2px] bg-accent"
                  style={{ width }}
                />
              ))}
            </span>
            <span className="text-11 text-faint">{template.summary}</span>
          </button>
        ))}
      </div>
    </section>
  );
}
