import { cleanup, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { AxiosError, AxiosHeaders } from "axios";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import LoginPage from "@/app/login/page";
import RegisterPage from "@/app/register/page";
import { classifyAuthError } from "@/lib/auth-error";
import { renderWithIntl as render } from "@/test/test-utils";

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
  useRouter: () => ({ push: mocks.push }),
}));

vi.mock("sonner", () => ({
  toast: { error: mocks.toastError },
}));

vi.mock("next-themes", () => ({
  useTheme: () => ({ resolvedTheme: "light", setTheme: vi.fn() }),
}));

describe("auth error classification", () => {
  it("keeps login credential failures generic", () => {
    expect(classifyAuthError(axiosError(401), "login")).toEqual({
      kind: "credentials",
    });
  });

  it("classifies rate limits for both auth flows", () => {
    expect(classifyAuthError(axiosError(429), "login").kind).toBe("rate_limit");
    expect(classifyAuthError(axiosError(429), "register").kind).toBe("rate_limit");
  });

  it("classifies missing responses as connection or browser configuration failures", () => {
    expect(classifyAuthError(new AxiosError("Network Error", "ERR_NETWORK"), "login")).toEqual({
      kind: "connection",
    });
  });

  it("classifies server and unexpected failures as temporary service problems", () => {
    expect(classifyAuthError(axiosError(503), "login").kind).toBe("server");
    expect(classifyAuthError(new Error("unexpected"), "register").kind).toBe("server");
  });
});

describe("auth forms preserve input after request failures", () => {
  beforeEach(() => {
    mocks.login.mockReset();
    mocks.register.mockReset();
    mocks.push.mockReset();
    mocks.toastError.mockReset();
  });

  afterEach(cleanup);

  it("shows the classified login message and preserves credentials", async () => {
    const user = userEvent.setup();
    mocks.login.mockRejectedValue(axiosError(429));
    render(<LoginPage />);

    await user.type(screen.getByLabelText("Email"), "user@example.com");
    await user.type(screen.getByLabelText("Password"), "password123");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Too many attempts. Please try again later."
      );
    });
    expect(screen.getByLabelText("Email")).toHaveValue("user@example.com");
    expect(screen.getByLabelText("Password")).toHaveValue("password123");
    expect(mocks.push).not.toHaveBeenCalled();
  });

  it("shows the classified registration message and preserves all fields", async () => {
    const user = userEvent.setup();
    mocks.register.mockRejectedValue(new AxiosError("Network Error", "ERR_NETWORK"));
    render(<RegisterPage />);

    await user.type(screen.getByLabelText("First name"), "Ada");
    await user.type(screen.getByLabelText("Last name"), "Lovelace");
    await user.type(screen.getByLabelText("Email"), "ada@example.com");
    await user.type(screen.getByLabelText("Password"), "password123");
    await user.type(screen.getByLabelText("Confirm password"), "password123");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => {
      expect(mocks.toastError).toHaveBeenCalledWith(
        "Unable to connect to Fintrack. Check your connection and try again."
      );
    });
    expect(screen.getByLabelText("First name")).toHaveValue("Ada");
    expect(screen.getByLabelText("Last name")).toHaveValue("Lovelace");
    expect(screen.getByLabelText("Email")).toHaveValue("ada@example.com");
    expect(screen.getByLabelText("Password")).toHaveValue("password123");
    expect(screen.getByLabelText("Confirm password")).toHaveValue("password123");
    expect(mocks.push).not.toHaveBeenCalled();
  });
});

function axiosError(status: number): AxiosError {
  return new AxiosError(
    `Request failed with status ${status}`,
    undefined,
    undefined,
    undefined,
    {
      status,
      statusText: "",
      headers: {},
      config: { headers: new AxiosHeaders() },
      data: {},
    }
  );
}
