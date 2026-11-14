import { describe, expect, it } from "vitest";
import { compatibleSearch, parseSearch, safeReturnPath } from "./search";

describe("shareable nationwide searches", () => {
  it("starts nationwide with explicit pagination and sorting", () => {
    const query = parseSearch(new URLSearchParams());
    expect(Object.fromEntries(query)).toEqual({
      page: "0",
      size: "20",
      sort: "NEWEST",
    });
    expect(query.has("city")).toBe(false);
  });
  it.each([
    "city=Pune&city=Delhi",
    "city=",
    "city=%20",
    "unknown=x",
    "sort=monthly_rent",
    "page=-1",
    "size=51",
    "page=501&size=20",
    "minRent=9&maxRent=1",
    "lat=20",
    "lat=20&lng=80&radiusKm=0",
    "sort=DISTANCE_ASC",
    "category=PG_HOSTEL&minBedrooms=1",
    "flatRentalMode=ENTIRE_FLAT&maxSharingPerRoom=2",
    "maxSharingPerRoom=0",
    "hasWifi=1",
  ])("rejects invalid links instead of silently falling back: %s", (input) =>
    expect(() => parseSearch(new URLSearchParams(input))).toThrow(),
  );
  it("retains amenity false, studio, and full nearby filters", () => {
    const query = parseSearch(
      new URLSearchParams(
        "hasWifi=false&minBedrooms=0&maxBedrooms=0&lat=18.5&lng=73.8&radiusKm=10&sort=DISTANCE_ASC&page=500&size=20",
      ),
    );
    expect(query.get("hasWifi")).toBe("false");
    expect(query.get("minBedrooms")).toBe("0");
    expect(query.get("page")).toBe("500");
  });
  it("clears incompatible fields and resets pagination after mode changes", () => {
    const query = compatibleSearch(
      new URLSearchParams(
        "category=FLAT&flatRentalMode=ENTIRE_FLAT&minTotalRooms=1&genderRoomAvailable=MALE&maxSharingPerRoom=2&page=4",
      ),
    );
    expect(query.has("minTotalRooms")).toBe(false);
    expect(query.has("genderRoomAvailable")).toBe(false);
    expect(query.has("maxSharingPerRoom")).toBe(false);
    expect(query.get("page")).toBe("0");
  });
  it("clears containing-flat fields when changing to PG", () => {
    const query = compatibleSearch(
      new URLSearchParams(
        "category=PG_HOSTEL&flatRentalMode=ROOM&minBedrooms=2&maxBedrooms=3",
      ),
    );
    expect(query.has("flatRentalMode")).toBe(false);
    expect(query.has("minBedrooms")).toBe(false);
  });
});

describe("login returns", () => {
  it.each([
    "//external.example",
    "https://external.example",
    "/\\evil",
    "/path\n",
  ])("rejects external or ambiguous destinations %s", (value) =>
    expect(safeReturnPath(value)).toBe("/"),
  );
  it("keeps local search parameters", () =>
    expect(safeReturnPath("/my-listings?page=2")).toBe("/my-listings?page=2"));
  it("confines sandbox destinations", () =>
    expect(safeReturnPath("/account", "/recruiter-sandbox")).toBe(
      "/recruiter-sandbox/",
    ));
});
