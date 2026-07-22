import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { NextIntlClientProvider } from "next-intl";
import { describe, expect, it, vi } from "vitest";
import type { ReactNode } from "react";
import { useCategoryLabel } from "@/lib/category-label";
import { categoryService, type Category } from "@/services/category-service";
import enMessages from "../../messages/en.json";

vi.mock("@/services/category-service", () => ({
  categoryService: { list: vi.fn() },
}));

const mockedList = vi.mocked(categoryService.list);

function wrapper({ children }: { children: ReactNode }) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return (
    <QueryClientProvider client={queryClient}>
      <NextIntlClientProvider locale="en" messages={enMessages}>
        {children}
      </NextIntlClientProvider>
    </QueryClientProvider>
  );
}

const categories: Category[] = [
  { id: 1, name: "Food & Dining", transactionType: "EXPENSE", system: true },
  { id: 2, name: "Travel", transactionType: "EXPENSE", system: false },
];

describe("useCategoryLabel", () => {
  it("translates a system category name given the flag directly", () => {
    mockedList.mockResolvedValue(categories);
    const { result } = renderHook(() => useCategoryLabel(), { wrapper });

    expect(result.current({ name: "Food & Dining", system: true })).toBe("Food & Dining");
  });

  it("leaves a user category name verbatim given the flag directly", () => {
    mockedList.mockResolvedValue(categories);
    const { result } = renderHook(() => useCategoryLabel(), { wrapper });

    expect(result.current({ name: "Travel", system: false })).toBe("Travel");
  });

  it("leaves a user category verbatim even when its name collides with a seeded system name", () => {
    mockedList.mockResolvedValue(categories);
    const { result } = renderHook(() => useCategoryLabel(), { wrapper });

    // A user-created category named "Food & Dining" (system: false) must never be
    // translated just because the text matches a seeded name — the flag is the only gate.
    expect(result.current({ name: "Food & Dining", system: false })).toBe("Food & Dining");
  });

  it("resolves the system flag via categoryId against the cached category list when the flag is absent", async () => {
    mockedList.mockResolvedValue(categories);
    const { result } = renderHook(() => useCategoryLabel(), { wrapper });

    await waitFor(() => expect(mockedList).toHaveBeenCalled());
    await waitFor(() => expect(result.current({ name: "Food & Dining", categoryId: 1 })).toBe("Food & Dining"));
  });

  it("falls back to the raw name when the categoryId can't be resolved", async () => {
    mockedList.mockResolvedValue(categories);
    const { result } = renderHook(() => useCategoryLabel(), { wrapper });

    await waitFor(() => expect(mockedList).toHaveBeenCalled());
    expect(result.current({ name: "Some Deleted Category", categoryId: 999 })).toBe("Some Deleted Category");
  });

  it("passes through a null/undefined name unchanged", () => {
    mockedList.mockResolvedValue(categories);
    const { result } = renderHook(() => useCategoryLabel(), { wrapper });

    expect(result.current({ name: undefined })).toBeUndefined();
    expect(result.current({ name: null })).toBeUndefined();
  });
});
