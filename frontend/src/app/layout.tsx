import type { Metadata } from "next";
import { getConfig } from "@/lib/config";
import { SessionProvider } from "@/components/session";
import { Shell } from "@/components/shell";
import "./globals.css";

export const dynamic = "force-dynamic";
export const metadata: Metadata = {
  title: {
    default: "OpenNest | Find your next place",
    template: "%s | OpenNest",
  },
  description:
    "Find flats, rooms, and PG accommodation across India. Connect directly with owners on OpenNest.",
  applicationName: "OpenNest",
};

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const config = getConfig();
  return (
    <html lang="en" suppressHydrationWarning>
      <body>
        <SessionProvider config={config}>
          <Shell>{children}</Shell>
        </SessionProvider>
      </body>
    </html>
  );
}
