import { redirect } from "next/navigation";
import { errorMessage, readApi } from "@/lib/api";
import { getConfig } from "@/lib/config";
import { listingSchema, pageSchema, type ListingPage } from "@/lib/contracts";
import {
  parseSearch,
  searchFormKey,
  searchFormQuery,
  searchParamsObject,
} from "@/lib/search";
import { Pagination } from "./common";
import { ListingCard } from "./listing-card";
import { SearchListing, SearchView } from "./search-view";

export type SearchPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

export async function SearchPage({ searchParams }: SearchPageProps) {
  const config = getConfig();
  const path = `${config.basePath}/`;
  const raw = searchParamsObject(await searchParams);
  const native =
    raw.getAll(searchFormKey).length === 1 && raw.get(searchFormKey) === "1";
  const input = native ? searchFormQuery(raw) : raw;
  let query: URLSearchParams;
  try {
    query = parseSearch(input);
  } catch (error) {
    return (
      <SearchView
        key={input.toString()}
        result={null}
        query={input.toString()}
        error={
          error instanceof Error ? error.message : "Check your search link."
        }
      />
    );
  }
  if (native) redirect(`${path}?${query}`);

  let result: ListingPage | null = null;
  let message: string | undefined;
  try {
    result = await readApi(
      config.apiOrigin,
      `/api/v1/listings/search?${query}`,
      pageSchema(listingSchema),
      { credentials: "omit" },
    );
  } catch (error) {
    message = errorMessage(error);
  }
  const mapListings = result?.items.map(({ id, publicLocation }) => ({
    id,
    publicLocation,
  }));
  function href(page: number) {
    const params = new URLSearchParams(query);
    params.set("page", String(page));
    return `${path}?${params}`;
  }
  return (
    <SearchView
      key={query.toString()}
      result={
        result && {
          totalElements: result.totalElements,
          mapListings: mapListings ?? [],
        }
      }
      query={query.toString()}
      error={message}
      pagination={result && <Pagination {...result} href={href} />}
    >
      {result?.items.map((listing) => (
        <SearchListing key={listing.id} id={listing.id}>
          <ListingCard listing={listing} basePath={config.basePath} />
        </SearchListing>
      ))}
    </SearchView>
  );
}
