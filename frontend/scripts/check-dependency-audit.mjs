import { spawnSync } from "node:child_process";
import { readFileSync } from "node:fs";

const advisoryUrl = "https://github.com/advisories/GHSA-fc5q-39xc-vjpf";
const audit = spawnSync(
  process.execPath,
  [process.env.npm_execpath, "audit", "--include=dev", "--audit-level=moderate", "--json"],
  { encoding: "utf8", maxBuffer: 4 * 1024 * 1024 },
);
if (audit.error) throw audit.error;
if (audit.stderr) process.stderr.write(audit.stderr);

const report = JSON.parse(audit.stdout);
const findings = report.vulnerabilities;
if (
  ![0, 1].includes(audit.status) ||
  report.error ||
  report.auditReportVersion !== 2 ||
  !findings ||
  typeof findings !== "object" ||
  Array.isArray(findings)
) {
  throw new Error("npm audit did not return a valid report");
}

const { packages } = JSON.parse(
  readFileSync(new URL("../package-lock.json", import.meta.url), "utf8"),
);

function isKnownDevelopmentFinding(name, visited = new Set()) {
  const finding = findings[name];
  if (
    visited.has(name) ||
    !finding?.nodes?.length ||
    !finding.nodes.every((path) => packages[path]?.dev === true) ||
    !finding.via?.length
  ) {
    return false;
  }

  const next = new Set([...visited, name]);
  return finding.via.every((cause) =>
    typeof cause === "string"
      ? isKnownDevelopmentFinding(cause, next)
      : name === "picomatch" && cause?.url === advisoryUrl,
  );
}

const blocking = Object.entries(findings).filter(
  ([, finding]) => !["info", "low"].includes(finding.severity),
);
if (blocking.some(([name]) => !isKnownDevelopmentFinding(name)) || (audit.status === 1 && !blocking.length)) {
  console.error(audit.stdout);
  process.exitCode = 1;
} else if (blocking.length) {
  console.warn(`Known development-only audit advisory: ${advisoryUrl}`);
  console.warn(`Affected tooling packages: ${blocking.map(([name]) => name).sort().join(", ")}`);
} else {
  console.log("npm audit found no moderate or higher vulnerabilities.");
}
