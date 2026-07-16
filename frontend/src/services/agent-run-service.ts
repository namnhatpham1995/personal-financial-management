import { apiClient } from "@/lib/api-client";

export type AgentRunStatus = "EXTRACTING" | "AWAITING_REVIEW" | "COMMITTED" | "REJECTED" | "FAILED";

export interface AgentRunSummary {
  id: number;
  vaultDocumentId: string;
  status: AgentRunStatus;
  createdAt: string;
  updatedAt: string;
}

export interface AgentProposal {
  merchant: string;
  date: string;
  amount: string;
  currency: string;
  categoryId: number | null;
  accountId: number | null;
  description?: string;
  flags: string[];
  excluded: boolean;
}

export interface ExtractionResult {
  merchant: string;
  date: string;
  currency: string;
  lineItems: Array<{ description: string; quantity?: number; amount: string }>;
  total: string;
}

export interface AgentRunDetail {
  id: number;
  vaultDocumentId: string;
  status: AgentRunStatus;
  extraction: ExtractionResult | null;
  proposals: AgentProposal[];
  failureReason: string | null;
  retryable: boolean;
  createdTransactionIds: number[] | null;
  createdAt: string;
  updatedAt: string;
}

export interface DecisionPayload {
  approve: boolean;
  proposals?: AgentProposal[];
}

/** True for a 503 from an agent endpoint — the feature is dark on this deployment. */
export function isAgentFeatureUnavailable(error: unknown): boolean {
  return (
    typeof error === "object" &&
    error !== null &&
    "response" in error &&
    (error as { response?: { status?: number } }).response?.status === 503
  );
}

export const agentRunService = {
  async start(vaultDocumentId: string): Promise<AgentRunSummary> {
    const { data } = await apiClient.post<AgentRunSummary>("/agent-runs", { vaultDocumentId });
    return data;
  },

  async list(): Promise<AgentRunSummary[]> {
    const { data } = await apiClient.get<AgentRunSummary[]>("/agent-runs");
    return data;
  },

  async getById(id: number): Promise<AgentRunDetail> {
    const { data } = await apiClient.get<AgentRunDetail>(`/agent-runs/${id}`);
    return data;
  },

  async decide(id: number, payload: DecisionPayload): Promise<AgentRunDetail> {
    const { data } = await apiClient.post<AgentRunDetail>(`/agent-runs/${id}/decision`, payload);
    return data;
  },
};
