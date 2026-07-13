import { screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi, beforeEach } from "vitest";
import WhatsNewPage from "@/app/dashboard/whats-new/page";
import { changelogService } from "@/services/changelog-service";
import { latestChangelogVersion } from "@/changelog/changelog-entries";
import { renderWithIntl as render } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  setLastSeenChangelogVersion: vi.fn(),
}));

vi.mock("@/services/changelog-service", () => ({
  changelogService: { markSeen: vi.fn() },
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: { id: 1, lastSeenChangelogVersion: 0 },
    setLastSeenChangelogVersion: mocks.setLastSeenChangelogVersion,
  }),
}));

const mockedMarkSeen = vi.mocked(changelogService.markSeen);

describe("WhatsNewPage", () => {
  beforeEach(() => {
    mockedMarkSeen.mockReset().mockResolvedValue();
    mocks.setLastSeenChangelogVersion.mockReset();
  });

  it("renders every changelog entry title", () => {
    render(<WhatsNewPage />);
    expect(screen.getByText("Multi-language interface")).toBeInTheDocument();
    expect(screen.getByText("Receipt & Statement Vault")).toBeInTheDocument();
    expect(screen.getByText("Multi-currency budgets")).toBeInTheDocument();
  });

  it("marks the latest version as seen on mount when there are unseen entries", async () => {
    render(<WhatsNewPage />);

    await waitFor(() => expect(mockedMarkSeen).toHaveBeenCalledWith(latestChangelogVersion));
    expect(mocks.setLastSeenChangelogVersion).toHaveBeenCalledWith(latestChangelogVersion);
  });
});
