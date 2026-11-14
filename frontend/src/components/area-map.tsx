"use client";

import { useEffect, useRef, useState } from "react";
import type { Map as MapInstance } from "maplibre-gl";
import type { Listing } from "@/lib/contracts";
import { z } from "zod";
import { useSession } from "./session";
import { Notice } from "./common";
import "maplibre-gl/dist/maplibre-gl.css";

type Point = { latitude: number; longitude: number };
type MapProps = {
  listings?: Listing[];
  onSelect?: (id: string) => void;
  selectedId?: string;
  point?: Point;
  onPoint?: (point: Point) => void;
  pointHint?: string;
};

export default function AreaMap({
  listings = [],
  onSelect,
  selectedId = "",
  point,
  onPoint,
  pointHint = "Select a point or enter coordinates. Location is private to the owner.",
}: MapProps) {
  const { config } = useSession();
  const node = useRef<HTMLDivElement>(null),
    instance = useRef<MapInstance | null>(null);
  const selection = useRef(selectedId);
  useEffect(() => {
    selection.current = selectedId;
    const map = instance.current;
    if (map?.getLayer("selected-area"))
      map.setFilter("selected-area", ["==", ["get", "id"], selectedId]);
  }, [selectedId]);
  const [failed, setFailed] = useState(false),
    [ready, setReady] = useState(false);
  const data = JSON.stringify(
    listings.map((item) => ({ id: item.id, ...item.publicLocation })),
  );
  const pointLat = point?.latitude,
    pointLng = point?.longitude;
  useEffect(() => {
    if (!config.mapKey || !node.current) return;
    let cancelled = false;
    const element = node.current;
    void import("maplibre-gl")
      .then((maplibre) => {
        if (cancelled) return;
        maplibre.setWorkerUrl(
          new URL(
            "maplibre-gl/dist/maplibre-gl-worker.mjs",
            import.meta.url,
          ).toString(),
        );
        const parsedItems = z
          .array(
            z.object({
              id: z.string(),
              latitude: z.number(),
              longitude: z.number(),
              radiusMeters: z.number(),
            }),
          )
          .parse(JSON.parse(data));
        const map = new maplibre.Map({
          container: element,
          style: `https://api.maptiler.com/maps/streets-v2/style.json?key=${encodeURIComponent(config.mapKey)}`,
          bounds: [
            [68, 6.5],
            [97.5, 37.5],
          ],
          fitBoundsOptions: { padding: 32 },
          attributionControl: {},
        });
        instance.current = map;
        map.addControl(new maplibre.NavigationControl(), "top-right");
        map.on("error", () => {
          if (!cancelled) setFailed(true);
        });
        map.on("load", () => {
          if (cancelled) return;
          const foreground = getComputedStyle(document.documentElement)
            .getPropertyValue("--primary")
            .trim();
          const canvas = document.createElement("canvas"),
            context = canvas.getContext("2d");
          if (!context) {
            setFailed(true);
            return;
          }
          context.fillStyle = foreground;
          context.fillRect(0, 0, 1, 1);
          const [red, green, blue] = context.getImageData(0, 0, 1, 1).data;
          const color = `rgb(${red}, ${green}, ${blue})`;
          const features = parsedItems.map((item) => {
            const coordinates = Array.from({ length: 41 }, (_, index) => {
              const angle = (index / 40) * Math.PI * 2;
              return [
                item.longitude +
                  (Math.cos(angle) * item.radiusMeters) /
                    (111320 * Math.cos((item.latitude * Math.PI) / 180)),
                item.latitude + (Math.sin(angle) * item.radiusMeters) / 111320,
              ];
            });
            return {
              type: "Feature",
              properties: { id: item.id },
              geometry: { type: "Polygon", coordinates: [coordinates] },
            } satisfies GeoJSON.Feature<GeoJSON.Polygon>;
          });
          map.addSource("areas", {
            type: "geojson",
            data: { type: "FeatureCollection", features },
          });
          map.addLayer({
            id: "areas",
            type: "fill",
            source: "areas",
            paint: {
              "fill-color": color,
              "fill-opacity": 0.18,
              "fill-outline-color": color,
            },
          });
          map.addLayer({
            id: "selected-area",
            type: "line",
            source: "areas",
            filter: ["==", ["get", "id"], selection.current],
            paint: { "line-color": color, "line-width": 3 },
          });
          map.on("click", "areas", (event) => {
            const id: unknown = event.features?.[0]?.properties.id;
            if (typeof id === "string") onSelect?.(id);
          });
          const bounds = new maplibre.LngLatBounds();
          parsedItems.forEach((item) => {
            bounds.extend([item.longitude - 0.025, item.latitude - 0.025]);
            bounds.extend([item.longitude + 0.025, item.latitude + 0.025]);
            new maplibre.Marker({ color })
              .setLngLat([item.longitude, item.latitude])
              .setPopup(new maplibre.Popup().setText("Approximate area"))
              .addTo(map);
          });
          if (pointLat !== undefined && pointLng !== undefined) {
            bounds.extend([pointLng, pointLat]);
            new maplibre.Marker({ color })
              .setLngLat([pointLng, pointLat])
              .addTo(map);
          }
          if (!bounds.isEmpty())
            map.fitBounds(bounds, {
              padding: 36,
              maxZoom: onPoint ? 13 : 11,
              duration: 0,
            });
          setReady(true);
        });
        if (onPoint)
          map.on("click", (event) =>
            onPoint({
              latitude: Number(event.lngLat.lat.toFixed(6)),
              longitude: Number(event.lngLat.lng.toFixed(6)),
            }),
          );
      })
      .catch(() => {
        if (!cancelled) setFailed(true);
      });
    return () => {
      cancelled = true;
      instance.current?.remove();
      instance.current = null;
    };
  }, [config.mapKey, data, onSelect, onPoint, pointLat, pointLng]);
  if (!config.mapKey)
    return (
      <div className="map-unavailable">
        <Notice>
          Map preview is unavailable. Browse the listings and locality
          information below.
        </Notice>
      </div>
    );
  return (
    <div className="map-wrap">
      <div
        ref={node}
        className="area-map"
        role="region"
        aria-label={
          onPoint
            ? "Choose a location on the map, or use the coordinate fields"
            : "Approximate listing areas, with equivalent listing links below"
        }
      />
      <span className="sr-only" role="status">
        {failed
          ? "Map unavailable"
          : ready
            ? "Location map ready"
            : "Loading map…"}
      </span>
      {failed && (
        <Notice>
          Map tiles could not load. Listings and coordinate fields remain
          available.
        </Notice>
      )}
      <p className="map-caption">
        {onPoint
          ? pointHint
          : "Approximate areas, not building locations. Showing this page of results."}
      </p>
    </div>
  );
}
