import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { WhatsNewModal } from "@/components/whats-new-modal";
import { changelogService } from "@/services/changelog-service";
import { latestChangelogVersion } from "@/changelog/changelog-entries";
import { renderWithIntl as render } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  user: { id: 1, lastSeenChangelogVersion: 0 },
  setLastSeenChangelogVersion: vi.fn(),
  push: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: mocks.user,
    setLastSeenChangelogVersion: mocks.setLastSeenChangelogVersion,
  }),
}));

vi.mock("@/services/changelog-service", () => ({
  changelogService: { markSeen: vi.fn() },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({ push: mocks.push }),
}));

// Decouples the "more than 3 unseen entries" cases from however many entries
// are actually authored today (currently 3, which alone can't exceed the cap).
vi.mock("@/changelog/changelog-entries", () => ({
  changelogEntries: [
    { version: 4, date: "2026-07-14", titleKey: "changelog.entries.3.title", bodyKey: "changelog.entries.3.body", tag: "new" },
    { version: 3, date: "2026-07-13", titleKey: "changelog.entries.3.title", bodyKey: "changelog.entries.3.body", tag: "new" },
    { version: 2, date: "2026-06-02", titleKey: "changelog.entries.2.title", bodyKey: "changelog.entries.2.body", tag: "new" },
    { version: 1, date: "2026-05-05", titleKey: "changelog.entries.1.title", bodyKey: "changelog.entries.1.body", tag: "improved" },
  ],
  latestChangelogVersion: 4,
}));

const mockedMarkSeen = vi.mocked(changelogService.markSeen);

describe("WhatsNewModal", () => {
  beforeEach(() => {
    mockedMarkSeen.mockReset().mockResolvedValue();
    mocks.setLastSeenChangelogVersion.mockReset();
    mocks.push.mockReset();
    mocks.user = { id: 1, lastSeenChangelogVersion: 0 };
  });

  it("opens when the user has unseen entries", () => {
    render(<WhatsNewModal />);
    expect(screen.getByRole("dialog", { name: "What's New" })).toBeInTheDocument();
  });

  it("does not render when the user has already seen the latest version", () => {
    mocks.user = { id: 1, lastSeenChangelogVersion: latestChangelogVersion };
    render(<WhatsNewModal />);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("caps visible entries at 3 and shows a view-all link when there are more", () => {
    mocks.user = { id: 1, lastSeenChangelogVersion: -1 };
    render(<WhatsNewModal />);
    expect(screen.getByRole("button", { name: "View all updates" })).toBeInTheDocument();
  });

  it("marks the latest version seen and closes when dismissed via Got it", async () => {
    const user = userEvent.setup();
    render(<WhatsNewModal />);

    await user.click(screen.getByRole("button", { name: "Got it" }));

    expect(mockedMarkSeen).toHaveBeenCalledWith(latestChangelogVersion);
    expect(mocks.setLastSeenChangelogVersion).toHaveBeenCalledWith(latestChangelogVersion);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("marks seen and closes on Escape", async () => {
    const user = userEvent.setup();
    render(<WhatsNewModal />);

    await user.keyboard("{Escape}");

    await waitFor(() => expect(mockedMarkSeen).toHaveBeenCalledWith(latestChangelogVersion));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it("marks seen and navigates to the full page via view all", async () => {
    const user = userEvent.setup();
    render(<WhatsNewModal />);

    await user.click(screen.getByRole("button", { name: "View all updates" }));

    expect(mockedMarkSeen).toHaveBeenCalledWith(latestChangelogVersion);
    expect(mocks.push).toHaveBeenCalledWith("/dashboard/whats-new");
  });

  it("marks seen and closes on backdrop click", async () => {
    const user = userEvent.setup();
    const { container } = render(<WhatsNewModal />);

    const backdrop = container.querySelector("[aria-hidden='true']");
    expect(backdrop).not.toBeNull();
    await user.click(backdrop as Element);

    expect(mockedMarkSeen).toHaveBeenCalledWith(latestChangelogVersion);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });
});
