/**
 * What a route renders before its branch lands.
 *
 * Fourteen routes are committed empty so that no two branches create the same directory,
 * and a stray link during the fan-out has to land somewhere legible — a 404 says the app
 * is broken, this says the screen is not built. The branch that builds one deletes its
 * use of this component; when the last one has, the file goes with it.
 */
export function RouteStub({ screen, slice }: { screen: string; slice: string }) {
  return (
    <main className="empty flex-col gap-1">
      <span className="text-13 text-foreground">{screen}</span>
      <span className="text-12 text-faint">Slice {slice} — not built yet.</span>
    </main>
  );
}
