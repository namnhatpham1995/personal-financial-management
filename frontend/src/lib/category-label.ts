"use client";

import { useTranslations } from "next-intl";
import { useQuery } from "@tanstack/react-query";
import { categoryService } from "@/services/category-service";

interface CategoryLabelInput {
  /** Raw category name as returned by the API; verbatim for user-created categories. */
  name: string | null | undefined;
  /** Present when the caller already has the flag (e.g. the categories page); skips the lookup. */
  system?: boolean;
  /** Used to resolve `system` via the cached category list when the flag isn't already known. */
  categoryId?: number | null;
}

/**
 * Translates a category name only when it is system-seeded (`system === true`), resolved either
 * from the flag directly or, when absent, by looking up `categoryId` against the cached category
 * list. Never translates on name match alone: a user category sharing a seeded name (e.g. a
 * custom "Travel" category) must render verbatim, so the `system` flag is the only gate.
 */
export function useCategoryLabel() {
  const t = useTranslations("systemCategories");
  const { data: categories } = useQuery({
    queryKey: ["categories"],
    queryFn: categoryService.list,
  });

  return ({ name, system, categoryId }: CategoryLabelInput): string | undefined => {
    if (!name) return name ?? undefined;

    const isSystem = system ?? (categoryId != null
      ? categories?.find((c) => c.id === categoryId)?.system ?? false
      : false);

    if (!isSystem) return name;
    return t.has(name) ? t(name) : name;
  };
}
