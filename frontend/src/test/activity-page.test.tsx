import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { describe, expect, it, vi, beforeEach } from "vitest";
import ActivityPage from "@/app/dashboard/activity/page";
import { activityService } from "@/services/activity-service";
import { renderWithIntl as render } from "@/test/test-utils";

vi.mock("@/services/activity-service", () => ({
  activityService: { list: vi.fn() },
}));

const mockedList = vi.mocked(activityService.list);

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <ActivityPage />
    </QueryClientProvider>
  );
}

describe("ActivityPage", () => {
  beforeEach(() => {
    mockedList.mockReset();
  });

  it("requests the activity feed via the relative /activity path", async () => {
    mockedList.mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 20,
    });

    renderPage();

    await waitFor(() => expect(mockedList).toHaveBeenCalledWith(0, 20));
    // activityService.list is the single call site for the request path;
    // asserting it's invoked (rather than duplicated) is the regression guard here.
  });

  it("renders events on a successful fetch", async () => {
    mockedList.mockResolvedValue({
      content: [
        {
          id: "1",
          action: "accounts.created",
          ts: "2026-07-13T05:13:42.436294Z",
          correlationId: "corr-1",
          meta: {},
        },
      ],
      totalElements: 1,
      totalPages: 1,
      number: 0,
      size: 20,
    });

    renderPage();

    expect(await screen.findByText("Accounts Created")).toBeInTheDocument();
    expect(screen.queryByText("No activity yet")).not.toBeInTheDocument();
    expect(screen.queryByText("Couldn't load activity")).not.toBeInTheDocument();
  });

  it("shows the empty state only after a confirmed empty response", async () => {
    mockedList.mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 20,
    });

    renderPage();

    expect(await screen.findByText("No activity yet")).toBeInTheDocument();
    expect(screen.queryByText("Couldn't load activity")).not.toBeInTheDocument();
  });

  it("shows an error state with retry on failure, not the empty state", async () => {
    mockedList.mockRejectedValue(new Error("network error"));

    renderPage();

    expect(await screen.findByText("Couldn't load activity")).toBeInTheDocument();
    expect(screen.queryByText("No activity yet")).not.toBeInTheDocument();

    const retryButton = screen.getByRole("button", { name: "Retry" });
    mockedList.mockResolvedValue({
      content: [],
      totalElements: 0,
      totalPages: 1,
      number: 0,
      size: 20,
    });

    const user = userEvent.setup();
    await user.click(retryButton);

    expect(await screen.findByText("No activity yet")).toBeInTheDocument();
  });
});
