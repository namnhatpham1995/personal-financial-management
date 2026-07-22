import { readFileSync, readdirSync } from "node:fs";
import { join, relative, resolve, sep } from "node:path";
import { describe, expect, it } from "vitest";
import { scanTsxForEnumLeaks } from "@/lib/enum-label-scan";

/** Recursively lists .tsx files under `dir`, skipping `src/test/` — no test-runner dependency needed. */
function listTsxFiles(dir: string, root: string): string[] {
  const entries = readdirSync(dir, { withFileTypes: true });
  const files: string[] = [];
  for (const entry of entries) {
    const fullPath = join(dir, entry.name);
    const relPath = relative(root, fullPath);
    if (entry.isDirectory()) {
      if (relPath === "test" || relPath.startsWith(`test${sep}`)) continue;
      files.push(...listTsxFiles(fullPath, root));
    } else if (entry.name.endsWith(".tsx")) {
      files.push(relPath);
    }
  }
  return files;
}

describe("scanTsxForEnumLeaks (fixtures)", () => {
  it("flags a bare enum property rendered as a JSX child (transaction-table.tsx:105 shape)", () => {
    const src = `<Badge variant={variant}>\n  {tx.transactionType}\n</Badge>`;
    const leaks = scanTsxForEnumLeaks(src);
    expect(leaks).toHaveLength(1);
    expect(leaks[0].rule).toBe("bare-property-access");
  });

  it("flags a bare enum property rendered as a JSX child (recurring-tab.tsx:174 shape)", () => {
    const src = `<Badge variant={v}>{item.transactionType}</Badge>`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(1);
  });

  it("flags document/ingestion status rendered bare (vault/page.tsx:201,213 shape)", () => {
    expect(scanTsxForEnumLeaks(`<span>{doc.status}</span>`)).toHaveLength(1);
    expect(scanTsxForEnumLeaks(`<span>{doc.ingestionStatus}</span>`)).toHaveLength(1);
  });

  it("flags a raw enum literal used as both option key and child (recurring-tab.tsx:134 / account-management-ui.tsx:213 shape)", () => {
    const src = `{["DAILY","WEEKLY"].map((f) => <option key={f}>{f}</option>)}`;
    const leaks = scanTsxForEnumLeaks(src);
    expect(leaks).toHaveLength(1);
    expect(leaks[0].rule).toBe("option-key-equals-child");
  });

  it("flags the transactions/page.tsx:181 filter option shape", () => {
    const src = `{["INCOME","EXPENSE","TRANSFER"].map((type) => <option key={type}>{type}</option>)}`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(1);
  });

  it("does not flag a value resolved through a translation hook", () => {
    const src = `<Badge variant={variant}>{getTypeLabel(tx.transactionType)}</Badge>`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(0);
  });

  it("does not flag a ternary/comparison using the raw value for styling, not display", () => {
    const src = `<Badge variant={tx.transactionType === "INCOME" ? "income" : "expense"} />`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(0);
  });

  it("does not flag a `value` attribute carrying the raw enum value", () => {
    const src = `<option key={type} value={type}>{getTypeLabel(type)}</option>`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(0);
  });

  it("does not flag a query key or Zod enum containing the same words", () => {
    const src = `
      useQuery({ queryKey: ["categories"], queryFn: categoryService.list });
      const schema = z.enum(["INCOME", "EXPENSE", "TRANSFER"]);
    `;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(0);
  });

  it("does not flag an option whose key and child use different identifiers", () => {
    const src = `<option key={c.id}>{getCategoryLabel({ name: c.name, system: c.system })}</option>`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(0);
  });

  it("honors the enum-label-coverage-ignore escape hatch", () => {
    const src = `<span>{doc.status}</span> {/* enum-label-coverage-ignore: intentional raw debug output */}`;
    expect(scanTsxForEnumLeaks(src)).toHaveLength(0);
  });
});

describe("scanTsxForEnumLeaks (repo scan)", () => {
  it("reports zero leaks across frontend/src (excluding src/test/)", () => {
    const root = resolve(__dirname, "..");
    const violations: string[] = [];

    for (const path of listTsxFiles(root, root)) {
      const source = readFileSync(join(root, path), "utf-8");
      for (const leak of scanTsxForEnumLeaks(source)) {
        violations.push(`${path}:${leak.line} [${leak.rule}] ${leak.snippet}`);
      }
    }

    expect(violations, violations.join("\n")).toHaveLength(0);
  });
});
