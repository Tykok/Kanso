import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useRequestedSection } from "./use-requested-section";

describe("the settings tab the address asked for", () => {
  it("opens on the requested tab, or the fallback", () => {
    expect(renderHook(() => useRequestedSection("github", "appearance")).result.current[0]).toBe(
      "github",
    );
    expect(renderHook(() => useRequestedSection(null, "appearance")).result.current[0]).toBe(
      "appearance",
    );
  });

  it("follows a new request while already on the page", () => {
    const { result, rerender } = renderHook(
      ({ requested }) => useRequestedSection<string>(requested, "appearance"),
      { initialProps: { requested: null as string | null } },
    );
    act(() => result.current[1]("people"));
    expect(result.current[0]).toBe("people");
    rerender({ requested: "sync-queue" });
    expect(result.current[0]).toBe("sync-queue");
  });
});
