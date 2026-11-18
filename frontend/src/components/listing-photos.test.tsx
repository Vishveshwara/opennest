import { act, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { ListingPhotos } from "./listing-photos";

vi.mock("./session", () => ({
  useSession: () => ({
    config: {
      apiOrigin: "https://api.example.test",
      browserApiOrigin: "https://api.example.test",
    },
  }),
}));

afterEach(() => vi.unstubAllGlobals());

const photo = {
  imageId: "00000000-0000-4000-8000-000000000001",
  segment: "ROOM" as const,
  displayOrder: 0,
  contentType: "image/avif",
  byteSize: 100,
  width: 10,
  height: 10,
  url: "https://storage.example.test/expired",
  expiresAt: null,
};

it("retries photo recovery after a newer route snapshot commits", async () => {
  const stale = Promise.withResolvers<Response>();
  const fresh = Promise.withResolvers<Response>();
  const fetch = vi
    .fn()
    .mockReturnValueOnce(stale.promise)
    .mockReturnValueOnce(fresh.promise);
  vi.stubGlobal("fetch", fetch);
  const view = render(
    <ListingPhotos id="test-flat" title="Test flat" images={[photo]} />,
  );
  fireEvent.error(screen.getByRole("img"));
  fireEvent.click(screen.getByRole("button", { name: "Refresh photos" }));
  expect(fetch).toHaveBeenCalledTimes(1);

  view.rerender(
    <ListingPhotos id="test-flat" title="Test flat" images={[{ ...photo }]} />,
  );
  await act(async () =>
    stale.resolve(
      Response.json({
        images: [{ ...photo, url: "https://storage.example.test/stale" }],
      }),
    ),
  );
  expect(fetch).toHaveBeenCalledTimes(2);
  expect(screen.queryByText("No photos available")).toBeNull();

  await act(async () => fresh.resolve(Response.json({ images: [] })));
  expect(screen.getByText("No photos available")).toBeVisible();
  expect(screen.queryByRole("img")).toBeNull();
});
