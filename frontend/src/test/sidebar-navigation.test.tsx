import { screen } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi } from "vitest";
import { Sidebar } from "@/components/sidebar";
import { agentRunService } from "@/services/agent-run-service";
import { renderWithIntl as render } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({ pathname: "/dashboard" }));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({ user: { email: "a@b.com", lastSeenChangelogVersion: 0 }, logout: vi.fn() }),
}));

vi.mock("next/navigation", () => ({
  usePathname: () => mocks.pathname,
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

describe("Sidebar navigation", () => {
  it("does not render a dedicated API Tokens item", () => {
    mocks.pathname = "/dashboard";
    renderSidebar();
    expect(screen.queryByRole("link", { name: "API Tokens" })).not.toBeInTheDocument();
  });

  it("marks Settings active when on the API tokens sub-page", () => {
    mocks.pathname = "/dashboard/settings/api-tokens";
    renderSidebar();
    const [settingsLink] = screen.getAllByRole("link", { name: "Settings" });
    expect(settingsLink).toHaveClass("text-primary");
  });

  it("marks Settings active on the settings page itself", () => {
    mocks.pathname = "/dashboard/settings";
    renderSidebar();
    const [settingsLink] = screen.getAllByRole("link", { name: "Settings" });
    expect(settingsLink).toHaveClass("text-primary");
  });

  it("does not mark Overview active on unrelated dashboard sub-pages", () => {
    mocks.pathname = "/dashboard/settings";
    renderSidebar();
    const [overviewLink] = screen.getAllByRole("link", { name: "Overview" });
    expect(overviewLink).not.toHaveClass("text-primary");
  });
});
