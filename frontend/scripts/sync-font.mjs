import { mkdir, writeFile } from "node:fs/promises";

const response = await fetch(
  "https://fonts.googleapis.com/css2?family=Montserrat:wght@100..900&display=swap",
  {
    headers: {
      "User-Agent":
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36",
    },
  },
);
if (!response.ok) throw new Error("Font stylesheet download failed");
const css = await response.text();
const urls = [
  ...new Set(
    [...css.matchAll(/url\((https:\/\/fonts\.gstatic\.com\/[^)]+)\)/g)].map(
      (match) => match[1],
    ),
  ),
];
if (!urls.length) throw new Error("Montserrat font download is unavailable");
await mkdir("public/fonts", { recursive: true });
let localCss = css;
for (const [index, url] of urls.entries()) {
  const font = await fetch(url);
  if (!font.ok) throw new Error("Montserrat font file download failed");
  const extension = new URL(url).pathname.split(".").at(-1);
  const name = `montserrat-${index}.${extension}`;
  await writeFile(
    `public/fonts/${name}`,
    new Uint8Array(await font.arrayBuffer()),
  );
  localCss = localCss.replaceAll(url, `/fonts/${name}`);
}
await writeFile("src/app/fonts.css", localCss);
const license = await fetch(
  "https://raw.githubusercontent.com/google/fonts/main/ofl/montserrat/OFL.txt",
);
if (!license.ok) throw new Error("Montserrat license download failed");
const licenseText = (await license.text()).replace(/[ \t]+$/gm, "").trimEnd();
await writeFile("public/fonts/OFL.txt", `${licenseText}\n`);
