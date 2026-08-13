/**
 * The logo, everywhere it appears: the sidebar header, the sign-in screen, the setup
 * wizard, and the two screens shown while `/api/me` is in flight.
 *
 * One component rather than four copies of an `<img>`, because what varies between the
 * four is only a width, and what does not vary — which file, what the alt text is, how
 * the dark theme handles it — are decisions that drift the moment they are written down
 * more than once.
 *
 * The mark arrived as greyscale art on an opaque white ground. `public/kanso-logo.png`
 * is the same art with luminance keyed to alpha: white became transparent, black stayed
 * black, and the kanji's 204 grey became black at 20%, which composites back to exactly
 * 204 over white. So it is faithful on light backgrounds, it no longer carries a white
 * tile around with it, and the dark theme becomes one `invert()` — see `globals.css`.
 *
 * `src/app/icon.png` is deliberately the *original*, white ground included: a favicon is
 * drawn at 16px against a browser chrome we do not control, and an opaque tile reads on
 * both light and dark tab strips where transparent black art would vanish into one of them.
 */
export function BrandLogo({ width, alt = "" }: { width: number; alt?: string }) {
  return (
    /*
     * A plain `<img>`, against the lint rule's advice and on purpose. `next/image` would
     * hand the default loader a job it has no tool for: `sharp` is not a dependency here,
     * and `pnpm-workspace.yaml` lists it under `ignoredBuiltDependencies`, so image
     * optimisation would have no processor at runtime in the standalone image. Trading a
     * lint warning for a route that might not serve is the wrong way round, and one 64KB
     * logo is not what that rule exists to catch. Revisit if `sharp` is ever added.
     */
    // eslint-disable-next-line @next/next/no-img-element
    <img
      className="brand-logo"
      src="/kanso-logo.png"
      /*
       * The intrinsic size, with `height: auto` in CSS: the pair is what lets the browser
       * reserve the right box from the markup alone, so the sidebar does not reflow when
       * the bytes land. `width` then scales it without touching the ratio.
       */
      width={737}
      height={704}
      style={{ width }}
      /*
       * Empty by default because the most common caller is `BrandMenu`, where the image
       * sits inside a button that already carries `aria-label="Kanso — account and
       * settings"`. An `aria-label` on a button replaces its contents for assistive
       * technology, so alt text there would be written and never read. Where the logo
       * speaks for itself instead — sign-in, setup, the splash — the caller passes one.
       */
      alt={alt}
    />
  );
}

/**
 * What the interface shows while it is still finding out who you are.
 *
 * Deliberately static: no spinner, no pulse. These screens are meant to be gone inside a
 * second, and something that only begins moving as it disappears draws the eye to the
 * wait rather than covering it. Kanso is judged on how fast it answers a keystroke; an
 * animation here would advertise the one moment it cannot.
 */
export function BrandSplash({ label }: { label: string }) {
  return (
    <div className="centered">
      <BrandLogo width={120} alt="Kanso 簡素" />
      <span style={{ color: "var(--text-dim)" }}>{label}</span>
    </div>
  );
}
