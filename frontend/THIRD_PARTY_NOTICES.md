# Third-party notices

OpenNest is AGPL-3.0-only. Dependencies retain their respective licenses, recorded in their packages and the npm lockfile dependency graph.

Each production build runs scripts/license-notices.mjs and publishes the installed production packages' LICENSE, COPYING and NOTICE files at public/third-party-notices.txt. The footer links to that artifact. Regenerate it when the dependency graph changes.

- Next.js, React, Tailwind CSS, Radix, shadcn/ui component patterns, Lucide, Zod, clsx, tailwind-merge and class-variance-authority use their upstream permissive licenses. Retain license notices when distributing their code.
- MapLibre GL JS retains its BSD-3-Clause license. MapTiler styles, tiles and data have separate service terms and require visible attribution. MapLibre does not supply unlimited map hosting.
- Montserrat is licensed under the SIL Open Font License. The downloaded OFL notice is included at public/fonts/OFL.txt and is served with the font files.
- Theme variables originate from the maintainer-specified TweakCN registry item. Its original JSON is retained at src/theme.json.

For corresponding OpenNest source, use the repository link in the application footer and the exact revision identified by the release. Phase 5 does not deploy a running release.
