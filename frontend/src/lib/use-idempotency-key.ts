import { useRef } from "react";

export interface UseIdempotencyKeyResult {
  /** Key for `payload` as of this render. Stable while the payload is unchanged. */
  key: string;
  /** Forces a fresh key on the next call (render-time `key` or an imperative `resolve`), even for an identical payload. */
  clear: () => void;
  /**
   * Imperative variant of the same rotate-on-change logic, for call sites where the payload
   * is only known at submit time (e.g. react-hook-form values passed to a submit handler)
   * rather than available as a stable render-time value. Safe to call from event handlers.
   */
  resolve: (payload: unknown) => string;
}

/**
 * Generates a stable idempotency key for a mutation payload: the same key is returned
 * across re-renders (or `resolve()` calls) as long as the payload is unchanged (stable
 * JSON.stringify compare), which lets a retry of the same logical request reuse the same
 * key. Any change to the payload rotates to a fresh key, since it represents a materially
 * different request.
 */
export function useIdempotencyKey(payload: unknown): UseIdempotencyKeyResult {
  const keyRef = useRef<string | null>(null);
  const lastPayloadRef = useRef<string | null>(null);

  const resolve = (p: unknown): string => {
    const serialized = stableStringify(p);
    if (keyRef.current === null || lastPayloadRef.current !== serialized) {
      keyRef.current = crypto.randomUUID();
      lastPayloadRef.current = serialized;
    }
    return keyRef.current;
  };

  const clear = () => {
    keyRef.current = null;
    lastPayloadRef.current = null;
  };

  return { key: resolve(payload), clear, resolve };
}

/** JSON.stringify with sorted object keys, so key order differences don't spuriously rotate the key. */
function stableStringify(value: unknown): string {
  return JSON.stringify(value, (_key, val) => {
    if (val && typeof val === "object" && !Array.isArray(val)) {
      return Object.keys(val)
        .sort()
        .reduce((acc: Record<string, unknown>, k) => {
          acc[k] = (val as Record<string, unknown>)[k];
          return acc;
        }, {});
    }
    return val;
  });
}
