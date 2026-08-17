import Link from "next/link";
import type { ReactNode } from "react";
import { Button } from "@/components/ui/button";
import { Kbd } from "@/components/ui/kbd";
import { Seal } from "@/components/ui/seal";
import { LICENCE, OPEN_SOURCE, REPO_URL } from "@/components/publik/copy";
import { STATUS_COLORS, STATUS_LABELS } from "@/lib/status";

/**
 * The landing page, following `Kanso - Vitrine` section by section: hero and a miniature
 * of the list, the four figures, three views, the dark band about documents, three
 * columns on the mirror, open source, and the closing line.
 *
 * The one screen in the bundle whose job is persuasion, and the only one with no data
 * behind it — every number on it is a claim about the design that the design system
 * itself can be checked against ("6 statuses" is `TicketStatus`; "44px" is
 * `--touch-min`; "0 borders between two rows" is the list's own reset). The figures are
 * read off the tokens where they can be, so a page that boasts cannot boast wrongly.
 */
export default function AboutPage() {
  return (
    <main className="flex flex-1 flex-col">
      <Hero />
      <Figures />
      <Views />
      <Documents />
      <ThreeThings />
      <OpenSource />
      <Closing />
    </main>
  );
}

function Hero() {
  return (
    <header className="grid border-b border-rule lg:grid-cols-2">
      <div className="flex flex-col gap-6 border-rule px-6 py-16 md:px-10 lg:border-r">
        <span className="font-mono text-11 uppercase tracking-[0.12em] text-faint">
          Work tracking · living document
        </span>
        <h1 className="max-w-lg text-30 font-medium leading-tight tracking-[-0.028em] md:text-[52px]">
          The board and the page,
          <br />
          without the two tools.
        </h1>
        <p className="max-w-md text-13 text-muted-foreground md:text-15">
          Kanso keeps a team&apos;s tickets and the documents that explain them in one
          model. Statuses come from the actual work; structure comes from space, not from
          borders.
        </p>
        <div className="flex flex-wrap items-center gap-3">
          <Button size="lg" asChild>
            <Link href="/">Open the app</Link>
          </Button>
          <Button size="lg" variant="outline" asChild>
            <Link href="/roadmap">See the roadmap</Link>
          </Button>
          <span className="text-12 text-faint">
            or <Kbd>⌘K</Kbd> anywhere inside it
          </span>
        </div>
        {/* The six statuses as six bars, in the app's own colours: the palette is the
            claim, so it is drawn from the tokens rather than restated in hex. */}
        <div className="mt-2 flex gap-2" aria-hidden>
          {(["backlog", "todo", "in_progress", "in_review", "done", "canceled"] as const).map(
            (status) => (
              <span
                key={status}
                className="h-1 w-9"
                style={{ background: STATUS_COLORS[status] }}
              />
            ),
          )}
        </div>
      </div>
      <div className="flex items-center bg-secondary px-6 py-14 md:px-10">
        <ListMiniature />
      </div>
    </header>
  );
}

/**
 * A drawing of the list, not the list.
 *
 * `components/tickets.tsx` is the real one and is read-only to this slice — and it
 * should be: the real list needs a session, a scope and a query, none of which a
 * stranger has. So this is a still picture with the same tokens, which is what the
 * bundle drew too.
 */
function ListMiniature() {
  const rows = [
    { key: "KAN-142", title: "Echo suppression drops our own writes", who: "MR", current: true },
    { key: "KAN-139", title: "Timeline: route the arrows around the bars", who: "AO" },
    { key: "KAN-137", title: "Compact density: keep 44px on touch", who: "JS" },
  ];

  return (
    <div className="w-full overflow-hidden rounded-lg bg-card shadow-panel">
      <div className="flex items-center gap-2.5 bg-background px-4 py-2.5 text-11 text-faint">
        <span className="text-primary">
          <Seal size={12} />
        </span>
        <span className="text-muted-foreground">Core</span>
        <span>/</span>
        <span>Tickets</span>
        <span className="flex-1" />
        <span className="font-mono">14</span>
      </div>
      <div className="flex flex-col gap-row px-2.5 py-3.5">
        <GroupHeading status="in_progress" count={3} />
        {rows.map((row) => (
          <div
            key={row.key}
            className="grid h-row items-center gap-3 rounded-md px-3 text-13 text-muted-foreground"
            style={{
              gridTemplateColumns: "66px 1fr 22px",
              ...(row.current
                ? {
                    background: "var(--accent-soft)",
                    boxShadow: "inset 2px 0 0 var(--primary)",
                    color: "var(--foreground)",
                  }
                : {}),
            }}
          >
            <span className="font-mono text-11 text-faint">{row.key}</span>
            <span className="truncate">{row.title}</span>
            <span className="grid size-5 place-items-center rounded-full bg-background text-[9px] text-faint">
              {row.who}
            </span>
          </div>
        ))}
        <GroupHeading status="todo" count={4} />
        <div
          className="grid h-row items-center gap-3 px-3 text-13 text-muted-foreground"
          style={{ gridTemplateColumns: "66px 1fr 22px" }}
        >
          <span className="font-mono text-11 text-faint">KAN-136</span>
          <span className="truncate">Inbox: group the sync failures</span>
          <span className="size-5 rounded-full border border-dashed border-border" />
        </div>
      </div>
    </div>
  );
}

function GroupHeading({ status, count }: { status: "in_progress" | "todo"; count: number }) {
  return (
    <div className="px-3 pt-2 pb-1.5 text-11 uppercase tracking-[0.1em] text-faint">
      {STATUS_LABELS[status]} · {count}
    </div>
  );
}

/** The four figures. Each is checkable against the code, which is why they are here. */
function Figures() {
  const figures = [
    { number: "6", label: "statuses, not eighteen" },
    { number: "1", label: "model for tickets and documents" },
    { number: "0", label: "borders between two rows" },
    { number: "44 px", label: "minimum target on touch" },
  ];

  return (
    <section className="grid border-b border-rule sm:grid-cols-2 lg:grid-cols-4">
      {figures.map((figure) => (
        <div
          key={figure.label}
          className="flex flex-col gap-0.5 border-rule px-6 py-6 md:px-10 lg:border-r lg:last:border-r-0"
        >
          <span className="text-21 font-medium tracking-[-0.02em]">{figure.number}</span>
          <span className="text-12 text-muted-foreground">{figure.label}</span>
        </div>
      ))}
    </section>
  );
}

function Views() {
  const views = [
    {
      name: "List",
      text: "Grouped by status. The title carries the weight; everything else recedes.",
    },
    {
      name: "Board",
      text: "The same six statuses in columns. Dragging changes the status and nothing else.",
    },
    {
      name: "Plan",
      text: "Bars and dependencies. The critical path is emphasised, slack is hatched.",
    },
  ];

  return (
    <section id="product" className="flex flex-col gap-8 border-b border-rule px-6 py-16 md:px-10">
      <div className="flex max-w-xl flex-col gap-2.5">
        <span className="font-mono text-11 uppercase tracking-[0.12em] text-faint">
          Three views, one set of tickets
        </span>
        <h2 className="text-21 font-medium tracking-[-0.02em] md:text-30">
          Change the view, not the software
        </h2>
      </div>
      <div className="grid gap-8 border-t border-rule pt-7 lg:grid-cols-3 lg:gap-0">
        {views.map((view, index) => (
          <div
            key={view.name}
            className={[
              "flex flex-col gap-1.5",
              index < views.length - 1 ? "lg:border-r lg:border-rule lg:pr-7" : "",
              index > 0 ? "lg:pl-7" : "",
            ].join(" ")}
          >
            <span className="text-15 font-medium">{view.name}</span>
            <p className="text-13 text-muted-foreground">{view.text}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * The dark band. It states its own colours rather than reading `.dark`'s tokens: this
 * section is dark in both schemes on purpose, so it must not follow the reader's theme.
 */
function Documents() {
  return (
    <section
      id="structure"
      className="grid gap-10 px-6 py-16 md:px-10 lg:grid-cols-2 lg:items-center"
      style={{ background: "oklch(0.185 0.008 262)", color: "oklch(0.935 0.006 262)" }}
    >
      <div className="flex flex-col gap-5">
        <span className="flex items-center gap-3">
          <span style={{ color: "oklch(0.70 0.13 262)" }}>
            <Seal size={26} />
          </span>
          <span
            className="font-mono text-11 uppercase tracking-[0.12em]"
            style={{ color: "oklch(0.565 0.011 262)" }}
          >
            The document
          </span>
        </span>
        <h2 className="max-w-lg text-21 font-medium leading-tight tracking-[-0.022em] md:text-30">
          A ticket quoted in a page is still the same ticket.
        </h2>
        <p className="max-w-md text-13 md:text-15" style={{ color: "oklch(0.715 0.011 262)" }}>
          Blocks accept tickets like any other content. The status shown in the page
          follows the one in the list, and a change made here is the same change there —
          not a copy that ages.
        </p>
        <div className="flex flex-wrap gap-4 text-13" style={{ color: "oklch(0.715 0.011 262)" }}>
          <span>Blocks, mentions, queries</span>
          <span aria-hidden>·</span>
          <span>Two-way Notion mirror</span>
        </div>
      </div>
      <div
        className="flex flex-col gap-3.5 rounded-panel px-7 py-6"
        style={{ background: "oklch(0.228 0.010 262)" }}
      >
        <span className="font-mono text-11" style={{ color: "oklch(0.565 0.011 262)" }}>
          DOC-08 · The sync contract
        </span>
        <h3 className="text-15 font-medium md:text-21">What the mirror guarantees</h3>
        <p className="text-13 leading-relaxed" style={{ color: "oklch(0.715 0.011 262)" }}>
          The order of writes is preserved per actor. A rejected write stays in the queue
          and is never silently lost.
        </p>
      </div>
    </section>
  );
}

function ThreeThings() {
  const columns = [
    {
      eyebrow: "Offline",
      title: "Write with no network",
      body: (
        <>
          Changes enter a visible, ordered queue and are sent when the network returns. A
          conflict offers both versions instead of choosing for you.
        </>
      ),
    },
    {
      eyebrow: "Keyboard",
      title: "Everything without the mouse",
      body: (
        <>
          Create with <Kbd>c</Kbd>, open as a page with <Kbd>⇧↵</Kbd>, status with{" "}
          <Kbd>1</Kbd>–<Kbd>6</Kbd>. The same keys in the list, the board and the plan.
        </>
      ),
    },
    {
      eyebrow: "Density",
      title: "Two densities, one body size",
      body: (
        <>
          Compact removes space, never legibility: 27px per row instead of 36, with the
          same text. On touch, targets stay at 44px.
        </>
      ),
    },
  ];

  return (
    <section id="mirror" className="grid gap-8 border-b border-rule px-6 py-16 md:px-10 lg:grid-cols-3 lg:gap-0">
      {columns.map((column, index) => (
        <Column key={column.eyebrow} first={index === 0} last={index === columns.length - 1}>
          <span className="font-mono text-11 uppercase tracking-[0.12em] text-faint">
            {column.eyebrow}
          </span>
          <h3 className="text-15 font-medium tracking-[-0.012em]">{column.title}</h3>
          <p className="text-13 text-muted-foreground">{column.body}</p>
        </Column>
      ))}
    </section>
  );
}

function Column({
  children,
  first,
  last,
}: {
  children: ReactNode;
  first: boolean;
  last: boolean;
}) {
  return (
    <div
      className={[
        "flex flex-col gap-3",
        last ? "" : "lg:border-r lg:border-rule lg:pr-9",
        first ? "" : "lg:pl-9",
      ].join(" ")}
    >
      {children}
    </div>
  );
}

function OpenSource() {
  const { install, contribute, governance } = OPEN_SOURCE;

  return (
    <section id="contribute" className="flex flex-col gap-8 border-b border-rule px-6 py-16 md:px-10">
      <div className="flex flex-wrap items-end gap-5">
        <h2 className="text-21 font-medium tracking-[-0.02em] md:text-30">
          Free, and hostable by you
        </h2>
        <span className="pb-1 text-13 text-muted-foreground">
          {LICENCE}. No paid edition, no feature kept behind a wall.
        </span>
      </div>
      <div className="grid gap-8 border-t border-rule pt-7 lg:grid-cols-3 lg:gap-0">
        <Column first last={false}>
          <span className="text-13 font-medium">{install.title}</span>
          <code className="border bg-card px-3 py-2 font-mono text-13 text-muted-foreground">
            {install.command}
          </code>
          <Points points={install.points} />
        </Column>
        <div className="flex flex-col gap-3.5 bg-accent-soft p-7 lg:border-r lg:border-rule">
          <span className="text-13 font-medium">{contribute.title}</span>
          <Points points={contribute.points} />
          <Button className="mt-2 self-start" asChild>
            <Link href="/roadmap">See the roadmap</Link>
          </Button>
        </div>
        <Column first={false} last>
          <span className="text-13 font-medium">{governance.title}</span>
          <Points points={governance.points} />
          <Button variant="outline" className="mt-2 self-start" asChild>
            <a href={REPO_URL} target="_blank" rel="noreferrer">
              Read the source
            </a>
          </Button>
        </Column>
      </div>
    </section>
  );
}

function Points({ points }: { points: readonly string[] }) {
  return (
    <ul className="flex flex-col gap-1.5 text-13 text-muted-foreground">
      {points.map((point) => (
        <li key={point}>{point}</li>
      ))}
    </ul>
  );
}

function Closing() {
  return (
    <section className="flex flex-col items-start gap-8 border-b border-rule px-6 py-20 md:px-10 lg:flex-row lg:items-end lg:gap-12">
      <div className="flex flex-1 flex-col gap-4">
        <span className="text-primary">
          <Seal size={34} title="Kanso" />
        </span>
        <h2 className="max-w-2xl text-21 font-medium leading-tight tracking-[-0.026em] md:text-30">
          簡素 — the simple and the plain. A tool that does not ask to be learned.
        </h2>
      </div>
      <div className="flex flex-col items-start gap-3">
        <Button size="lg" asChild>
          <a href={REPO_URL} target="_blank" rel="noreferrer">
            Clone the repository
          </a>
        </Button>
        <span className="text-12 text-faint">{LICENCE}.</span>
      </div>
    </section>
  );
}
