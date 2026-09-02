import { SavedViewScreen } from "@/components/organise/saved-view";

/** Screen 21 — one saved view, its chips, and the bulk strip. */
export default async function ViewPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <SavedViewScreen id={id} />;
}
