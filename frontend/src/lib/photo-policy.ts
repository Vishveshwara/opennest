export const MAX_SOURCE_BYTES = 40_000_000;
export const MAX_LISTING_PHOTOS = 20;
export const WEBP_MAX_DIMENSION = 16_383;

export function validateSource(file: File) {
  if (!["image/jpeg", "image/png", "image/webp"].includes(file.type))
    throw new Error("Choose a JPEG, PNG, or WebP photo.");
  if (!file.size || file.size > MAX_SOURCE_BYTES)
    throw new Error("Choose a photo of 40 MB or less.");
}
