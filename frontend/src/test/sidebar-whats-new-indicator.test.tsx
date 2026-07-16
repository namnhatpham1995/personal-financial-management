import { screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { Sidebar } from "@/components/sidebar";
import { latestChangelogVersion } from "@/changelog/changelog-entries";
import { agentRunService } from "@/services/agent-run-service";
import { renderWithIntl as render } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({ user: { email: "a@b.com", lastSeenChangelogVersion: 0 } }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ user: mocks.user, logout: vi.fn() }),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => "/dashboard",
}));

vi.mock("next-themes", () => ({
  useTheme: () => ({ theme: "light", setTheme: vi.fn() }),
}));

vi.mock("@/services/agent-run-service", async () => {
  const actual = await vi.importActual<typeof import("@/services/agent-run-service")>(
    "@/services/agent-run-service"
  );
  return { ...actual, agentRunService: { list: vi.fn().mockResolvedValue([]) } };
});

function renderSidebar() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <Sidebar />
    </QueryClientProvider>
  );
}

describe("Sidebar What's New indicator", () => {
  it("shows an unseen dot when the user has not seen the latest changelog version", () => {
    mocks.user = { email: "a@b.com", lastSeenChangelogVersion: 0 };
    renderSidebar();
    const [link] = screen.getAllByRole("link", { name: "What's New" });
    expect(link.querySelector("[aria-hidden='true']")).toBeInTheDocument();
  });

  it("hides the dot once the user has seen the latest changelog version", () => {
    mocks.user = { email: "a@b.com", lastSeenChangelogVersion: latestChangelogVersion };
    renderSidebar();
    const [link] = screen.getAllByRole("link", { name: "What's New" });
    expect(link.querySelector("[aria-hidden='true']")).not.toBeInTheDocument();
  });
});
