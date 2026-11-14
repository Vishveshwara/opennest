import { z } from "zod";

export const sorts = [
  "NEWEST",
  "RENT_ASC",
  "RENT_DESC",
  "ROOMS_DESC",
  "RATING_DESC",
  "DISTANCE_ASC",
];
export const searchKeys = [
  "city",
  "locality",
  "category",
  "flatRentalMode",
  "status",
  "minRent",
  "maxRent",
  "minBedrooms",
  "maxBedrooms",
  "minTotalRooms",
  "maxTotalRooms",
  "hasWifi",
  "hasGym",
  "hasSwimmingPool",
  "maxSharingPerRoom",
  "genderRoomAvailable",
  "lat",
  "lng",
  "radiusKm",
  "sort",
  "page",
  "size",
];
const integerKeys = [
  "minRent",
  "maxRent",
  "minBedrooms",
  "maxBedrooms",
  "minTotalRooms",
  "maxTotalRooms",
  "page",
  "size",
  "maxSharingPerRoom",
];
const enums: Record<string, string[]> = {
  category: ["FLAT", "PG_HOSTEL"],
  flatRentalMode: ["ENTIRE_FLAT", "ROOM"],
  status: ["ACTIVE", "FULL_OCCUPIED"],
  genderRoomAvailable: ["MALE", "FEMALE", "SHARED"],
  sort: sorts,
  hasWifi: ["true", "false"],
  hasGym: ["true", "false"],
  hasSwimmingPool: ["true", "false"],
};

export function parseSearch(params: URLSearchParams) {
  const query = new URLSearchParams(params);
  for (const [key, value] of query) {
    if (
      !searchKeys.includes(key) ||
      query.getAll(key).length !== 1 ||
      !value.trim()
    )
      throw new Error(
        "The search link contains unsupported, repeated, or empty filters.",
      );
    if (integerKeys.includes(key) && !/^\d+$/.test(value))
      throw new Error(
        "Use whole numbers for room, rent, and pagination filters.",
      );
    if (enums[key] && !enums[key].includes(value))
      throw new Error("The search link contains an unsupported filter value.");
    if (
      ["city", "locality"].includes(key) &&
      (value.length > 120 || value.includes("\0"))
    )
      throw new Error("Enter a city or locality of up to 120 characters.");
    if (
      integerKeys.includes(key) &&
      !z.number().int().min(0).max(2147483647).safeParse(Number(value)).success
    )
      throw new Error("A numeric filter is outside its supported range.");
  }
  const page = Number(query.get("page") ?? 0),
    size = Number(query.get("size") ?? 20);
  if (size < 1 || size > 50 || page * size > 10000)
    throw new Error(
      "Narrow the search. This page or page size exceeds the browsing limit.",
    );
  for (const [min, max] of [
    ["minRent", "maxRent"],
    ["minBedrooms", "maxBedrooms"],
    ["minTotalRooms", "maxTotalRooms"],
  ]) {
    if (
      query.has(min) &&
      query.has(max) &&
      Number(query.get(min)) > Number(query.get(max))
    )
      throw new Error("Minimum values must not exceed maximum values.");
  }
  const sharing = query.get("maxSharingPerRoom");
  if (sharing && (Number(sharing) < 1 || Number(sharing) > 10))
    throw new Error("Sharing must be between 1 and 10 occupants per room.");
  const geo = ["lat", "lng", "radiusKm"].filter((key) => query.has(key));
  if (geo.length && geo.length !== 3)
    throw new Error("Nearby search needs latitude, longitude, and a radius.");
  if (geo.length) {
    const lat = Number(query.get("lat")),
      lng = Number(query.get("lng")),
      radius = Number(query.get("radiusKm"));
    if (
      ![lat, lng, radius].every(Number.isFinite) ||
      lat < 6.5 ||
      lat > 37.5 ||
      lng < 68 ||
      lng > 97.5 ||
      radius <= 0 ||
      radius > 100
    )
      throw new Error(
        "Check the nearby coordinates and use a radius above 0 and up to 100 km.",
      );
  }
  if (query.get("sort") === "DISTANCE_ASC" && geo.length !== 3)
    throw new Error("Nearest sorting requires a complete nearby search.");
  const category = query.get("category"),
    mode = query.get("flatRentalMode");
  if (
    category === "PG_HOSTEL" &&
    (mode || query.has("minBedrooms") || query.has("maxBedrooms"))
  )
    throw new Error("PG listings do not support flat-mode or BHK filters.");
  if (
    mode === "ENTIRE_FLAT" &&
    [
      "minTotalRooms",
      "maxTotalRooms",
      "genderRoomAvailable",
      "maxSharingPerRoom",
    ].some((key) => query.has(key))
  )
    throw new Error(
      "Entire flats use whole-flat availability, not room vacancy filters.",
    );
  query.set("page", String(page));
  query.set("size", String(size));
  if (!query.has("sort")) query.set("sort", "NEWEST");
  return query;
}

export function compatibleSearch(params: URLSearchParams) {
  const query = new URLSearchParams(params);
  if (query.get("category") === "PG_HOSTEL")
    ["flatRentalMode", "minBedrooms", "maxBedrooms"].forEach((key) =>
      query.delete(key),
    );
  if (query.get("flatRentalMode") === "ENTIRE_FLAT")
    [
      "minTotalRooms",
      "maxTotalRooms",
      "genderRoomAvailable",
      "maxSharingPerRoom",
    ].forEach((key) => query.delete(key));
  query.set("page", "0");
  return query;
}

export function searchParamsObject(
  values: Record<string, string | string[] | undefined>,
) {
  const params = new URLSearchParams();
  for (const [key, value] of Object.entries(values)) {
    if (Array.isArray(value)) value.forEach((item) => params.append(key, item));
    else if (value !== undefined) params.set(key, value);
  }
  return params;
}

export function safeReturnPath(value: string | null, basePath = "") {
  if (
    !value ||
    !value.startsWith("/") ||
    value.startsWith("//") ||
    /[\\\u0000-\u0020]/.test(value)
  )
    return `${basePath}/`;
  try {
    const url = new URL(value, "https://opennest.co.in");
    if (
      url.origin !== "https://opennest.co.in" ||
      (basePath && !url.pathname.startsWith(`${basePath}/`))
    )
      return `${basePath}/`;
    return url.pathname + url.search;
  } catch {
    return `${basePath}/`;
  }
}
