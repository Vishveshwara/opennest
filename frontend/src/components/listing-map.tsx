"use client";

import dynamic from "next/dynamic";
import { useState } from "react";
import type { Listing } from "@/lib/contracts";
import { Notice } from "./common";
import { useSession } from "./session";
import { Button } from "./ui/button";

const AreaMap = dynamic(() => import("./area-map"), {
  loading: () => <p role="status">Loading map…</p>,
});

export function ListingMap({
  listing,
}: {
  listing: Pick<Listing, "id" | "publicLocation">;
}) {
  const [open, setOpen] = useState(false);
  const { config } = useSession();
  if (!config.mapKey) return <Notice>Map preview is unavailable.</Notice>;
  return (
    <>
      <Button
        type="button"
        variant="outline"
        aria-expanded={open}
        aria-controls="listing-area-map"
        onClick={() => setOpen(!open)}
      >
        {open ? "Hide approximate map" : "Show approximate map"}
      </Button>
      <div id="listing-area-map" hidden={!open}>
        {open && <AreaMap listings={[listing]} />}
      </div>
    </>
  );
}
