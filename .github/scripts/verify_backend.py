import argparse
import hashlib
import json
import re
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
BACKEND = ROOT / "backend"
UNIT_SUITES = {
    "in.opennest.ModuleStructureTests",
    "in.opennest.OpenNestApplicationTests",
    "in.opennest.ProductionConfigurationTests",
    "in.opennest.SandboxConfigurationTests",
    "in.opennest.common.internal.configuration.DeploymentEnvironmentPostProcessorTests",
    "in.opennest.common.internal.web.HttpErrorContractTests",
    "in.opennest.common.internal.web.LiveHttpErrorTests",
    "in.opennest.identity.TrustBadgeTests",
    "in.opennest.identity.internal.configuration.IdentitySettingsTests",
}
INTEGRATION_SUITES = {"in.opennest.SchemaMigrationIT", "in.opennest.BackendIsolationIT", "in.opennest.IdentityAuthenticationIT"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_inputs():
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(BACKEND / "pom.xml").getroot()
    require(pom.findtext("m:properties/m:java.version", namespaces=namespace) == "25", "Java target must remain 25")
    properties = dict(line.split("=", 1) for line in (BACKEND / ".mvn/wrapper/maven-wrapper.properties")
                      .read_text().splitlines() if line and not line.startswith("#"))
    manifest = json.loads((BACKEND / ".mvn/wrapper/checksums.json").read_text())
    require(properties["wrapperVersion"] == manifest["wrapperVersion"], "Wrapper version differs from verified manifest")
    require(properties["distributionUrl"] == manifest["distributionUrl"], "Maven URL differs from verified manifest")
    require(properties["distributionSha256Sum"] == manifest["distributionSha256Sum"], "Maven checksum differs from verified manifest")
    for name, expected in manifest["scriptsSha256Lf"].items():
        actual = hashlib.sha256((BACKEND / name).read_bytes().replace(b"\r\n", b"\n")).hexdigest()
        require(actual == expected, f"Wrapper script differs from verified Apache source: {name}")
    for path in (ROOT / "docs/ai-context").glob("*.md"):
        lines = path.read_text(encoding="utf-8").splitlines()
        require(len(lines) < 80, f"Context index must stay below 80 lines: {path.name}")
        require(all(not line.strip() or line.startswith("- ") for line in lines), f"Context index must use bullets: {path.name}")
    for path in (BACKEND / "src/main/java").rglob("MODULE_CONTEXT.md"):
        require(len(path.read_text(encoding="utf-8").splitlines()) < 50, f"Module context must stay below 50 lines: {path}")
    print("Verified Java 25 target, Apache Maven Wrapper scripts/distribution pin, and context limits.")


def read_reports(directory, expected):
    suites = {}
    for path in sorted(directory.glob("TEST-*.xml")):
        suite = ET.parse(path).getroot()
        name = suite.attrib["name"]
        require(name not in suites, f"Duplicate test suite report: {name}")
        suites[name] = {key: int(suite.get(key, "0")) for key in ("tests", "failures", "errors", "skipped")}
    require(expected <= suites.keys(), f"Missing test suites: {', '.join(sorted(expected - suites.keys()))}")
    for name, counts in suites.items():
        require(counts["tests"] > 0, f"No tests executed in {name}")
        require(not any(counts[key] for key in ("failures", "errors", "skipped")), f"Unsuccessful suite {name}: {counts}")
    return suites


def verify_reports():
    target = BACKEND / "target"
    unit = read_reports(target / "surefire-reports", UNIT_SUITES)
    integration = read_reports(target / "failsafe-reports", INTEGRATION_SUITES)
    artifact = target / "opennest-backend-0.0.1-SNAPSHOT.jar"
    with zipfile.ZipFile(artifact) as jar:
        application_classes = [name for name in jar.namelist() if name.startswith("BOOT-INF/classes/")]
        require(not any(token in name for name in application_classes for token in
                        ("IsolationProbe", "IsolationTestConfiguration", "BackendIsolationIT", "HttpContractFixtures",
                         "SchemaMigrationIT", "PersistenceScenarios", "PersistenceScenarioSupport",
                         "IdentityAuthenticationIT", "ControlledOidcProvider", "ProviderConfiguration")), "Test fixture packaged in application")
        migrations = [name for name in application_classes if name.startswith("BOOT-INF/classes/db/migration/V")]
        require(set(migrations) == {
            "BOOT-INF/classes/db/migration/V1__create_application_schema.sql",
            "BOOT-INF/classes/db/migration/V2__create_application_indexes.sql",
        }, "Packaged migrations differ from the Phase 1 handoff")
    version = subprocess.run(["java", "--version"], capture_output=True, text=True, check=True).stdout.strip()
    require(re.search(r"(?:openjdk|java) 25[.\s]", version), "Verification must run on Java 25")
    result = {"java": version, "unit": unit, "integration": integration,
              "totalTests": sum(counts["tests"] for counts in [*unit.values(), *integration.values()]),
              "packagedTestFixtures": False}
    (target / "backend-verification.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {result['totalTests']} tests with no failures, errors, or skips; packaged boundaries pass.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="Verify the OpenNest Phase 1 build and handoff gates.")
    parser.add_argument("--reports", action="store_true", help="Require both integration suites and inspect the built application")
    args = parser.parse_args()
    try:
        verify_inputs()
        if args.reports:
            verify_reports()
    except (ValueError, KeyError, OSError, ET.ParseError, zipfile.BadZipFile, subprocess.CalledProcessError) as failure:
        print(f"Backend verification failed: {failure}", file=sys.stderr)
        sys.exit(1)
