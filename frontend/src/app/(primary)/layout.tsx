import { notFound } from "next/navigation";
import { getConfig } from "@/lib/config";

export default function PrimaryLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  if (getConfig().mode !== "PRIMARY") notFound();
  return children;
}
