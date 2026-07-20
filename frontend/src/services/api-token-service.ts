import { apiClient } from "@/lib/api-client";

export type ApiTokenScope = "READ" | "WRITE";

export interface ApiToken {
  id: number;
  name: string;
  tokenPrefix: string;
  scope: ApiTokenScope;
  createdAt: string;
  expiresAt: string;
  lastUsedAt: string | null;
  revoked: boolean;
}

export interface CreateApiTokenPayload {
  name: string;
  scope: ApiTokenScope;
  expiryDays: number;
}

export interface CreatedApiToken {
  token: ApiToken;
  plaintextToken: string;
}

export const apiTokenService = {
  list: () => apiClient.get<ApiToken[]>("/tokens").then((r) => r.data),
  create: (data: CreateApiTokenPayload, idempotencyKey: string) =>
    apiClient
      .post<CreatedApiToken>("/tokens", data, { headers: { "Idempotency-Key": idempotencyKey } })
      .then((r) => r.data),
  revoke: (id: number) => apiClient.delete(`/tokens/${id}`),
};
