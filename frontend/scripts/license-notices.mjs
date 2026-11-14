import { readFile, readdir, writeFile } from "node:fs/promises";
import { resolve, sep } from "node:path";

const root = resolve(".");
const lock = JSON.parse(await readFile("package-lock.json", "utf8"));
const notices = ["OpenNest third-party notices", "Installed production dependencies retain the licenses below. Montserrat's separate OFL notice is available at /fonts/OFL.txt."];
for (const [path, entry] of Object.entries(lock.packages).sort(([a], [b]) => a.localeCompare(b))) {
  if (!path.startsWith("node_modules/") || entry.dev) continue;
  const directory = resolve(path);
  if (!directory.startsWith(`${root}${sep}node_modules${sep}`)) throw new Error("Dependency license path is outside node_modules");
  let files;
  try { files = await readdir(directory); } catch { continue; }
  const pkg = JSON.parse(await readFile(resolve(directory, "package.json"), "utf8"));
  const licenses = files.filter(name => /^(licen[sc]e|copying|notice)(\.(md|txt))?$/i.test(name));
  notices.push(`\n${pkg.name} ${pkg.version}\nLicense: ${entry.license ?? pkg.license ?? "See upstream package"}`);
  for (const file of licenses) notices.push(await readFile(resolve(directory, file), "utf8"));
  if (!licenses.length) notices.push(`Upstream license reference: https://www.npmjs.com/package/${pkg.name}/v/${pkg.version}`);
}
notices.push("\nWebP codec BSD license\n", await readFile("node_modules/@jsquash/webp/codec/LICENSE.codec.md", "utf8"));
const contents = notices.join("\n\n").replace(/[ \t]+$/gm, "").trimEnd();
await writeFile("public/third-party-notices.txt", `${contents}\n`);
