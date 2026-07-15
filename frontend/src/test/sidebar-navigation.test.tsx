import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Sidebar } from "@/components/sidebar";
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

describe("Sidebar navigation", () => {
  it("does not render a dedicated API Tokens item", () => {
    mocks.pathname = "/dashboard";
    render(<Sidebar />);
    expect(screen.queryByRole("link", { name: "API Tokens" })).not.toBeInTheDocument();
  });

  it("marks Settings active when on the API tokens sub-page", () => {
    mocks.pathname = "/dashboard/settings/api-tokens";
    render(<Sidebar />);
    const [settingsLink] = screen.getAllByRole("link", { name: "Settings" });
    expect(settingsLink).toHaveClass("text-primary");
  });

  it("marks Settings active on the settings page itself", () => {
    mocks.pathname = "/dashboard/settings";
    render(<Sidebar />);
    const [settingsLink] = screen.getAllByRole("link", { name: "Settings" });
    expect(settingsLink).toHaveClass("text-primary");
  });

  it("does not mark Overview active on unrelated dashboard sub-pages", () => {
    mocks.pathname = "/dashboard/settings";
    render(<Sidebar />);
    const [overviewLink] = screen.getAllByRole("link", { name: "Overview" });
    expect(overviewLink).not.toHaveClass("text-primary");
  });
});
