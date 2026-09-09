import { renderHook } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";
import { API_URL } from "./api";
import { useApiOrigin } from "./use-api-origin";

function Origin() {
  return <span>{useApiOrigin()}</span>;
}

describe("useApiOrigin", () => {
  it("draws the build-time value on the server, which is the markup a browser hydrates", () => {
    // The pass `next build` runs, and the reason this is a hook rather than a call: a
    // component that read the origin here would render one string on the server and
    // another in the browser, which React reports as a hydration mismatch. It would also
    // not build at all, since a prerender has no `window` to read.
    expect(renderToStaticMarkup(<Origin />)).toBe(`<span>${API_URL}</span>`);
  });

  it("draws the page's own origin in a browser, which is the one that can be pasted", () => {
    // A redirect URI or an `mcp add` command left relative is a string somebody copies
    // into Google or a shell and cannot debug from the error it produces.
    const { result } = renderHook(() => useApiOrigin());
    expect(result.current).toBe(window.location.origin);
  });
});
