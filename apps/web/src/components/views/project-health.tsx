"use client";

import { useState } from "react";
import { PROJECT_HEALTHS, type Project, type ProjectHealth, type ProjectUpdate } from "@/lib/api";
import { PROJECT_HEALTH_COLORS, PROJECT_HEALTH_LABELS } from "@/lib/status";
import { usePostProjectUpdate, useProjectUpdates } from "@/lib/queries";
import { Button } from "../ui/button";
import { GroupLabel } from "../ui/group-label";
import { Avatar } from "./avatar";
import { activityTime } from "./activity-copy";
import { healthLabel } from "./project-copy";

/**
 * How a project is going, beside where its work is.
 *
 * A file of its own rather than another 120 lines inside `project-page.tsx`, which is
 * already a page. The page keeps two calls into this: the pill in its header line, and the
 * panel down its right-hand column above the feed.
 */

/**
 * The health, as a dot and a word.
 *
 * Never the dot alone. The colour is what makes an off-track project findable in a column
 * of twenty; the word is what makes it *readable*, and `ui/status-dot.tsx` sets out at
 * length why every hue in this app is drawn beside text that says the same thing.
 *
 * `undefined` is drawn, and drawn as an absence: a hollow ring in the muted colour with
 * "No update yet" beside it. Not hidden, because the missing update is the point — a
 * project nobody has assessed is a thing somebody should notice — and not green, which is
 * the mistake `healthLabel` exists to prevent.
 */
export function HealthPill({ health }: { health: ProjectHealth | undefined }) {
  return (
    <span
      data-testid="health-pill"
      data-health={health ?? "none"}
      className="inline-flex items-center gap-[7px] text-12"
    >
      <span
        aria-hidden
        className="size-2 shrink-0 rounded-full border-[1.5px] border-current"
        style={{
          color: health ? PROJECT_HEALTH_COLORS[health] : "var(--muted-foreground)",
          // Filled when somebody has said something, hollow when nobody has: the same
          // "absent states recede" grammar the six status dots already use.
          background: health ? "currentColor" : undefined,
        }}
      />
      <span className={health ? "text-foreground" : "text-faint"}>{healthLabel(health)}</span>
    </span>
  );
}

/**
 * The panel: what the latest update said, the ones before it, and the box to write the
 * next one.
 *
 * The current health is *not* drawn again here. It is one row up, in the same list as
 * Lead and Team, read off [Project.health] which is always loaded; the newest row of this
 * history is the same fact a second time. Two pills saying "At risk" a centimetre apart
 * read as two assessments rather than one, which is the opposite of what a health signal
 * needs. So the field states the health and this panel states its reasons.
 */
export function ProjectHealthPanel({ project }: { project: Project }) {
  const updates = useProjectUpdates(project.id);
  const [writing, setWriting] = useState(false);

  const rows = updates.data ?? [];

  return (
    <div className="flex flex-col gap-2">
      <div className="flex items-baseline gap-2.5">
        <GroupLabel className="px-0 pt-0 pb-0">Health</GroupLabel>
        <span className="flex-1" />
        {!writing && (
          <Button
            type="button"
            variant="ghost"
            size="xs"
            onClick={() => setWriting(true)}
            data-testid="post-update"
          >
            Post an update
          </Button>
        )}
      </div>

      {writing && <UpdateForm projectId={project.id} onDone={() => setWriting(false)} />}

      {rows.length === 0 ? (
        // Said as a fact about this project rather than as an empty state, and with the
        // reason it matters: the whole feature is the difference between "going fine" and
        // "nobody has looked". Dropped while the box is open — it is an invitation to
        // write, and printing it under somebody already writing is nagging.
        !writing && (
          <p className="m-0 text-11 text-faint">
            Nobody has said how this is going. Status says where the work is; an update
            says whether it will land.
          </p>
        )
      ) : (
        <ul data-testid="project-updates" className="m-0 flex list-none flex-col gap-3 p-0">
          {rows.map((update) => (
            <UpdateRow key={update.id} update={update} />
          ))}
        </ul>
      )}
    </div>
  );
}

/** One dated statement. Never editable: the correction for a wrong one is the next one. */
function UpdateRow({ update }: { update: ProjectUpdate }) {
  return (
    <li className="flex flex-col gap-1 text-12">
      <div className="flex items-center gap-2">
        <HealthPill health={update.health} />
        <span className="flex-1" />
        <time className="font-mono text-11 text-faint" dateTime={update.at}>
          {activityTime(update.at, new Date(), Intl.DateTimeFormat().resolvedOptions().timeZone)}
        </time>
      </div>
      <p className="m-0 text-pretty text-muted-foreground">{update.body}</p>
      <span className="flex items-center gap-1.5 text-11 text-faint">
        {update.author ? (
          <>
            <Avatar displayName={update.author.displayName} size={18} />
            {update.author.displayName}
          </>
        ) : (
          // `author_id` is ON DELETE SET NULL: losing the account must not lose what was
          // known about the project in August.
          "Someone who has since left"
        )}
      </span>
    </li>
  );
}

/**
 * Writing one. Both halves are required, and the server refuses a blank body for the
 * reason the placeholder gives: a colour with no sentence under it is not actionable.
 *
 * There is no default health selected on purpose — `""` until the writer chooses. A form
 * that opens on "On track" is a form that posts "On track" whenever somebody starts typing
 * and does not look up, which is the same optimistic default the whole feature refuses at
 * the schema level.
 */
function UpdateForm({ projectId, onDone }: { projectId: string; onDone: () => void }) {
  const post = usePostProjectUpdate(projectId);
  const [health, setHealth] = useState<ProjectHealth | "">("");
  const [body, setBody] = useState("");

  const ready = health !== "" && body.trim() !== "";

  return (
    <form
      className="flex flex-col gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (!ready || post.isPending) return;
        post.mutate(
          { health: health as ProjectHealth, body: body.trim() },
          {
            onSuccess: () => {
              setBody("");
              setHealth("");
              onDone();
            },
          },
        );
      }}
    >
      <label className="flex flex-col gap-1">
        <span className="sr-only">Health</span>
        <select
          className="text-12"
          value={health}
          autoFocus
          onChange={(event) => setHealth(event.target.value as ProjectHealth | "")}
          // The keyboard shortcuts live on `window`; a `j` typed in here must stay here.
          onKeyDown={(event) => event.stopPropagation()}
        >
          <option value="">How is it going?</option>
          {PROJECT_HEALTHS.map((value) => (
            <option key={value} value={value}>
              {PROJECT_HEALTH_LABELS[value]}
            </option>
          ))}
        </select>
      </label>

      <label className="flex flex-col gap-1">
        <span className="sr-only">Update</span>
        <textarea
          className="min-h-16 w-full resize-none text-12 field-sizing-content"
          placeholder="Why — the reason is the update."
          value={body}
          onChange={(event) => setBody(event.target.value)}
          onKeyDown={(event) => event.stopPropagation()}
        />
      </label>

      {post.isError && (
        <span role="alert" className="text-11 text-urgent">
          The update was not saved.
        </span>
      )}

      <div className="flex items-center gap-2">
        <Button type="submit" size="xs" disabled={!ready || post.isPending}>
          Post
        </Button>
        <Button type="button" variant="ghost" size="xs" onClick={onDone} disabled={post.isPending}>
          Cancel
        </Button>
      </div>
    </form>
  );
}
