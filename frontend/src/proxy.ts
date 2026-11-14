import { NextResponse, type NextRequest } from "next/server";
import { getConfig } from "./lib/config";

export function proxy(request: NextRequest) {
  const { mode } = getConfig();
  const path = request.nextUrl.pathname;
  if (
    mode === "PRIMARY" &&
    (path === "/recruiter-sandbox" || path.startsWith("/recruiter-sandbox/"))
  )
    return new NextResponse("OpenNest page unavailable", {
      status: 404,
      headers: {
        "Cache-Control": "no-store",
        "X-Content-Type-Options": "nosniff",
      },
    });
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|fonts|favicon.ico).*)"],
};
