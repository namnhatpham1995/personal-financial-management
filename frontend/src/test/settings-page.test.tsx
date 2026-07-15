import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import SettingsPage from "@/app/dashboard/settings/page";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: vi.fn() }),
}));

vi.mock("@/lib/api-client", () => ({
  apiClient: { put: vi.fn().mockResolvedValue(undefined) },
}));

describe("Settings page", () => {
  it("renders the API Tokens card as a single link to the token page", () => {
    render(<SettingsPage />);

    const cardLink = screen.getByRole("link", { name: /API Tokens/ });
    expect(cardLink).toHaveAttribute("href", "/dashboard/settings/api-tokens");
    expect(cardLink).toHaveAccessibleName(
      "API Tokens Manage tokens for API clients and the MCP server."
    );
  });
});
