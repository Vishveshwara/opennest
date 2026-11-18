import { notFound } from "next/navigation";
import { uuid } from "@/lib/contracts";
import { AccountView } from "./auth";
import { OwnerPage } from "./owner";

const robots = { index: false, follow: false };

export const accountMetadata = { title: "My account", robots };
export const AccountPage = AccountView;

type ManageProps = { params: Promise<{ manage?: string[] }> };

export async function manageMetadata({ params }: ManageProps) {
  const { manage } = await params;
  const title =
    manage?.[0] === "new"
      ? "Create a listing draft"
      : manage?.length
        ? "Manage listing"
        : "My listings";
  return { title, robots };
}

export async function ManagePage({ params }: ManageProps) {
  const { manage = [] } = await params;
  const [id] = manage;
  if (manage.length > 1 || (id && id !== "new" && !uuid.safeParse(id).success))
    notFound();
  return <OwnerPage id={id} />;
}
