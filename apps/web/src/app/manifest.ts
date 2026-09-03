import type { MetadataRoute } from "next";

/**
 * What an installed Kanso is (KAN-24).
 *
 * A generated manifest rather than a static `manifest.json` so the copy has one home:
 * `name` and `description` are the same two strings `layout.tsx`'s metadata already
 * carries, and two files describing the same product is how they end up disagreeing.
 *
 * `display: "standalone"` and nothing else. The shell this installs is the keyboard-first
 * single column `app/(app)/layout.tsx` draws, which already fills `h-screen` and already
 * handles the under-720px width — so removing the browser's chrome hands the whole
 * viewport to a layout built for it, rather than needing a second one.
 *
 * There are deliberately **no `shortcuts`**. An OS jump list is a second navigation idea:
 * it would put four destinations in a long-press menu that the sidebar and the command
 * palette already own, and the day one is renamed there are two lists to remember. The
 * nav rework's whole point was one column and one selection; this respects it by adding
 * nothing.
 *
 * `background_color` is the splash the OS paints before the first frame, and it is white
 * rather than the light `--background` on purpose: it matches the icons' own ground, so
 * the splash and the icon on it are one shape instead of two off-white rectangles. The
 * *theme* colour is the one that has to follow the reader's mode, and a manifest holds
 * only one value — so that lives in `layout.tsx`'s `viewport.themeColor`, which can carry
 * a `prefers-color-scheme` pair.
 */
export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "Kanso",
    short_name: "Kanso",
    description:
      "Kanso runs a project end to end — projects, tickets, cycles, a roadmap, a timeline, workload — and everyone else reads the same data in Notion.",
    // The list, which is where the keyboard starts. `/` also restores the last scope from
    // its own `?team=` mirror, so an installed window reopens on the room it was left in.
    start_url: "/",
    display: "standalone",
    background_color: "#ffffff",
    theme_color: "#ffffff",
    icons: [
      { src: "/icons/icon-192.png", sizes: "192x192", type: "image/png", purpose: "any" },
      { src: "/icons/icon-512.png", sizes: "512x512", type: "image/png", purpose: "any" },
      /*
       * Its own file rather than `purpose: "any maskable"` on the two above. A launcher
       * that masks an icon crops to a circle inscribed in the middle 80%, so declaring one
       * image as both means either the plain icon is padded for a crop that will not happen
       * or the masked one loses its brush border to it. This one is drawn small inside the
       * safe zone; those two are not.
       */
      {
        src: "/icons/icon-maskable-512.png",
        sizes: "512x512",
        type: "image/png",
        purpose: "maskable",
      },
    ],
  };
}
