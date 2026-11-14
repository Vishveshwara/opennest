import type { NextConfig } from "next";
import { getConfig, getDevelopmentLanOrigins } from "./src/lib/config";

const config: NextConfig = {
  distDir:
    process.env.OPENNEST_VERIFY_BUILD === "true"
      ? ".next-verification"
      : ".next",
  cacheComponents: false,
  reactCompiler: false,
  poweredByHeader: false,
  images: { unoptimized: true },
  allowedDevOrigins: getDevelopmentLanOrigins().map(
    (origin) => new URL(origin).hostname,
  ),
  async rewrites() {
    const { apiOrigin, browserApiOrigin } = getConfig();
    return {
      beforeFiles:
        browserApiOrigin === ""
          ? [{ source: "/api/v1/:path*", destination: `${apiOrigin}/api/v1/:path*` }]
          : [],
    };
  },
  async headers() {
    return [
      {
        source: "/:path*",
        headers: [
          { key: "Cache-Control", value: "no-store" },
          { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
          { key: "X-Content-Type-Options", value: "nosniff" },
          {
            key: "Permissions-Policy",
            value: "camera=(), microphone=(), geolocation=(self)",
          },
        ],
      },
    ];
  },
};

export default config;
