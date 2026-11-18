import { redirect } from "next/navigation";
import { getConfig } from "@/lib/config";
import { SearchPage, type SearchPageProps } from "@/components/search-page";

export default function Page(props: SearchPageProps) {
  if (getConfig().mode === "SANDBOX") redirect("/recruiter-sandbox/");
  return <SearchPage {...props} />;
}
