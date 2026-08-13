/**
 * The whole identity: 簡 and 素 stacked in a ruled square, in real type rather
 * than a traced path — so it stays sharp at every size and can be selected.
 * Colour comes from `currentColor`, which is why it follows the accent chosen
 * in settings without knowing that accents exist.
 */
export function Seal({ size, title }: { size: number; title?: string }) {
  return (
    <span
      className="seal"
      style={{ width: size, height: size }}
      data-size={size > 34 ? "lg" : undefined}
      role={title ? "img" : undefined}
      aria-label={title}
      aria-hidden={title ? undefined : true}
    />
  );
}
