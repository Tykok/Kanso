import { afterEach, describe, expect, it, vi } from "vitest";
import { safeNext } from "@/lib/next-url";

/**
 * `NEXT_PUBLIC_API_URL` is captured the first time `core.ts` is evaluated — Next inlines
 * it into the bundle at build time, and vitest reads it from the environment at import
 * time — so a case that changes it has to reload the module rather than assign a variable.
 */
async function load(apiUrl: string | undefined) {
  vi.resetModules();
  vi.stubEnv("NEXT_PUBLIC_API_URL", apiUrl);
  return import("./core");
}

afterEach(() => {
  vi.unstubAllEnvs();
  vi.unstubAllGlobals();
});

describe("apiOrigin", () => {
  it("is the configured URL when there is one", async () => {
    // Development keeps the two halves on two ports, and `.env.development` says so.
    const { apiOrigin } = await load("http://localhost:8080");
    expect(apiOrigin()).toBe("http://localhost:8080");
  });

  it("is empty where there is no window, because that is what a prerender has", async () => {
    // `next build` prerenders every client component, so this branch is not a fallback:
    // it is the pass that runs at build time, and it is the whole reason `apiOrigin` is a
    // function rather than the constant four of its call sites used to be.
    const { apiOrigin } = await load(undefined);
    expect(typeof window).toBe("undefined");
    expect(apiOrigin()).toBe("");
  });

  it("is the page's own origin in a browser, which is what one origin means", async () => {
    // Nothing is inlined into a published image, so the origin is not known until a
    // browser has one — and then it is the address the reader typed.
    vi.stubGlobal("window", { location: { origin: "https://kanso.example.com" } });
    const { apiOrigin } = await load(undefined);
    expect(apiOrigin()).toBe("https://kanso.example.com");
  });
});

describe("the consent round trip an agent's authorisation depends on", () => {
  it("keeps the absolute `next` the API sent, once the origin is resolved", async () => {
    // `ConsentController` sends an anonymous member to `/login?next=<absolute consent
    // URL>` and `login/page.tsx` reflects that value. Handed `API_URL`, which a published
    // image inlines as the empty string, `safeNext` parses `""`, throws, catches, and
    // returns HOME — fail-closed, so not a hole, but authorising an agent would appear to
    // do nothing at all. Both halves are asserted here because the wrong one is silent.
    vi.stubGlobal("window", { location: { origin: "https://kanso.example.com" } });
    const { API_URL, apiOrigin } = await load(undefined);
    const consent = "https://kanso.example.com/oauth/consent?client_id=claude-code";

    expect(safeNext(consent, API_URL)).toBe("/");
    expect(safeNext(consent, apiOrigin())).toBe(consent);
  });
});
