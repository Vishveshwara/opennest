import Link from "next/link";
import { getConfig } from "@/lib/config";
export default function NotFound() {
  return (
    <div className="container section">
      <p className="eyebrow">404 · Place not found</p>
      <h1>This page is unavailable.</h1>
      <p>The listing may be paused or the link may have changed.</p>
      <Link className="text-link" href={`${getConfig().basePath}/`}>
        Return to search
      </Link>
    </div>
  );
}
