export const MAX_SOURCE_BYTES = 40_000_000;
export const MAX_LISTING_PHOTOS = 20;
export const MAX_PHOTO_EDGE = 2_048;
export const TARGET_PHOTO_BYTES = 1_000_000;

export function photoDimensions(width: number, height: number) {
  if (!Number.isSafeInteger(width) || !Number.isSafeInteger(height) || width < 1 || height < 1)
    throw new Error("The photo dimensions are invalid.");
  const scale = Math.min(1, MAX_PHOTO_EDGE / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

export function validateSource(file: File) {
  if (!["image/jpeg", "image/png", "image/webp", "image/avif"].includes(file.type))
    throw new Error("Choose a JPEG, PNG, WebP, or AVIF photo.");
  if (!file.size || file.size > MAX_SOURCE_BYTES)
    throw new Error("Choose a photo of 40 MB or less.");
}
