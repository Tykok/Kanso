import { act, renderHook } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useRequestedSection } from "./use-requested-section";

const address = vi.hoisted(() => ({ search: "" }));
const replace = vi.hoisted(() => vi.fn());

vi.mock("next/navigation", () => ({
  useSearchParams: () => new URLSearchParams(address.search),
  usePathname: () => "/settings",
  useRouter: () => ({ replace }),
}));

const NAMES = { appearance: "Appearance", people: "People", "sync-queue": "Sync queue" };

describe("the settings tab the address names", () => {
  beforeEach(() => {
    address.search = "";
    replace.mockReset();
  });

  it("opens on the requested tab, or the fallback for none or an unknown one", () => {
    address.search = "section=people";
    expect(renderHook(() => useRequestedSection(NAMES, "appearance")).result.current[0]).toBe(
      "people",
    );
    address.search = "section=nonsense";
    expect(renderHook(() => useRequestedSection(NAMES, "appearance")).result.current[0]).toBe(
      "appearance",
    );
  });

  it("writes a pressed tab to the address, so the same link followed again still lands", () => {
    address.search = "section=sync-queue";
    const { result } = renderHook(() => useRequestedSection(NAMES, "appearance"));
    act(() => result.current[1]("people"));
    expect(replace).toHaveBeenCalledWith("/settings?section=people", { scroll: false });
  });
});
