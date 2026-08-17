import { RoadmapBoard } from "@/components/publik/roadmap-board";

/**
 * Screen 27. The heading and the paragraph are the drawing's, and the second sentence
 * of that paragraph is the rule the whole slice obeys: the statuses are the
 * application's own, and voting sorts the queue without committing to a date.
 */
export default function RoadmapPage() {
  return (
    <main className="flex flex-1 flex-col gap-5 px-6 pt-8 pb-10 md:px-10">
      <div className="flex max-w-2xl flex-col gap-2.5">
        <h1 className="text-30 font-medium tracking-[-0.02em]">What we are working on</h1>
        <p className="text-13 text-muted-foreground">
          A read-only view of the tickets marked public. The statuses are the
          application&apos;s own — nothing is reworded for the shop window. Voting helps
          with the ordering; it does not commit to a date.
        </p>
      </div>
      <RoadmapBoard />
    </main>
  );
}
