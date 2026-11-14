import { mkdir, writeFile } from "node:fs/promises";

const response = await fetch(
  "https://tweakcn.com/r/themes/cmv1h5ahl000104kwff88a0p8",
);
if (!response.ok) throw new Error("Theme download failed");
const theme = await response.json();
if (!theme.cssVars?.light || !theme.cssVars?.dark || !theme.cssVars?.theme)
  throw new Error("Theme variables missing");
const variables = (values) =>
  Object.entries(values)
    .map(([name, value]) => `  --${name}: ${value};`)
    .join("\n");
await mkdir("src/app", { recursive: true });
await writeFile(
  "src/app/theme.css",
  `:root {\n${variables({ ...theme.cssVars.theme, ...theme.cssVars.light })}\n}\n.dark {\n${variables(theme.cssVars.dark)}\n}\n@layer base { body { letter-spacing: var(--tracking-normal); } }\n`,
);
await writeFile("src/theme.json", `${JSON.stringify(theme, null, 2)}\n`);
