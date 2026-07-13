import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { Sidebar } from "@/components/sidebar";
import { latestChangelogVersion } from "@/changelog/changelog-entries";
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

describe("Sidebar What's New indicator", () => {
  it("shows an unseen dot when the user has not seen the latest changelog version", () => {
    mocks.user = { email: "a@b.com", lastSeenChangelogVersion: 0 };
    render(<Sidebar />);
    const [link] = screen.getAllByRole("link", { name: "What's New" });
    expect(link.querySelector("[aria-hidden='true']")).toBeInTheDocument();
  });

  it("hides the dot once the user has seen the latest changelog version", () => {
    mocks.user = { email: "a@b.com", lastSeenChangelogVersion: latestChangelogVersion };
    render(<Sidebar />);
    const [link] = screen.getAllByRole("link", { name: "What's New" });
    expect(link.querySelector("[aria-hidden='true']")).not.toBeInTheDocument();
  });
});
