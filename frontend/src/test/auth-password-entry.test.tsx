import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import LoginPage from "@/app/login/page";
import RegisterPage from "@/app/register/page";

const mocks = vi.hoisted(() => ({
  login: vi.fn(),
  register: vi.fn(),
  push: vi.fn(),
  toastError: vi.fn(),
}));

vi.mock("@/lib/auth-context", () => ({
  useAuth: () => ({
    user: null,
    isLoading: false,
    login: mocks.login,
    register: mocks.register,
    logout: vi.fn(),
  }),
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

describe("auth password entry", () => {
  beforeEach(() => {
    mocks.login.mockResolvedValue(undefined);
    mocks.register.mockResolvedValue(undefined);
  });

  afterEach(() => {
    cleanup();
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
