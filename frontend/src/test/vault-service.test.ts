/**
 * Regression test for fix-vault-api-v1-routing: vault/statement-import endpoints live at
 * `/api/vault` (unversioned), not `/api/v1/vault` like every other capability. Every
 * vaultService call must override apiClient's default `/api/v1` baseURL with
 * VAULT_BASE_URL — this test fails if a call site drifts back to relying on the default.
 */
import { describe, it, expect, beforeEach, vi } from "vitest";
import { apiClient, VAULT_BASE_URL } from "@/lib/api-client";
import { vaultService } from "@/services/vault-service";

const okResponse = <T,>(data: T) => Promise.resolve({ data } as never);

describe("vaultService: requests target the unversioned /api/vault base path", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("upload", async () => {
    const spy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse({}));
    await vaultService.upload("RECEIPT", 1, new File(["x"], "r.jpg"), "a".repeat(16));
    expect(spy.mock.calls[0][0]).toBe("/vault/upload");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("listByAccount", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }));
    await vaultService.listByAccount(1, "STATEMENT");
    expect(spy.mock.calls[0][0]).toBe("/vault/by-account");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("list", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }));
    await vaultService.list();
    expect(spy.mock.calls[0][0]).toBe("/vault");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("listByType supports an overall page and an optional account filter", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }));
    await vaultService.listByType("RECEIPT");
    expect(spy.mock.calls[0][0]).toBe("/vault");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL, params: { type: "RECEIPT", page: 0, size: 20 } });
    await vaultService.listByType("RECEIPT", 42, 1, 50);
    expect(spy.mock.calls[1][1]).toMatchObject({ params: { type: "RECEIPT", accountId: 42, page: 1, size: 50 } });
  });

  it("previewReassignment and reassign use the keyed Vault endpoints", async () => {
    const getSpy = vi.spyOn(apiClient, "get").mockImplementation(() => okResponse({}));
    const postSpy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse({}));
    await vaultService.previewReassignment("doc-1", 42);
    await vaultService.reassign("doc-1", 42, "a".repeat(16));
    expect(getSpy.mock.calls[0][0]).toBe("/vault/doc-1/reassignment-preview");
    expect(postSpy.mock.calls[0][0]).toBe("/vault/doc-1/reassign");
    expect(postSpy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL, headers: { "Idempotency-Key": "a".repeat(16) } });
  });

  it("listUnassignedReceipts", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }));
    await vaultService.listUnassignedReceipts();
    expect(spy.mock.calls[0][0]).toBe("/vault/unassigned-receipts");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("assignAccount", async () => {
    const spy = vi.spyOn(apiClient, "patch").mockImplementation(() => okResponse({}));
    await vaultService.assignAccount("doc-1", 42);
    expect(spy.mock.calls[0][0]).toBe("/vault/doc-1/account");
    expect(spy.mock.calls[0][2]).toMatchObject({
      baseURL: VAULT_BASE_URL,
      params: { accountId: 42 },
    });
  });

  it("getById", async () => {
    const spy = vi.spyOn(apiClient, "get").mockImplementation(() => okResponse({}));
    await vaultService.getById("doc-1");
    expect(spy.mock.calls[0][0]).toBe("/vault/doc-1");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("getDownloadUrl", async () => {
    const spy = vi.spyOn(apiClient, "get").mockImplementation(() => okResponse(new Blob()));
    vi.stubGlobal("URL", { ...URL, createObjectURL: vi.fn().mockReturnValue("blob:x") });
    await vaultService.getDownloadUrl("doc-1");
    expect(spy.mock.calls[0][0]).toBe("/vault/doc-1/download");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("linkToTransaction", async () => {
    const spy = vi.spyOn(apiClient, "patch").mockImplementation(() => okResponse({}));
    await vaultService.linkToTransaction("doc-1", 42);
    expect(spy.mock.calls[0][0]).toBe("/vault/doc-1/link");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("byTransactionIds", async () => {
    const spy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse([]));
    await vaultService.byTransactionIds([1, 2]);
    expect(spy.mock.calls[0][0]).toBe("/vault/by-transactions");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("deleteById", async () => {
    const spy = vi.spyOn(apiClient, "delete").mockImplementation(() => okResponse(undefined));
    await vaultService.deleteById("doc-1");
    expect(spy.mock.calls[0][0]).toBe("/vault/doc-1");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("importUpload", async () => {
    const spy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse({ documentId: "doc-1" }));
    await vaultService.importUpload(1, new File(["x"], "s.csv"), "a".repeat(16));
    expect(spy.mock.calls[0][0]).toBe("/vault/import/upload");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("parseImport", async () => {
    const spy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse([]));
    await vaultService.parseImport("doc-1");
    expect(spy.mock.calls[0][0]).toBe("/vault/import/doc-1/parse");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("getImportRows", async () => {
    const spy = vi.spyOn(apiClient, "get").mockImplementation(() => okResponse([]));
    await vaultService.getImportRows("doc-1");
    expect(spy.mock.calls[0][0]).toBe("/vault/import/doc-1/rows");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("workspace serializes type/stage arrays as repeated keys, not bracket notation", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }));
    await vaultService.workspace({ types: ["STATEMENT", "RECEIPT"], stages: ["NEEDS_REVIEW"], accountId: 42 });
    expect(spy.mock.calls[0][0]).toBe("/vault/workspace");
    expect(spy.mock.calls[0][1]).toMatchObject({
      baseURL: VAULT_BASE_URL,
      params: { type: ["STATEMENT", "RECEIPT"], stage: ["NEEDS_REVIEW"], accountId: 42, page: 0, size: 20 },
      paramsSerializer: { indexes: null },
    });
  });

  it("workspaceStageCounts unwraps the counts envelope", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ counts: { READY_TO_IMPORT: 3, NOT_PROCESSED: 1 } }));
    const counts = await vaultService.workspaceStageCounts({ types: ["STATEMENT"] });
    expect(spy.mock.calls[0][0]).toBe("/vault/workspace/counts");
    expect(spy.mock.calls[0][1]).toMatchObject({
      baseURL: VAULT_BASE_URL,
      params: { type: ["STATEMENT"] },
      paramsSerializer: { indexes: null },
    });
    expect(counts).toEqual({ READY_TO_IMPORT: 3, NOT_PROCESSED: 1 });
  });

  it("confirmImport", async () => {
    const spy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse({ created: 1 }));
    await vaultService.confirmImport("doc-1", [{ dedupKey: "k1", categoryId: null }], "a".repeat(16));
    expect(spy.mock.calls[0][0]).toBe("/vault/import/doc-1/confirm");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });
});
