/**
 * Language switcher tests (task 6.5).
 * Covers: all 4 supported languages listed by native name, switching sets the
 * locale cookie and triggers a soft refresh (not a full reload), and the
 * underlying locale/messages swap actually changes rendered text.
 */
import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useTranslations } from "next-intl";
import { afterEach, describe, expect, it, vi } from "vitest";
import { LanguageSwitcher } from "@/components/language-switcher";
import { renderWithIntl } from "@/test/test-utils";

const mockRefresh = vi.fn();
vi.mock("next/navigation", () => ({
  useRouter: () => ({ refresh: mockRefresh }),
}));

function SampleSignInTitle() {
  const t = useTranslations("auth.signIn");
  return <p>{t("title")}</p>;
}

afterEach(() => {
  mockRefresh.mockReset();
  document.cookie = "NEXT_LOCALE=; path=/; max-age=0";
});

describe("LanguageSwitcher", () => {
  it("lists all 4 supported languages by native name", () => {
    renderWithIntl(<LanguageSwitcher />);
    const options = screen.getAllByRole("option").map((o) => o.textContent);
    expect(options).toEqual(["English", "Tiếng Việt", "Deutsch", "简体中文"]);
  });

  it("switching sets the locale cookie and triggers a soft refresh", async () => {
    const user = userEvent.setup();
    renderWithIntl(<LanguageSwitcher />);

    await user.selectOptions(screen.getByRole("combobox"), "vi");

    expect(document.cookie).toContain("NEXT_LOCALE=vi");
    expect(mockRefresh).toHaveBeenCalledTimes(1);
  });

  it("does not call the backend by default (cookie-only, e.g. login/register pages)", async () => {
    const putSpy = vi.spyOn((await import("@/lib/api-client")).apiClient, "put");
    const user = userEvent.setup();
    renderWithIntl(<LanguageSwitcher />);

    await user.selectOptions(screen.getByRole("combobox"), "de");

    expect(putSpy).not.toHaveBeenCalled();
    putSpy.mockRestore();
  });

  it("syncs to the backend when syncToBackend is set", async () => {
    const putSpy = vi
      .spyOn((await import("@/lib/api-client")).apiClient, "put")
      .mockResolvedValue({ data: {} });
    const user = userEvent.setup();
    renderWithIntl(<LanguageSwitcher syncToBackend />);

    await user.selectOptions(screen.getByRole("combobox"), "de");

    expect(putSpy).toHaveBeenCalledWith("/auth/me/language", { language: "de" });
    putSpy.mockRestore();
  });

  it("swapping locale/messages renders a sampled translated string correctly", () => {
    const { unmount } = renderWithIntl(<SampleSignInTitle />, { locale: "en" });
    expect(screen.getByText("Sign in")).toBeInTheDocument();
    unmount();

    renderWithIntl(<SampleSignInTitle />, { locale: "vi" });
    expect(screen.getByText("Đăng nhập")).toBeInTheDocument();
  });
});
