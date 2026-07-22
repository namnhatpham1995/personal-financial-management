/**
 * Scans .tsx source text for raw enum values rendered as display text instead of
 * going through the enum-label / category-label translation hooks. Two patterns:
 *
 * 1. A bare property-access chain ending in a known enum-ish property name, sitting
 *    directly between `>` and `<` as a JSX child (e.g. `{tx.transactionType}`,
 *    `{doc.status}`). A call like `{getTypeLabel(tx.transactionType)}` doesn't match
 *    because the captured expression isn't an exact property-access chain — it has
 *    the wrapping call syntax around it.
 * 2. `<option key={x}>{x}</option>` — the same bare identifier used as both the
 *    option's key and its rendered text, the shape every raw-enum `<option>` leak in
 *    this codebase took (see design.md Family A).
 *
 * Deliberately restricted to JSX child positions so comparisons (`x.status === "…"`),
 * `value={x}` attributes, query keys, and Zod enums are never flagged — those aren't
 * display text. Escape hatch for a legitimate bare case (e.g. currency codes, which are
 * never translated): add `enum-label-coverage-ignore` anywhere on the flagged line or
 * the line directly above it.
 */

export interface EnumLabelLeak {
  line: number;
  snippet: string;
  rule: "bare-property-access" | "option-key-equals-child";
}

const ENUM_PROPERTY_SUFFIX =
  /^[A-Za-z_$][\w$]*(\.[A-Za-z_$][\w$]*)*\.(transactionType|frequency|accountType|documentStatus|ingestionStatus|status)$/;

const JSX_CHILD_EXPR = />\s*\{\s*([^{}<>]+?)\s*\}\s*</g;
const OPTION_KEY_ECHO =
  /<option\b[^>]*\bkey=\{\s*(\w+)\s*\}[^>]*>\s*\{\s*(\w+)\s*\}\s*<\/option>/g;

function lineOf(source: string, index: number): number {
  return source.slice(0, index).split("\n").length;
}

export function scanTsxForEnumLeaks(source: string): EnumLabelLeak[] {
  const leaks: EnumLabelLeak[] = [];
  const lines = source.split("\n");
  const isIgnored = (line: number) =>
    lines[line - 1]?.includes("enum-label-coverage-ignore") ||
    lines[line - 2]?.includes("enum-label-coverage-ignore");

  for (const match of Array.from(source.matchAll(JSX_CHILD_EXPR))) {
    const expr = match[1];
    if (!ENUM_PROPERTY_SUFFIX.test(expr)) continue;
    const line = lineOf(source, match.index ?? 0);
    if (isIgnored(line)) continue;
    leaks.push({ line, snippet: match[0].trim(), rule: "bare-property-access" });
  }

  for (const match of Array.from(source.matchAll(OPTION_KEY_ECHO))) {
    const [keyIdent, childIdent] = [match[1], match[2]];
    if (keyIdent !== childIdent) continue;
    const line = lineOf(source, match.index ?? 0);
    if (isIgnored(line)) continue;
    leaks.push({ line, snippet: match[0].trim(), rule: "option-key-equals-child" });
  }

  return leaks;
}
