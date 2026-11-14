import { notFound, redirect } from "next/navigation";
import { getConfig } from "@/lib/config";
import { readApi, errorMessage } from "@/lib/api";
import {
  listingSchema,
  pageSchema,
  uuid,
  type ListingPage,
} from "@/lib/contracts";
import { parseSearch, searchParamsObject } from "@/lib/search";
import { SearchView } from "@/components/search-view";
import { Detail } from "@/components/detail";
import { AccountView, Login, Personas } from "@/components/auth";
import { OwnerPage } from "@/components/owner";

type Props = {
  params: Promise<{ route?: string[] }>;
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};
export async function generateMetadata({ params }: Props) {
  const { route = [] } = await params;
  const config = getConfig();
  const privatePage = route.some((value) =>
    ["account", "my-listings", "login", "personas"].includes(value),
  );
  return {
    robots:
      config.mode === "SANDBOX" || privatePage
        ? { index: false, follow: false }
        : { index: true, follow: true },
  };
}

export default async function Page({ params, searchParams }: Props) {
  const { route = [] } = await params;
  const config = getConfig();
  if (config.mode === "PRIMARY" && route[0] === "recruiter-sandbox") notFound();
  if (config.mode === "SANDBOX" && route[0] !== "recruiter-sandbox") {
    if (!route.length) redirect("/recruiter-sandbox/");
    notFound();
  }
  const segments = config.mode === "SANDBOX" ? route.slice(1) : route;
  if (!segments.length) {
    const raw = searchParamsObject(await searchParams);
    let query: URLSearchParams;
    try {
      query = parseSearch(raw);
    } catch (error) {
      return (
        <SearchView
          key={raw.toString()}
          result={null}
          query={raw.toString()}
          error={
            error instanceof Error ? error.message : "Check your search link."
          }
        />
      );
    }
    let result: ListingPage | null = null,
      message: string | undefined;
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
    return (
      <SearchView
        key={query.toString()}
        result={result}
        query={query.toString()}
        error={message}
      />
    );
  }
  if (
    segments[0] === "listings" &&
    segments.length === 2 &&
    uuid.safeParse(segments[1]).success
  ) {
    const listing = await readApi(
      config.apiOrigin,
      `/api/v1/listings/${segments[1]}`,
      listingSchema,
      { credentials: "omit" },
    ).catch((error) => {
      if (
        error &&
        typeof error === "object" &&
        "status" in error &&
        error.status === 404
      )
        notFound();
      throw error;
    });
    return <Detail listing={listing} />;
  }
  if (segments[0] === "my-listings" && segments.length <= 2) {
    if (
      segments[1] &&
      segments[1] !== "new" &&
      !uuid.safeParse(segments[1]).success
    )
      notFound();
    return <OwnerPage id={segments[1]} />;
  }
  if (segments.length === 1) {
    if (segments[0] === "account") return <AccountView />;
    if (segments[0] === "login" && config.mode === "PRIMARY") return <Login />;
    if (segments[0] === "personas" && config.mode === "SANDBOX")
      return <Personas />;
  }
  notFound();
}
