import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useIdempotencyKey } from "@/lib/use-idempotency-key";

describe("useIdempotencyKey", () => {
  it("returns a stable key across re-renders with an unchanged payload", () => {
    const payload = { name: "Groceries", transactionType: "EXPENSE" };
    const { result, rerender } = renderHook(({ p }) => useIdempotencyKey(p), {
      initialProps: { p: payload },
    });

    const firstKey = result.current.key;
    expect(firstKey).toMatch(/^[0-9a-f-]{36}$/i);

    rerender({ p: { ...payload } });

    expect(result.current.key).toBe(firstKey);
  });

  it("rotates the key when the payload changes", () => {
    const { result, rerender } = renderHook(({ p }) => useIdempotencyKey(p), {
      initialProps: { p: { name: "Groceries" } },
    });

    const firstKey = result.current.key;
    rerender({ p: { name: "Rent" } });

    expect(result.current.key).not.toBe(firstKey);
  });

  it("treats payloads with reordered keys as equivalent (stable serialization)", () => {
    const { result, rerender } = renderHook(({ p }) => useIdempotencyKey(p), {
      initialProps: { p: { a: 1, b: 2 } },
    });

    const firstKey = result.current.key;
    rerender({ p: { b: 2, a: 1 } });

    expect(result.current.key).toBe(firstKey);
  });

  it("rotates the key after clear() even with an identical payload", () => {
    const payload = { name: "Groceries" };
    const { result, rerender } = renderHook(({ p }) => useIdempotencyKey(p), {
      initialProps: { p: payload },
    });

    const firstKey = result.current.key;
    result.current.clear();
    rerender({ p: payload });

    expect(result.current.key).not.toBe(firstKey);
  });

  it("resolve() mirrors the same rotate-on-change behavior for imperative call sites", () => {
    const { result } = renderHook(() => useIdempotencyKey(null));

    const first = result.current.resolve({ amount: "10" });
    const second = result.current.resolve({ amount: "10" });
    const third = result.current.resolve({ amount: "20" });

    expect(second).toBe(first);
    expect(third).not.toBe(first);
  });

  it("never shares state between two independent hook instances", () => {
    const a = renderHook(({ p }) => useIdempotencyKey(p), { initialProps: { p: { x: 1 } } });
    const b = renderHook(({ p }) => useIdempotencyKey(p), { initialProps: { p: { x: 1 } } });

    expect(a.result.current.key).not.toBe(b.result.current.key);

    a.result.current.clear();
    a.rerender({ p: { x: 1 } });

    // b's key is untouched by a's clear()
    const bKeyBefore = b.result.current.key;
    b.rerender({ p: { x: 1 } });
    expect(b.result.current.key).toBe(bKeyBefore);
  });
});
