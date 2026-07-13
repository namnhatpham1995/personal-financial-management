import { apiClient } from "@/lib/api-client";

export const changelogService = {
  markSeen: async (version: number): Promise<void> => {
    await apiClient.put("/auth/me/changelog-seen", { version });
  },
};
