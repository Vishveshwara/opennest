import { StrictMode } from "react";
import { act, render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { OwnerPage } from "./owner";

let search = new URLSearchParams();
const request = vi.fn();
const router = { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() };
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  useSearchParams: () => search,
}));
vi.mock("./session", () => ({
  useSession: () => ({
    api: { request },
    account: { id: "00000000-0000-4000-8000-000000000001" },
    ready: true,
    epoch: 0,
    config: { basePath: "", mode: "PRIMARY" },
  }),
}));

beforeEach(() => {
  search = new URLSearchParams();
  request.mockReset();
});

it("ignores superseded owner reads without aborting navigation or Strict Mode cleanup", async () => {
  const abort = vi.spyOn(AbortController.prototype, "abort");
  const pending: ((value: unknown) => void)[] = [];
  request.mockImplementation(
    () => new Promise((resolve) => pending.push(resolve)),
  );
  const view = render(
    <StrictMode>
      <OwnerPage />
    </StrictMode>,
  );
  search = new URLSearchParams("page=3");
  view.rerender(
    <StrictMode>
      <OwnerPage />
    </StrictMode>,
  );
  const latest = pending.at(-1);
  if (!latest) throw new Error("The owner request did not start");
  await act(async () =>
    latest({
      items: [],
      page: 3,
      size: 20,
      totalElements: 30,
      totalPages: 2,
      hasNext: false,
      hasPrevious: true,
      sort: "NEWEST",
    }),
  );
  expect(screen.getByText("30 matching places")).toBeInTheDocument();
  await act(async () => {
    pending
      .slice(0, -1)
      .forEach((resolve) =>
        resolve({
          items: [],
          page: 0,
          size: 20,
          totalElements: 0,
          totalPages: 0,
          hasNext: false,
          hasPrevious: false,
          sort: "NEWEST",
        }),
      );
  });
  expect(screen.getByText("30 matching places")).toBeInTheDocument();
  view.unmount();
  expect(abort).not.toHaveBeenCalled();
});
