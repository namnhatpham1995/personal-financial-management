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
    await vaultService.upload("RECEIPT", new File(["x"], "r.jpg"), "a".repeat(16));
    expect(spy.mock.calls[0][0]).toBe("/vault/upload");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("list", async () => {
    const spy = vi
      .spyOn(apiClient, "get")
      .mockImplementation(() => okResponse({ content: [], totalElements: 0, totalPages: 0, size: 20, number: 0 }));
    await vaultService.list();
    expect(spy.mock.calls[0][0]).toBe("/vault");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
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

  it("getImportRows", async () => {
    const spy = vi.spyOn(apiClient, "get").mockImplementation(() => okResponse([]));
    await vaultService.getImportRows("doc-1");
    expect(spy.mock.calls[0][0]).toBe("/vault/import/doc-1/rows");
    expect(spy.mock.calls[0][1]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });

  it("confirmImport", async () => {
    const spy = vi.spyOn(apiClient, "post").mockImplementation(() => okResponse({ created: 1 }));
    await vaultService.confirmImport("doc-1", ["k1"], "a".repeat(16));
    expect(spy.mock.calls[0][0]).toBe("/vault/import/doc-1/confirm");
    expect(spy.mock.calls[0][2]).toMatchObject({ baseURL: VAULT_BASE_URL });
  });
});
