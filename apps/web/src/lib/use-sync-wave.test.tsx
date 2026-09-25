import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useSyncWave } from "./use-sync-wave";

const wave = () => renderHook(({ remaining }) => useSyncWave(remaining), {
  initialProps: { remaining: 0 },
});

describe("how far along the queue is", () => {
  it("draws nothing while the queue is empty", () => {
    expect(wave().result.current).toBeNull();
  });

  it("starts at zero and fills as the wave drains", () => {
    const { result, rerender } = wave();
    rerender({ remaining: 50 });
    expect(result.current).toBe(0);
    rerender({ remaining: 25 });
    expect(result.current).toBe(0.5);
  });

  it("goes away when the queue empties, and the next wave starts over", () => {
    const { result, rerender } = wave();
    rerender({ remaining: 50 });
    rerender({ remaining: 0 });
    expect(result.current).toBeNull();
    rerender({ remaining: 4 });
    expect(result.current).toBe(0);
  });

  it("steps back rather than lying when the queue grows mid-wave", () => {
    const { result, rerender } = wave();
    rerender({ remaining: 10 });
    rerender({ remaining: 5 });
    expect(result.current).toBe(0.5);
    rerender({ remaining: 20 });
    expect(result.current).toBe(0);
    rerender({ remaining: 10 });
    expect(result.current).toBe(0.5);
  });
});
