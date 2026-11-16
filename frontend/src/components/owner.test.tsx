import { StrictMode } from "react";
import { act, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { OwnerPage } from "./owner";
import { ApiError } from "@/lib/api";
import { ownerListingSchema } from "@/lib/contracts";
import { convertToLosslessWebp, uploadDirect } from "@/lib/uploads";

let search = new URLSearchParams();
const request = vi.fn();
const api = { request };
const router = { push: vi.fn(), replace: vi.fn(), refresh: vi.fn() };
vi.mock("./area-map", () => ({ default: () => null }));
vi.mock("@/lib/uploads", async (original) => ({
  ...await original<typeof import("@/lib/uploads")>(),
  convertToLosslessWebp: vi.fn(), uploadDirect: vi.fn(),
}));
vi.mock("next/navigation", () => ({
  useRouter: () => router,
  useSearchParams: () => search,
}));
vi.mock("./session", () => ({
  useSession: () => ({
    api,
    account: { id: "00000000-0000-4000-8000-000000000001" },
    ready: true,
    epoch: 0,
    config: { basePath: "", mode: "PRIMARY" },
  }),
}));

beforeEach(() => {
  search = new URLSearchParams();
  request.mockReset();
  vi.mocked(convertToLosslessWebp).mockReset();
  vi.mocked(uploadDirect).mockReset();
});

it("synchronizes the status selection and submitted filter with URL history", async () => {
  request.mockResolvedValue({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
  search = new URLSearchParams("status=ACTIVE");
  const view = render(<OwnerPage />);
  expect(screen.getByLabelText("Status")).toHaveValue("ACTIVE");
  fireEvent.change(screen.getByLabelText("Status"), { target: { value: "PAUSED" } });
  fireEvent.click(screen.getByRole("button", { name: "Apply" }));
  expect(router.push).toHaveBeenLastCalledWith("/my-listings?status=PAUSED");
  search = new URLSearchParams("status=PAUSED");
  view.rerender(<OwnerPage />);
  search = new URLSearchParams("status=ACTIVE");
  view.rerender(<OwnerPage />);
  expect(screen.getByLabelText("Status")).toHaveValue("ACTIVE");
  fireEvent.click(screen.getByRole("button", { name: "Apply" }));
  expect(router.push).toHaveBeenLastCalledWith("/my-listings?status=ACTIVE");
  search = new URLSearchParams("status=PAUSED");
  view.rerender(<OwnerPage />);
  expect(screen.getByLabelText("Status")).toHaveValue("PAUSED");
  await waitFor(() => expect(request).toHaveBeenLastCalledWith("/api/v1/listings/mine?status=PAUSED", expect.anything()));
});

const listingId = "00000000-0000-4000-8000-000000000002";
const image = {
  imageId: "00000000-0000-4000-8000-000000000003", segment: "ROOM", displayOrder: 0,
  contentType: "image/webp", byteSize: 100, width: 2, height: 2, url: null, expiresAt: null,
};
function listing(photoCount = 0) {
  return ownerListingSchema.parse({
    id: listingId, ownerId: "00000000-0000-4000-8000-000000000001", title: "Test flat", description: "A test listing",
    status: "PAUSED", city: "Pune", locality: "Test locality", monthlyRent: 10000, securityDeposit: 0,
    currency: "INR", rentPeriod: "MONTH", hasWifi: false, hasGym: false, hasSwimmingPool: false,
    createdAt: "2026-11-15T00:00:00Z", updatedAt: "2026-11-15T00:00:00Z",
    owner: { fullName: "Test Owner", averageRating: null, reviewCount: 0, brokerReportCount: 0, trustBadge: "STANDARD" },
    images: Array.from({ length: photoCount }, (_, index) => ({ ...image, imageId: `00000000-0000-4000-8000-${String(index + 20).padStart(12, "0")}` })),
    publicLocation: { latitude: 18.51, longitude: 73.81, radiusMeters: 2000 },
    category: "FLAT", flatRentalMode: "ENTIRE_FLAT", rentBasis: "PER_PROPERTY", bedrooms: 1,
    wholeFlatAvailable: true, maxOccupants: 2, maleRoomsAvailable: null, femaleRoomsAvailable: null,
    sharedRoomsAvailable: null, totalRoomsAvailable: null, maxSharingPerRoom: null,
    version: 0, privateAddress: "Test address", latitude: 18.5, longitude: 73.8,
  });
}
function photoRequests(photoCount = 0) {
  let completions = 0;
  request.mockImplementation(async (path: string) => {
    if (path.endsWith("/presign")) return {
      imageId: image.imageId, objectKey: "staging/test", uploadUrl: "https://storage.example/test",
      expiresAt: new Date(Date.now() + 600_000).toISOString(), requiredHeaders: {},
    };
    if (path.endsWith("/complete")) { completions++; return image; }
    return { ...listing(photoCount), images: [...listing(photoCount).images, ...(completions ? [image] : [])] };
  });
  vi.mocked(convertToLosslessWebp).mockResolvedValue(new Blob(["webp"], { type: "image/webp" }));
  vi.mocked(uploadDirect).mockResolvedValue(undefined);
}

it("removes a failed local photo to release the final slot for a replacement", async () => {
  photoRequests(19);
  vi.mocked(convertToLosslessWebp).mockRejectedValueOnce(new Error("Unsupported photo"));
  render(<OwnerPage id={listingId} />);
  const input = await screen.findByLabelText("Choose photos");
  fireEvent.change(input, { target: { files: [new File(["bad"], "failed.png", { type: "image/png" })] } });
  fireEvent.click(screen.getByRole("button", { name: "Upload photo" }));
  const remove = await screen.findByRole("button", { name: "Remove failed photo" });
  expect(input).toBeDisabled();
  fireEvent.click(remove);
  expect(input).toBeEnabled();
  expect(screen.queryByText(/failed.png/)).not.toBeInTheDocument();
  fireEvent.change(input, { target: { files: [new File(["ok"], "replacement.png", { type: "image/png" })] } });
  fireEvent.click(screen.getByRole("button", { name: "Upload photo" }));
  expect(await screen.findByText("Validated")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Retry photo" })).not.toBeInTheDocument();
});

it("retains retry payloads for unfinished uploads without converting or reserving again", async () => {
  photoRequests();
  vi.mocked(uploadDirect).mockRejectedValueOnce(new Error("Connection interrupted"));
  render(<OwnerPage id={listingId} />);
  fireEvent.change(await screen.findByLabelText("Choose photos"), { target: { files: [new File(["ok"], "retry.png", { type: "image/png" })] } });
  fireEvent.click(screen.getByRole("button", { name: "Upload photo" }));
  fireEvent.click(await screen.findByRole("button", { name: "Retry photo" }));
  expect(await screen.findByText("Validated")).toBeInTheDocument();
  expect(convertToLosslessWebp).toHaveBeenCalledTimes(1);
  expect(request.mock.calls.filter(([path]) => String(path).endsWith("/presign"))).toHaveLength(1);
  expect(uploadDirect).toHaveBeenCalledTimes(2);
});

it("keeps a validated job completed when reloading the listing fails", async () => {
  photoRequests();
  const implementation = request.getMockImplementation();
  let loads = 0;
  request.mockImplementation(async (...args) => {
    if (args[0] === `/api/v1/listings/${listingId}` && ++loads === 2) throw new ApiError(503);
    return implementation?.(...args);
  });
  render(<OwnerPage id={listingId} />);
  fireEvent.change(await screen.findByLabelText("Choose photos"), { target: { files: [new File(["ok"], "completed.png", { type: "image/png" })] } });
  fireEvent.click(screen.getByRole("button", { name: "Upload photo" }));
  fireEvent.click(await screen.findByRole("button", { name: "Refresh photos" }));
  await waitFor(() => expect(screen.queryByRole("button", { name: "Refresh photos" })).not.toBeInTheDocument());
  expect(screen.getByText("Validated")).toBeInTheDocument();
  expect(screen.getByText(/completed.png/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Retry photo" })).not.toBeInTheDocument();
  expect(request.mock.calls.filter(([path]) => String(path).endsWith("/complete"))).toHaveLength(1);
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
