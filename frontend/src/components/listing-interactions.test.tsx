import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { ApiError } from "@/lib/api";
import {
  ListingContact,
  ListingInteractionScope,
  ListingReviews,
} from "./listing-interactions";

const request = vi.fn();
const api = { request };
const account = { id: "test-seeker" };
const listing = { id: "test-flat", ownerId: "test-owner" };
const date = () =>
  new Date().toLocaleDateString("sv-SE", { timeZone: "Asia/Kolkata" });

vi.mock("next/navigation", () => ({ useRouter: () => ({ refresh: vi.fn() }) }));
vi.mock("./session", () => ({
  useSession: () => ({
    api,
    account,
    epoch: 0,
    config: { mode: "PRIMARY", basePath: "" },
  }),
}));

function state(overrides = {}) {
  return {
    quotaDate: date(),
    remainingReveals: 10,
    revealedToday: false,
    contactActionsEnabled: true,
    hasHistoricalReveal: false,
    canReview: false,
    canReportBrokerage: false,
    review: null,
    ...overrides,
  };
}

const revealButton = () =>
  screen.getByRole("button", { name: "Reveal owner contact" });

function reviewPage(page: number, commentText: string) {
  return {
    page,
    size: 5,
    totalPages: 2,
    totalElements: 6,
    hasPrevious: page > 0,
    hasNext: page === 0,
    items: [
      {
        id: `review-${page}`,
        rating: 5,
        commentText,
        issueTag: "GENUINE_OWNER",
        createdAt: "2026-11-18T09:00:00Z",
      },
    ],
  };
}

function interactions(value = listing) {
  return (
    <ListingInteractionScope id={value.id}>
      <ListingContact listing={value} />
      <ListingReviews listing={value} />
    </ListingInteractionScope>
  );
}

beforeEach(() => {
  request.mockReset();
});
afterEach(() => vi.useRealTimers());

it("offers a contact-state retry while keeping reveal disabled until it succeeds", async () => {
  const retry = Promise.withResolvers<unknown>();
  request
    .mockRejectedValueOnce(new ApiError(503))
    .mockReturnValueOnce(retry.promise);
  render(<ListingContact listing={listing} />);
  const retryButton = await screen.findByRole("button", {
    name: "Retry contact status",
  });
  expect(revealButton()).toBeDisabled();
  fireEvent.click(retryButton);
  expect(revealButton()).toBeDisabled();
  await act(async () => retry.resolve(state()));
  expect(revealButton()).toBeEnabled();
});

it.each(["timer", "focus"])(
  "refreshes an exhausted quota across Kolkata midnight by %s",
  async (trigger) => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-11-18T18:29:59.500Z"));
    request.mockResolvedValueOnce(state({ remainingReveals: 0 }));
    request.mockImplementation(async () => state());
    render(<ListingContact listing={listing} />);
    await act(async () => {});
    expect(revealButton()).toBeDisabled();
    expect(request).toHaveBeenCalledTimes(1);

    if (trigger === "timer") {
      await act(async () => vi.advanceTimersByTimeAsync(500));
    } else {
      vi.setSystemTime(new Date("2026-11-18T18:30:01Z"));
      fireEvent(window, new Event("focus"));
      await act(async () => {});
    }
    expect(revealButton()).toBeEnabled();
    expect(
      screen.getByText(/10 of 10 distinct contacts remaining on 2026-11-19/),
    ).toBeVisible();
    expect(request).toHaveBeenCalledTimes(2);
  },
);

it("updates review eligibility on successful reveal without a redundant contact-state read", async () => {
  let revealed = false;
  request.mockImplementation(async (path: string) => {
    if (path.endsWith("/reveal-phone")) {
      revealed = true;
      return {
        phoneNumber: "+910000000001",
        whatsappUrl: "https://wa.me/910000000001",
        remainingReveals: 9,
        revealedDate: date(),
      };
    }
    if (path.endsWith("/state"))
      return state({ canReview: revealed, hasHistoricalReveal: revealed });
    return reviewPage(0, "First page");
  });
  render(interactions());
  await waitFor(() => expect(revealButton()).toBeEnabled());
  await screen.findByText(/Reveal this listing’s contact/);
  fireEvent.click(revealButton());
  expect(
    await screen.findByRole("link", { name: "Open WhatsApp" }),
  ).toBeVisible();
  expect(
    await screen.findByRole("heading", { name: "Share your experience" }),
  ).toBeVisible();
  expect(
    request.mock.calls.filter(([path]) => path.endsWith("/state")),
  ).toHaveLength(3);
});

it("leaves review eligibility alone when the reveal POST fails", async () => {
  request.mockImplementation(async (path: string) => {
    if (path.endsWith("/reveal-phone")) throw new ApiError(503);
    if (path.endsWith("/state")) return state();
    return reviewPage(0, "First page");
  });
  render(interactions());
  await waitFor(() => expect(revealButton()).toBeEnabled());
  await screen.findByText(/Reveal this listing’s contact/);
  fireEvent.click(revealButton());
  expect(await screen.findByRole("alert")).toBeVisible();
  expect(request.mock.calls.filter(([path]) => path.endsWith("/state"))).toHaveLength(2);
  expect(screen.queryByRole("heading", { name: "Share your experience" })).toBeNull();
});

it("keeps a late reveal on the previous listing out of the current listing", async () => {
  const previousReveal = Promise.withResolvers<unknown>();
  const other = { ...listing, id: "test-other-flat" };
  request.mockImplementation(async (path: string) => {
    if (path.endsWith("/reveal-phone")) return previousReveal.promise;
    if (path.endsWith("/state")) return state();
    return reviewPage(0, "First page");
  });
  const view = render(interactions());
  await waitFor(() => expect(revealButton()).toBeEnabled());
  fireEvent.click(revealButton());
  view.rerender(interactions(other));
  await waitFor(() => expect(revealButton()).toBeEnabled());
  const otherStatePath = `/api/v1/interactions/listings/${other.id}/state`;
  await act(async () =>
    previousReveal.resolve({ phoneNumber: "+910000000001" }),
  );
  expect(
    request.mock.calls.filter(([path]) => path === otherStatePath),
  ).toHaveLength(2);
  expect(screen.queryByRole("link", { name: "Open WhatsApp" })).toBeNull();
});

it("keeps review navigation on its last response through a pending and failed page", async () => {
  const pending = Promise.withResolvers<unknown>();
  request.mockImplementation(async (path: string) => {
    if (path.endsWith("/state")) return state();
    return path.includes("page=1")
      ? pending.promise
      : reviewPage(0, "First page");
  });
  render(<ListingReviews listing={listing} />);
  const next = await screen.findByRole("button", { name: "Next reviews" });
  fireEvent.click(next);
  expect(next).toBeDisabled();
  expect(screen.getByText("Page 1 of 2")).toBeVisible();
  expect(screen.getByText("First page")).toBeVisible();
  await act(async () => pending.reject(new ApiError(503)));
  expect(next).toBeEnabled();
  expect(screen.getByText("Page 1 of 2")).toBeVisible();
  expect(screen.getByRole("alert")).toBeVisible();

  request.mockImplementation(async (path: string) =>
    path.endsWith("/state") ? state() : reviewPage(1, "Second page"),
  );
  fireEvent.click(next);
  expect(await screen.findByText("Second page")).toBeVisible();
  expect(screen.getByText("Page 2 of 2")).toBeVisible();
  expect(next).toBeDisabled();
  expect(screen.queryByRole("alert")).toBeNull();
});
