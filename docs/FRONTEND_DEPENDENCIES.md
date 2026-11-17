# OpenNest frontend dependencies

Phase 5 dependency selection was checked on 11 November 2026 before initialization. The local runtime is Node 24.14.0 with npm 11.9.0. The backend remains Java 25, Spring Boot 4.1.1 and Spring Modulith 2.1.1.

The frontend pins Next.js 16.4.6, React/react-dom 19.3.1 and TypeScript 5.9.3. npm registry metadata confirms Next.js requires Node >=20.9.0 and accepts React ^19.0.0. Node 24 satisfies that runtime requirement. Official release records cover [Next.js 16.4](https://nextjs.org/blog/next-16-4) and [React 19.3](https://react.dev/blog/2026/09/09/react-19-3). No alternate major or release line was substituted.

Run these read-only probes to recheck availability:

```powershell
npm view next@16.4.6 version engines peerDependencies --json
npm view react@19.3.1 version --json
npm view react-dom@19.3.1 version peerDependencies --json
npm view typescript@5.9.3 version --json
node --version
npm --version
```

The npm lockfile records exact transitive resolutions. Tailwind 4, Zod 4, MapLibre, Radix, Lucide, Vitest and Playwright selections are pinned in frontend/package.json. Cache Components and React Compiler remain disabled. The 17 November image follow-up replaces @jsquash/webp with @jsquash/avif 2.1.1. Dev/build copy the single-threaded encoder WASM and codec notices into public/codecs/avif; no CDN or cross-origin isolation is required. A dedicated worker applies orientation, downsizes to a 2,048-pixel longest side without upscaling, flattens transparency onto white and encodes lossy 8-bit AVIF at quality 60/speed 8. Above the soft 1 MB target it tries quality 45 once and keeps the smaller output; converted files are uploaded without a product byte limit. Source decoding still uses device memory and browser color conversion. Backend JavaCV/FFmpeg dependencies provide independent validation; see [media dependencies and limits](LISTINGS_HANDOFF.md). Upstream: [jSquash source](https://github.com/jamsinclair/jSquash), [AVIF package](https://www.npmjs.com/package/@jsquash/avif/v/2.1.1).

The development-only TypeScript ESLint dependency receives a scoped minimatch 11.0.1 override to address [GHSA-g3hv-75wg-m249](https://github.com/advisories/GHSA-g3hv-75wg-m249). Lint/type/test/build checks must pass with that override. npm audit reported zero vulnerabilities after selection; repeat the audit when dependencies change.

The supplied TweakCN registry item remains the source of theme values. The theme synchronization script validates the expected variable groups and writes the original JSON alongside CSS. Montserrat files and their OFL license are served locally, so builds and visitors do not need Google Fonts requests.
