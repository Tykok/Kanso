"use client";

import {
  useEffect,
  useId,
  useMemo,
  useRef,
  useState,
  type KeyboardEvent,
  type ReactNode,
} from "react";
import { Kbd } from "@/components/ui/kbd";
import type { ViewFilters } from "@/lib/api";
import { format, parse, suggest, type Suggestion } from "@/lib/filter-query";
import { useUi } from "@/store/ui";
import { chipsOf } from "./chips";
import { useFilterVocabulary } from "./filter-catalog";
import { FilterCompletions } from "./filter-completions";
import { applySuggestion, highlights, withoutFacet } from "./filter-text";

/**
 * The question, typed. One box, its completion list, and the chips of what is being asked.
 *
 * This replaces `filter-composer.tsx`, 240 lines of dialog whose whole shape was a list of
 * every facet crossed with every answer: fine for `Label is sync`, and unable to express
 * `-status:done`, two answers to one facet, or a range at all. The language does express
 * them (`lib/filter-query/`), so what was missing was somewhere to write one.
 *
 * ## Three truths, one direction each
 *
 * - **The text** is what the reader edits. Nothing writes it but them, a chip's `×` and an
 *   accepted suggestion.
 * - **`ViewFilters`** is what the query sends, and it is only ever `parse` of the text.
 * - **The chips** are a *rendering* of `ViewFilters` — never an input to it.
 *
 * ## Why the question is asked on `↵`, and not on every keystroke
 *
 * A saved view writes its filters straight through to the server, and the main list's
 * filters are part of the tickets query key. `status:t` is a legitimate mid-word state that
 * parses to *no filters at all* — so a box that asked on every keystroke would PATCH a
 * stored view eleven times to type `status:todo`, and three of those writes would have
 * emptied it. So the reader's line is a draft until `↵`, until the box loses focus, or
 * until a *value* is accepted from the list — that last one being what keeps the pointer
 * path live: click a key, click an answer, the rows narrow, and no key was ever pressed.
 *
 * The chips lag the text by exactly that much, and that is the honest reading of them: the
 * chips are the question being *asked*, the line is the question being *written*. `↵ apply`
 * appears beside the box whenever the two differ.
 *
 * ## What is deliberately not here
 *
 * No dialog. `dialog.kind === "filter"` — what `Mod+f` and the top bar's Filter button both
 * open — is drained into a *focus* of this box the moment it arrives, because the dispatcher
 * stands down entirely while a dialog is open and this box wants the opposite: `↑↓↵` inside
 * it, and every other key still live on the list behind it. Keeping the dialog and putting
 * the box inside it would have kept the modality the composer was rejected for, and split
 * the strip in two — chips outside, text inside — which is two places to read one question.
 */
export function FilterInput({
  filters,
  teamId,
  hide,
  onFilters,
  onSave,
  empty,
  search,
}: {
  filters: ViewFilters;
  /**
   * Whose cycles and labels can be asked about. Both are team-scoped and two teams may own
   * the name `sync`, so an unscoped list offers neither rather than offering both.
   */
  teamId?: string;
  /**
   * Facets this surface has already answered — the list scoped to a project passes
   * `project`, since the scope *is* that filter and `scopedFilters` would override a second
   * one anyway. It hides them from the completion list and nothing else: `parse` reads a
   * token whichever screen it was typed on, and a red underline under a word the server
   * answers would be the language lying about the endpoint behind it.
   */
  hide?: readonly (keyof ViewFilters)[];
  onFilters: (next: ViewFilters) => void;
  /** "Save this question", where there is one to save. Absent on a saved view. */
  onSave?: () => void;
  /** What to say with no chips up. The two surfaces are asking about different rooms. */
  empty?: ReactNode;
  /**
   * The plain "find a row on this screen" box, where the surface has one.
   *
   * It used to live in the top bar, three controls away from this one, which made one
   * screen ask its question in two places — and the narrower the window, the less the bar
   * could hold. It is a *slot* rather than a box built here because the two questions are
   * genuinely different: this component narrows the **answer** the server gives, that one
   * narrows what is **on screen** out of the answer already fetched. The list has both;
   * a saved view passes nothing and keeps the one.
   */
  search?: ReactNode;
}) {
  /** The words this instance knows, and the two ways an id is printed. See `filter-catalog`. */
  const { catalog, names, chipNames, settling } = useFilterVocabulary(teamId, hide);

  const dialog = useUi((state) => state.dialog);
  const close = useUi((state) => state.close);

  const [text, setText] = useState(() => format(filters, names));
  const [caret, setCaret] = useState(0);
  const [open, setOpen] = useState(false);
  const [active, setActive] = useState(0);
  const box = useRef<HTMLInputElement>(null);
  const mirror = useRef<HTMLDivElement>(null);
  const listId = useId();

  /** The question as stored, in words — what the text has to say to be in step with it. */
  const asked = format(filters, names);

  /**
   * The line, adopted when the question changes from outside the box.
   *
   * `seen` is what makes that answerable without a round trip: every write this component
   * makes records the words it expects back, so a change to `asked` that it did not make —
   * a saved view landing, the catalogue landing and turning ids into names, another surface
   * clearing the filters — is exactly a change `seen` has not been told about.
   *
   * Comparing the text against `format(parse(text))` instead was the other way to do it,
   * and it is wrong in the common case: `priority:hi` mid-word parses to nothing, so the
   * comparison would differ on every keystroke and the effect would overwrite the reader's
   * half-typed word with the last question they asked.
   */
  const seen = useRef(asked);
  useEffect(() => {
    if (asked === seen.current) return;
    seen.current = asked;
    setText(asked);
  }, [asked]);

  /**
   * `Mod+f`, and the Filter button, both arrive here — as a focus, not as a dialog.
   *
   * Drained on arrival: the store goes back to `none` in the same commit, so the dispatcher
   * is never standing down for a dialog nothing draws. That trap is exactly what
   * `lib/actions/organise.test.ts` pins in five cases, and none of them changes — the key is
   * still refused on the timeline, where this box is not mounted at all.
   */
  useEffect(() => {
    if (dialog.kind !== "filter") return;
    close();
    const input = box.current;
    if (!input) return;
    input.focus();
    input.setSelectionRange(input.value.length, input.value.length);
    setCaret(input.value.length);
    setOpen(true);
  }, [dialog.kind, close]);

  const parsed = useMemo(() => parse(text, catalog), [text, catalog]);
  /** Whether the line says something the chips do not yet — the whole of "there is a draft". */
  const unasked = format(parsed.filters, names) !== asked;

  // `settling` is "the catalogue has not landed", and while it holds nothing is underlined —
  // `filter-catalog.ts` says why a uuid printed for one paint is not a typo.
  const runs = useMemo(
    () => highlights(text, settling ? [] : parsed.errors),
    [text, settling, parsed.errors],
  );
  const complaint = settling ? undefined : parsed.errors[0];

  const asking = useMemo(() => suggest(text, caret, catalog), [text, caret, catalog]);
  // Clamped rather than reset in an effect: the list narrows as somebody types, and a
  // correction after the fact would render once with a highlight nobody can see.
  const at = Math.min(active, Math.max(asking.items.length - 1, 0));
  const current = asking.items[at];

  /** Both truths, each written in its own vocabulary. Nothing derives one from the other. */
  const ask = (next: ViewFilters, line: string) => {
    seen.current = format(next, names);
    setText(line);
    onFilters(next);
  };

  const commit = () => {
    // Nothing to ask, and saying so costs something: leaving the box would otherwise PATCH
    // a saved view with the filters it already holds, on every click anywhere else.
    if (unasked) ask(parsed.filters, text);
  };

  const accept = (item: Suggestion) => {
    const next = applySuggestion(text, asking, item.insert);

    /*
     * The element is written before the state, and that order is the whole trick.
     *
     * A caret cannot be placed until the text it points into is in the element, and the
     * text does not reach the element until React commits — so the alternative is an
     * effect, watching for the text to land, moving the caret afterwards. That effect
     * would have to reset its own trigger, which is a cascading render and is what the
     * compiler's lint refuses. Writing `value` first means React's next render finds the
     * string it was about to write already there, leaves the element alone, and the
     * selection set here stands.
     */
    const input = box.current;
    if (input) {
      input.focus();
      input.value = next.text;
      input.setSelectionRange(next.caret, next.caret);
    }
    setCaret(next.caret);
    setActive(0);

    // A key is half a question — `status:` asks nothing — so accepting one only writes.
    // A value finishes one, so accepting it asks, which is what makes the box work by
    // pointer alone: click `status`, click `Todo`, and the rows have narrowed.
    if (item.insert.endsWith(":")) setText(next.text);
    else ask(parse(next.text, catalog).filters, next.text);
  };

  const onKeyDown = (event: KeyboardEvent<HTMLInputElement>) => {
    // Nothing typed in here may also be moving a cursor or changing a status behind it.
    // `isTypingTarget` in the dispatcher already stands down for an input; the board and
    // the chart keep listeners of their own, and this is the line that holds against the
    // next one somebody adds.
    event.stopPropagation();

    if (event.key === "ArrowDown" || event.key === "ArrowUp") {
      event.preventDefault();
      if (!open) {
        setOpen(true);
        setActive(0);
        return;
      }
      const step = event.key === "ArrowDown" ? 1 : -1;
      setActive(Math.min(Math.max(at + step, 0), Math.max(asking.items.length - 1, 0)));
      return;
    }

    if (open && current && (event.key === "Enter" || event.key === "Tab")) {
      event.preventDefault();
      accept(current);
      return;
    }

    if (event.key === "Enter") {
      event.preventDefault();
      commit();
      return;
    }

    // The list first, the box second — so a reader who opened the list by accident is not
    // also thrown out of what they were typing. With the list closed the box hands the key
    // on by blurring, and blurring is what asks the question.
    if (event.key === "Escape") {
      event.preventDefault();
      if (open) setOpen(false);
      else event.currentTarget.blur();
    }
  };

  const chips = chipsOf(filters, chipNames);

  return (
    <div className="flex flex-col gap-2 px-6 pt-[18px] pb-3.5 max-[720px]:px-4 max-[720px]:pt-3">
      <div className="flex items-center gap-2">
        {search}

        {/*
          * The facet language, and not on a phone.
          *
          * `-status:done assignee:@me cycle:24` is written, not tapped: it wants a
          * keyboard, a completion list navigated with `↑↓`, and a line long enough to read
          * back. None of the three survives a 390px screen, and stacking it under the
          * search box would put two text fields on a screen the reader wanted one field on.
          * The chips below stay visible at every width, so a question asked at a desk is
          * still legible — and still removable, one `×` at a time — from a phone.
          */}
        <div className="relative min-w-0 flex-1 max-[720px]:hidden">
          <div className="relative rounded-md border border-border bg-card">
            {/*
              * The same characters as the input, in the same font at the same offset, with
              * nothing but the underlines visible. It is how a bad *word* is marked rather
              * than the whole box: `text-transparent` still paints a text decoration, so
              * the wavy line lands under exactly the offsets `parse` complained about.
              *
              * `scrollLeft` is mirrored below, because a line longer than the box scrolls
              * inside the input and an underline that stayed put would drift off its word.
              */}
            <div
              ref={mirror}
              aria-hidden
              className="pointer-events-none absolute inset-0 overflow-hidden whitespace-pre px-2 py-1.5 font-mono text-12 leading-5 text-transparent"
            >
              {runs.map((run, index) =>
                run.error ? (
                  <span
                    key={index}
                    className="underline decoration-urgent decoration-wavy underline-offset-2"
                  >
                    {run.text}
                  </span>
                ) : (
                  <span key={index}>{run.text}</span>
                ),
              )}
            </div>

            <input
              ref={box}
              data-testid="filter-query"
              role="combobox"
              aria-label="Filter these tickets"
              aria-autocomplete="list"
              aria-expanded={open}
              aria-controls={listId}
              aria-activedescendant={open && current ? `${listId}-${at}` : undefined}
              autoComplete="off"
              spellCheck={false}
              placeholder="status:todo assignee:@me -status:done"
              className="relative w-full border-none bg-transparent px-2 py-1.5 font-mono text-12 leading-5 text-foreground outline-none placeholder:text-faint"
              value={text}
              onChange={(event) => {
                setText(event.target.value);
                setCaret(event.target.selectionStart ?? event.target.value.length);
                // Back to the top on every edit, in the handler and not an effect: the list
                // under the box is a different list now.
                setActive(0);
                setOpen(true);
              }}
              // The caret is read from the element rather than tracked from the keys,
              // because `suggest` answers about the word the caret is *in* and there are
              // more ways to move one than there are keys: a click, a drag, `Home`, an undo.
              onKeyUp={(event) => setCaret(event.currentTarget.selectionStart ?? 0)}
              onClick={(event) => {
                setCaret(event.currentTarget.selectionStart ?? 0);
                setOpen(true);
              }}
              onFocus={(event) => {
                setCaret(event.currentTarget.selectionStart ?? 0);
                setOpen(true);
              }}
              onKeyDown={onKeyDown}
              onScroll={(event) => {
                if (mirror.current) mirror.current.scrollLeft = event.currentTarget.scrollLeft;
              }}
              // Leaving the box asks the question. Anything else would make a reader who
              // typed a filter and clicked a row wonder why nothing happened — and the
              // completion rows refuse `mousedown`, so clicking one is not leaving.
              onBlur={() => {
                setOpen(false);
                commit();
              }}
            />
          </div>

          {open && (
            <FilterCompletions
              items={asking.items}
              listId={listId}
              active={at}
              pending={settling}
              onActive={setActive}
              onAccept={accept}
            />
          )}
        </div>

        {unasked && (
          <span className="shrink-0 text-11 text-faint max-[720px]:hidden">
            <Kbd>↵</Kbd> apply
          </span>
        )}

        {/* Only with a question to save. An empty filter set would make a view holding
            everything in the team, which is the list somebody is already looking at. */}
        {onSave && chips.length > 0 && (
          <button
            type="button"
            data-testid="save-as-view"
            className="shrink-0 rounded-md px-2 py-1 text-12 text-faint hover:text-foreground"
            onClick={onSave}
          >
            Save as view
          </button>
        )}
      </div>

      {complaint && (
        <p role="status" className="m-0 text-11 text-urgent">
          {complaint.message}
        </p>
      )}

      <div className="flex flex-wrap items-center gap-2">
        {chips.map((chip) => (
          <span
            key={chip.key}
            data-testid="filter-chip"
            className="inline-flex items-center gap-2 rounded-md border border-border bg-card px-2.5 py-1 text-12 text-muted-foreground"
          >
            {chip.label} {chip.value && <span className="text-foreground">{chip.value}</span>}
            <button
              type="button"
              aria-label={`Remove ${chip.label} filter`}
              className="text-faint hover:text-foreground"
              // Both truths, each edited in its own vocabulary: the question loses a facet,
              // the line loses a word. `withoutFacet` is the whole of it, and argues in
              // its own docstring against the two shorter ways of writing it.
              onClick={() => {
                const gone = withoutFacet(text, filters, chip.key, names);
                ask(gone.filters, gone.text);
              }}
            >
              ×
            </button>
          </span>
        ))}
        {chips.length === 0 && empty}
      </div>
    </div>
  );
}
