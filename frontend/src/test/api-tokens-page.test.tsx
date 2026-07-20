/**
 * PAT ambiguous-delivery recovery (task 7.5): a 409 idempotency_key_conflict on token
 * creation must never trigger an automatic retry (that could mint a second token), must
 * refetch the token list so the user can see whether a token from the ambiguous attempt
 * already exists, and must surface explicit recovery guidance with a deliberate
 * "try again" action.
 */
import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AxiosError } from "axios";
import ApiTokensPage from "@/app/dashboard/settings/api-tokens/page";
import { apiTokenService } from "@/services/api-token-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/api-token-service");

function conflictError(): AxiosError {
  return {
    isAxiosError: true,
    name: "AxiosError",
    message: "Request failed with status code 409",
    response: {
      status: 409,
      data: { error: { code: "idempotency_key_conflict" } },
      statusText: "Conflict",
      headers: {},
      config: {} as never,
    },
    config: {} as never,
    toJSON: () => ({}),
  } as AxiosError;
}

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ApiTokensPage />
    </QueryClientProvider>
  );
}

describe("ApiTokensPage: ambiguous-delivery recovery", () => {
  beforeEach(() => {
    vi.mocked(apiTokenService.list).mockResolvedValue([]);
  });

  it("on a 409 idempotency_key_conflict, does not auto-retry create and refetches the token list", async () => {
    vi.mocked(apiTokenService.create).mockRejectedValueOnce(conflictError());
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "New Token" }));
    await user.type(screen.getByPlaceholderText("e.g. Claude Desktop"), "Claude Desktop");
    await user.click(screen.getByRole("button", { name: "Create" }));

    await waitFor(() => {
      expect(screen.getByText("A token may already exist")).toBeInTheDocument();
    });

    // create was called exactly once — no automatic retry after the conflict
    expect(apiTokenService.create).toHaveBeenCalledTimes(1);
    // list refetched so the user can see whether a token from the attempt already exists
    await waitFor(() => {
      expect(apiTokenService.list).toHaveBeenCalledTimes(2);
    });
  });

  it("lets the user start a deliberate new attempt after the recovery guidance is shown", async () => {
    vi.mocked(apiTokenService.create).mockRejectedValueOnce(conflictError());
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole("button", { name: "New Token" }));
    await user.type(screen.getByPlaceholderText("e.g. Claude Desktop"), "Claude Desktop");
    await user.click(screen.getByRole("button", { name: "Create" }));

    await screen.findByText("A token may already exist");
    await user.click(screen.getByRole("button", { name: "Try again" }));

    expect(screen.queryByText("A token may already exist")).not.toBeInTheDocument();
  });
});
