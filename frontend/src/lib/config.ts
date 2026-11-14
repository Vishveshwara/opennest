import { z } from "zod";

const origin = z
  .string()
  .url()
  .refine((value) => {
    const url = new URL(value);
    return (
      ["http:", "https:"].includes(url.protocol) &&
      url.pathname === "/" &&
      !url.search &&
      !url.hash &&
      !url.username &&
      !url.password
    );
  });

export function getConfig() {
  const mode = z
    .enum(["PRIMARY", "SANDBOX"])
    .parse(process.env.OPENNEST_UI_MODE ?? "PRIMARY");
  const apiOrigin = origin.parse(
    process.env.OPENNEST_API_ORIGIN ??
      (mode === "PRIMARY" ? "http://localhost:8080" : "http://localhost:8081"),
  );
  const siteOrigin = origin.parse(
    process.env.OPENNEST_SITE_ORIGIN ??
      (mode === "PRIMARY" ? "http://localhost:3000" : "http://localhost:3001"),
  );
  const lanOrigins = getDevelopmentLanOrigins();
  if (
    lanOrigins.length &&
    !["localhost", "127.0.0.1", "[::1]"].includes(new URL(apiOrigin).hostname)
  )
    throw new Error("OpenNest development LAN access requires a loopback API.");
  if (
    new URL(siteOrigin).protocol === "https:" &&
    new URL(apiOrigin).protocol !== "https:"
  )
    throw new Error("OpenNest HTTPS requires an HTTPS API origin.");
  const revision = process.env.OPENNEST_SOURCE_REVISION || "";
  if (revision)
    z.string()
      .regex(/^[a-f0-9]{40}$/)
      .parse(revision);
  const repository = "https://github.com/Vishveshwara/opennest";
  return {
    mode,
    apiOrigin,
    browserApiOrigin: lanOrigins.length ? "" : apiOrigin,
    siteOrigin,
    mapKey: process.env.OPENNEST_MAPTILER_KEY ?? "",
    basePath: mode === "SANDBOX" ? "/recruiter-sandbox" : "",
    sourceUrl: revision ? `${repository}/tree/${revision}` : repository,
    licenseUrl: `${repository}/blob/${revision || "main"}/LICENSE`,
  };
}

export function getDevelopmentLanOrigins(): string[] {
  const configured = process.env.OPENNEST_DEV_LAN_ORIGINS?.trim() ?? "";
  if (!configured) return [];
  if (
    process.env.NODE_ENV === "production" ||
    (process.env.OPENNEST_UI_MODE ?? "PRIMARY") !== "PRIMARY"
  )
    throw new Error("OpenNest development LAN access is unavailable in this mode.");
  return z
    .array(
      origin.refine((value) => {
        const url = new URL(value);
        const octets = url.hostname.split(".").map(Number);
        return (
          url.protocol === "http:" &&
          url.origin === value &&
          octets.length === 4 &&
          octets.every((part) => Number.isInteger(part) && part >= 0 && part <= 255) &&
          (octets[0] === 10 ||
            (octets[0] === 192 && octets[1] === 168) ||
            (octets[0] === 172 && octets[1] >= 16 && octets[1] <= 31))
        );
      }),
    )
    .max(8)
    .parse(configured.split(",").map((value) => value.trim()));
}

export type AppConfig = ReturnType<typeof getConfig>;
