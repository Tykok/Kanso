import { redirect } from "next/navigation";

/**
 * Screen 40's old address, kept as a redirect.
 *
 * It landed on `main` as a page of its own while the navigation rework was open, and the
 * two overlapped by half: it answered "how am I doing" and `/me` answers "what is on my
 * plate", which is one question a reader asks in one place. The ruling was that `/me` is
 * the tabbed home and this screen is its **Progress** tab — so the view moved and the URL
 * stayed, because a link somebody pasted into a document last week is not a thing to break
 * in a refactor.
 *
 * Deliberately outside `app/(app)/`. A redirect draws nothing, so mounting the shell around
 * it would render a sidebar for the length of one round trip and then throw it away.
 *
 * `?tab=progress` and not a bare `/me`: the reader asked for the pace, and landing them on
 * Assigned would make them hunt for what they had already named.
 */
export default function ProgressPage() {
  redirect("/me?tab=progress");
}
