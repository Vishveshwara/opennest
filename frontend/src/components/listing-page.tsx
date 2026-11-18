import { cache } from "react";
import { notFound } from "next/navigation";
import { ApiError, readApi } from "@/lib/api";
import { getConfig } from "@/lib/config";
import { listingSchema, uuid } from "@/lib/contracts";
import { Detail } from "./detail";

type ListingProps = { params: Promise<{ id: string }> };

const readListing = cache(async (id: string) => {
  if (!uuid.safeParse(id).success) notFound();
  try {
    return await readApi(
      getConfig().apiOrigin,
      `/api/v1/listings/${id}`,
      listingSchema,
      { credentials: "omit" },
    );
  } catch (error) {
    if (error instanceof ApiError && error.status === 404) notFound();
    throw error;
  }
});

export async function listingMetadata({ params }: ListingProps) {
  const { id } = await params;
  const listing = await readListing(id);
  const title = `${listing.title} in ${listing.locality}, ${listing.city}`;
  const description = listing.description
    .replace(/\s+/g, " ")
    .trim()
    .slice(0, 160);
  return { title, description };
}

export async function ListingPage({ params }: ListingProps) {
  const { id } = await params;
  return (
    <Detail listing={await readListing(id)} basePath={getConfig().basePath} />
  );
}
