import { notFound } from "next/navigation";
import { getConfig } from "@/lib/config";

export const metadata = {
  title: "Recruiter sandbox",
  robots: { index: false, follow: false },
};

export default function SandboxLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  if (getConfig().mode !== "SANDBOX") notFound();
  return children;
}
