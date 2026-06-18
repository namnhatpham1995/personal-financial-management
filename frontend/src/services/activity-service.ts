import { apiClient } from "@/lib/api-client";

export interface ActivityEvent {
  id: string;
  action: string;
  ts: string;
  correlationId: string;
  meta: Record<string, unknown>;
}

export interface ActivityPage {
  content: ActivityEvent[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
}

export const activityService = {
  list: async (page = 0, size = 20): Promise<ActivityPage> => {
    const { data } = await apiClient.get<ActivityPage>("/api/v1/activity", {
      params: { page, size },
    });
    return data;
  },
};
