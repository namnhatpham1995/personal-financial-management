import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import LoginPage from "@/app/login/page";
import RegisterPage from "@/app/register/page";
import { renderWithIntl as render } from "@/test/test-utils";

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  push: vi.fn(),
  toastError: vi.fn(),
  setTheme: vi.fn(),
  resolvedTheme: "light",
  setLastSeenChangelogVersion: vi.fn(),
  markChangelogSeen: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: null,
    isLoading: false,
    login: mocks.login,
    register: mocks.register,
    logout: vi.fn(),
    setLastSeenChangelogVersion: mocks.setLastSeenChangelogVersion,
  }),
}));

vi.mock("@/services/changelog-service", () => ({
  changelogService: { markSeen: mocks.markChangelogSeen },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => ({
    push: mocks.push,
  }),
}));

vi.mock("sonner", () => ({
  toast: {
    error: mocks.toastError,
  },
}));

vi.mock("next-themes", () => ({
  useTheme: () => ({
    resolvedTheme: mocks.resolvedTheme,
    setTheme: mocks.setTheme,
  }),
}));

describe("auth password entry", () => {
  beforeEach(() => {
    mocks.login.mockResolvedValue(undefined);
    mocks.register.mockResolvedValue(undefined);
    mocks.resolvedTheme = "light";
    mocks.setLastSeenChangelogVersion.mockReset();
    mocks.markChangelogSeen.mockReset().mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
    document.documentElement.classList.remove("dark", "light");
    vi.clearAllMocks();
  });

  it("toggles login password visibility", async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    const password = screen.getByLabelText("Password") as HTMLInputElement;
    expect(password).toHaveAttribute("type", "password");

    await user.click(screen.getByRole("button", { name: "Show password" }));
    expect(password).toHaveAttribute("type", "text");

    await user.click(screen.getByRole("button", { name: "Hide password" }));
    expect(password).toHaveAttribute("type", "password");
  });

  it("switches theme from the login page", async () => {
    const user = userEvent.setup();
    render(<LoginPage />);

    await user.click(await screen.findByRole("button", { name: "Switch to dark theme" }));

    expect(mocks.setTheme).toHaveBeenCalledWith("dark");
  });

  it("toggles registration password fields independently", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    const password = screen.getByLabelText("Password") as HTMLInputElement;
    const confirmPassword = screen.getByLabelText("Confirm password") as HTMLInputElement;

    expect(password).toHaveAttribute("type", "password");
    expect(confirmPassword).toHaveAttribute("type", "password");

    await user.click(screen.getByRole("button", { name: "Show password" }));
    expect(password).toHaveAttribute("type", "text");
    expect(confirmPassword).toHaveAttribute("type", "password");

    await user.click(screen.getByRole("button", { name: "Show confirm password" }));
    expect(password).toHaveAttribute("type", "text");
    expect(confirmPassword).toHaveAttribute("type", "text");
  });

  it("switches theme from the registration page", async () => {
    const user = userEvent.setup();
    mocks.resolvedTheme = "dark";
    document.documentElement.classList.add("dark");
    render(<RegisterPage />);

    await user.click(await screen.findByRole("button", { name: "Switch to light theme" }));

    expect(mocks.setTheme).toHaveBeenCalledWith("light");
  });

  it("blocks registration when password confirmation does not match", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    await fillRegistrationForm(user, {
      password: "password123",
      confirmPassword: "password456",
    });
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("Passwords do not match")).toBeInTheDocument();
    expect(mocks.register).not.toHaveBeenCalled();
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("submits only the canonical password when confirmation matches", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    await fillRegistrationForm(user, {
      password: "password123",
      confirmPassword: "password123",
    });
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => {
      expect(mocks.register).toHaveBeenCalledWith({
        firstName: "Ada",
        lastName: "Lovelace",
        email: "ada@example.com",
        password: "password123",
      });
    });
    expect(mocks.register.mock.calls[0][0]).not.toHaveProperty("confirmPassword");
    expect(mocks.push).toHaveBeenCalledWith("/dashboard");
  });

  it("seeds a new user as caught up on the changelog after registering", async () => {
    const user = userEvent.setup();
    render(<RegisterPage />);

    await fillRegistrationForm(user, {
      password: "password123",
      confirmPassword: "password123",
    });
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(mocks.markChangelogSeen).toHaveBeenCalled());
    expect(mocks.setLastSeenChangelogVersion).toHaveBeenCalled();
  });
});

async function fillRegistrationForm(
  user: ReturnType<typeof userEvent.setup>,
  passwords: { password: string; confirmPassword: string }
) {
  await user.type(screen.getByLabelText("First name"), "Ada");
  await user.type(screen.getByLabelText("Last name"), "Lovelace");
  await user.type(screen.getByLabelText("Email"), "ada@example.com");
  await user.type(screen.getByLabelText("Password"), passwords.password);
  await user.type(screen.getByLabelText("Confirm password"), passwords.confirmPassword);
}
