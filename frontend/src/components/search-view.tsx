"use client";

import { useCallback, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import {
  ArrowRight,
  MapPin,
  Search,
  SlidersHorizontal,
  LocateFixed,
  ShieldCheck,
  Users,
  IndianRupee,
  Map as MapIcon,
  LayoutGrid,
} from "lucide-react";
import { compatibleSearch, parseSearch } from "@/lib/search";
import type { ListingPage } from "@/lib/contracts";
import { useSession } from "./session";
import { Button } from "./ui/button";
import { ListingCard, Notice, Pagination } from "./common";
import AreaMap from "./area-map";

const sortLabels = [
  ["NEWEST", "Newest first"],
  ["RENT_ASC", "Quoted monthly rent: low to high"],
  ["RENT_DESC", "Quoted monthly rent: high to low"],
  ["ROOMS_DESC", "Most rooms available"],
  ["RATING_DESC", "Highest owner rating"],
  ["DISTANCE_ASC", "Nearest first"],
];
export function SearchView({
  result,
  query,
  error,
}: {
  result: ListingPage | null;
  query: string;
  error?: string;
}) {
  const { config } = useSession();
  const router = useRouter();
  const [advanced, setAdvanced] = useState(false),
    [map, setMap] = useState(false),
    [selected, setSelected] = useState(""),
    [notice, setNotice] = useState("");
  const initial = new URLSearchParams(query);
  const [category, setCategory] = useState(initial.get("category") ?? ""),
    [mode, setMode] = useState(initial.get("flatRentalMode") ?? "");
  const [geo, setGeo] = useState({
    lat: initial.get("lat") ?? "",
    lng: initial.get("lng") ?? "",
    radiusKm: initial.get("radiusKm") ?? "",
  });
  const [picker, setPicker] = useState(false);
  const setPoint = useCallback(
    (point: { latitude: number; longitude: number }) =>
      setGeo((current) => ({
        lat: String(point.latitude),
        lng: String(point.longitude),
        radiusKm: current.radiusKm || "10",
      })),
    [],
  );
  const href = (page: number) => {
    const params = new URLSearchParams(query);
    params.set("page", String(page));
    return `${config.basePath}/?${params}`;
  };
  const onSelect = useCallback((id: string) => setSelected(id), []);
  function navigate(params: URLSearchParams) {
    try {
      const valid = parseSearch(compatibleSearch(params));
      setNotice("");
      router.push(`${config.basePath}/?${valid}`);
    } catch (error) {
      setNotice(
        error instanceof Error ? error.message : "Check your search filters.",
      );
    }
  }
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const params = new URLSearchParams();
    new FormData(event.currentTarget).forEach((value, key) => {
      if (typeof value === "string" && value.trim())
        params.set(key, value.trim());
    });
    navigate(params);
  }
  function update(key: string, value: string) {
    const params = new URLSearchParams(query);
    params.set(key, value);
    navigate(params);
  }
  function locate() {
    if (!navigator.geolocation) {
      setNotice(
        "Location is unavailable in this browser. Enter coordinates or browse nationwide.",
      );
      return;
    }
    navigator.geolocation?.getCurrentPosition(
      (position) =>
        setGeo({
          lat: position.coords.latitude.toFixed(6),
          lng: position.coords.longitude.toFixed(6),
          radiusKm: geo.radiusKm || "10",
        }),
      () =>
        setNotice(
          "Location permission was unavailable. Enter coordinates or browse nationwide.",
        ),
      { timeout: 10000, maximumAge: 0 },
    );
  }
  const field = (
    label: string,
    name: string,
    props: React.ComponentProps<"input"> = {},
  ) => (
    <label className="field">
      {label}
      <input name={name} defaultValue={initial.get(name) ?? ""} {...props} />
    </label>
  );
  const choice = (label: string, name: string, options: string[][]) => (
    <label className="field">
      {label}
      <select name={name} defaultValue={initial.get(name) ?? ""}>
        <option value="">Any</option>
        {options.map(([value, text]) => (
          <option key={value} value={value}>
            {text}
          </option>
        ))}
      </select>
    </label>
  );
  return (
    <>
      <section className="hero">
        <div className="container">
          <div className="hero-topline">
            <span className="eyebrow">
              <span className="tiny-dot" /> A fresh start, anywhere in India
            </span>
            <span className="hero-note">
              Your next chapter starts here <ArrowRight size={15} />
            </span>
          </div>
          <div className="hero-layout">
            <div>
              <h1>
                Find a place.
                <br />
                <span>Make it yours.</span>
              </h1>
              <p className="hero-description">
                A room of your own. A flat with friends. A new city to call
                home.
                <br className="desktop-break" /> Find your next place, directly
                from the people who own it.
              </p>
            </div>
            <div className="hero-stamp">
              <HouseIllustration />
              <span>
                Good places.
                <br />
                Real connections.
              </span>
            </div>
          </div>
          <form className="search-form" onSubmit={submit} key={query}>
            <div className="search-main">
              <label className="search-location">
                <MapPin size={21} aria-hidden />
                <span>
                  Where are you looking?
                  <input
                    name="city"
                    placeholder="Anywhere in India"
                    defaultValue={initial.get("city") ?? ""}
                    maxLength={120}
                  />
                </span>
              </label>
              <label className="search-type">
                <span>
                  Place type
                  <select
                    name="category"
                    value={category}
                    onChange={(event) => {
                      setCategory(event.target.value);
                      if (event.target.value === "PG_HOSTEL") setMode("");
                    }}
                  >
                    <option value="">All places</option>
                    <option value="FLAT">Flats & rooms</option>
                    <option value="PG_HOSTEL">PG / Hostels</option>
                  </select>
                </span>
              </label>
              <Button type="submit" className="search-button">
                <Search size={19} /> Find my place
              </Button>
            </div>
            <div className="filter-toolbar">
              <Button
                type="button"
                variant="ghost"
                size="small"
                aria-expanded={advanced}
                onClick={() => setAdvanced(!advanced)}
              >
                <SlidersHorizontal size={15} /> All filters
              </Button>
              <span>No city selected? Explore all of India.</span>
            </div>
            <div
              className={
                advanced ? "advanced-filters" : "advanced-filters collapsed"
              }
              inert={!advanced}
              aria-hidden={!advanced}
            >
              {field("Locality", "locality", { maxLength: 120 })}
              {category !== "PG_HOSTEL" && (
                <label className="field">
                  Flat rental mode
                  <select
                    name="flatRentalMode"
                    value={mode}
                    onChange={(event) => setMode(event.target.value)}
                  >
                    <option value="">Any flat mode</option>
                    <option value="ENTIRE_FLAT">Entire flat</option>
                    <option value="ROOM">Room in a flat</option>
                  </select>
                </label>
              )}
              {choice("Availability", "status", [
                ["ACTIVE", "Available"],
                ["FULL_OCCUPIED", "Fully occupied"],
              ])}
              {field("Minimum quoted rent, INR", "minRent", {
                type: "number",
                min: 0,
                step: 1,
              })}
              {field("Maximum quoted rent, INR", "maxRent", {
                type: "number",
                min: 0,
                step: 1,
              })}
              {category !== "PG_HOSTEL" && (
                <>
                  {field("Minimum bedrooms / BHK", "minBedrooms", {
                    type: "number",
                    min: 0,
                    step: 1,
                  })}
                  {field("Maximum bedrooms / BHK", "maxBedrooms", {
                    type: "number",
                    min: 0,
                    step: 1,
                  })}
                </>
              )}
              {mode !== "ENTIRE_FLAT" && (
                <>
                  {field("Minimum available rooms", "minTotalRooms", {
                    type: "number",
                    min: 0,
                    step: 1,
                  })}
                  {field("Maximum available rooms", "maxTotalRooms", {
                    type: "number",
                    min: 0,
                    step: 1,
                  })}
                  {field("Maximum occupants per room", "maxSharingPerRoom", {
                    type: "number",
                    min: 1,
                    max: 10,
                    step: 1,
                  })}
                  {choice("Room vacancy", "genderRoomAvailable", [
                    ["MALE", "Male rooms"],
                    ["FEMALE", "Female rooms"],
                    ["SHARED", "Shared rooms"],
                  ])}
                </>
              )}
              {[
                ["Wi-Fi", "hasWifi"],
                ["Gym", "hasGym"],
                ["Swimming pool", "hasSwimmingPool"],
              ].map(([label, key]) => (
                <div key={key}>
                  {choice(label, key, [
                    ["true", "Required"],
                    ["false", "Absent"],
                  ])}
                </div>
              ))}
              {(
                [
                  ["Latitude", "lat"],
                  ["Longitude", "lng"],
                  ["Radius in km", "radiusKm"],
                ] satisfies [string, keyof typeof geo][]
              ).map(([label, name]) => (
                <label className="field" key={name}>
                  {label}
                  <input
                    name={name}
                    type="number"
                    step="any"
                    value={geo[name]}
                    onChange={(event) =>
                      setGeo({ ...geo, [name]: event.target.value })
                    }
                  />
                </label>
              ))}
              <div className="field">
                <span>Nearby search</span>
                <Button type="button" variant="outline" onClick={locate}>
                  <LocateFixed size={16} /> Use my location
                </Button>
              </div>
              <div className="field field-wide">
                <Button
                  type="button"
                  variant="outline"
                  aria-expanded={picker}
                  onClick={() => setPicker(!picker)}
                >
                  Choose nearby point on map
                </Button>
                {picker && (
                  <AreaMap
                    onPoint={setPoint}
                    point={
                      geo.lat && geo.lng
                        ? {
                            latitude: Number(geo.lat),
                            longitude: Number(geo.lng),
                          }
                        : undefined
                    }
                    pointHint="Choose a search center. These coordinates become part of the shareable search URL."
                  />
                )}
              </div>
              <p className="filter-help">
                BHK describes the flat layout. Available rooms describe
                vacancies. Rent is quoted per person, room, or property, as
                shown on each listing. Nearby coordinates become part of the
                shareable URL.
              </p>
            </div>
            <input
              type="hidden"
              name="sort"
              value={initial.get("sort") ?? "NEWEST"}
            />
            <input
              type="hidden"
              name="size"
              value={initial.get("size") ?? "20"}
            />
          </form>
          <div className="hero-promises">
            <span>
              <IndianRupee size={16} /> Zero brokerage platform
            </span>
            <span>
              <Users size={16} /> Connect directly with owners
            </span>
            <span>
              <ShieldCheck size={16} /> Community reviews
            </span>
          </div>
        </div>
      </section>
      <section className="container results-section">
        <div className="results-heading">
          <div>
            <p className="eyebrow">Room for your next chapter</p>
            <h2>
              {initial.get("city")
                ? `Places in ${initial.get("city")}`
                : "Explore places across India"}
            </h2>
            <p>
              {result
                ? `${result.totalElements.toLocaleString("en-IN")} places to start something new`
                : "Flats, rooms, and PG accommodation, in one place."}
            </p>
          </div>
          <div className="results-tools">
            <Button
              variant="outline"
              size="small"
              onClick={() => setMap(!map)}
              aria-pressed={map}
            >
              {map ? <LayoutGrid size={16} /> : <MapIcon size={16} />}
              {map ? "List view" : "Map view"}
            </Button>
            <label className="sr-only" htmlFor="sort">
              Sort listings
            </label>
            <select
              id="sort"
              value={initial.get("sort") ?? "NEWEST"}
              onChange={(event) => update("sort", event.target.value)}
            >
              {sortLabels.map(([value, label]) => (
                <option
                  key={value}
                  value={value}
                  disabled={
                    value === "DISTANCE_ASC" &&
                    !(
                      initial.has("lat") &&
                      initial.has("lng") &&
                      initial.has("radiusKm")
                    )
                  }
                >
                  {label}
                </option>
              ))}
            </select>
            <label className="sr-only" htmlFor="size">
              Results per page
            </label>
            <select
              id="size"
              value={initial.get("size") ?? "20"}
              onChange={(event) => update("size", event.target.value)}
            >
              {[10, 20, 50].map((size) => (
                <option key={size} value={size}>
                  {size} per page
                </option>
              ))}
              {![10, 20, 50].includes(Number(initial.get("size") ?? 20)) && (
                <option value={initial.get("size") ?? "20"}>
                  {initial.get("size")} per page
                </option>
              )}
            </select>
          </div>
        </div>
        <div className="results-subline">
          <span>Quoted monthly rent · Basis shown on every place</span>
          <Link href={`${config.basePath}/`} className="text-link">
            Clear filters
          </Link>
        </div>
        {notice && <Notice error>{notice}</Notice>}
        {error && (
          <Notice error>
            {error}{" "}
            <Link href={`${config.basePath}/`} className="text-link">
              Start a new search
            </Link>
          </Notice>
        )}
        {map && result && (
          <AreaMap
            listings={result.items}
            onSelect={onSelect}
            selectedId={selected}
          />
        )}
        {result && result.items.length === 0 && (
          <div className="empty-state">
            <Search size={35} />
            <h3>
              {result.totalElements > 0
                ? "This page has no places."
                : "A little more room to search."}
            </h3>
            <p>
              {result.totalElements > 0
                ? "The results changed. Return to the first page."
                : "Try a wider rent range, another locality, or search across India."}
            </p>
            <Button asChild variant="outline">
              <Link
                href={
                  result.totalElements > 0 ? href(0) : `${config.basePath}/`
                }
              >
                {result.totalElements > 0 ? "First page" : "Explore all places"}
              </Link>
            </Button>
          </div>
        )}
        <div className="listing-grid">
          {result?.items.map((listing) => (
            <ListingCard
              key={listing.id}
              listing={listing}
              basePath={config.basePath}
              apiOrigin={config.apiOrigin}
              selected={selected === listing.id}
              onSelect={() => setSelected(listing.id)}
            />
          ))}
        </div>
        {result && <Pagination {...result} href={href} />}
      </section>
      <section className="container reassurance">
        <div className="reassurance-icon">
          <HouseIllustration />
        </div>
        <div>
          <p className="eyebrow">
            A little less searching. A little more living.
          </p>
          <h2>Your place is out there.</h2>
          <p>
            Explore at your pace. Ask questions. Read the reviews.
            <br />
            And when it feels right, connect with the owner.
          </p>
        </div>
        <Button asChild variant="outline">
          <Link href={`${config.basePath}/my-listings`}>
            Have a place to share? <ArrowRight size={16} />
          </Link>
        </Button>
      </section>
    </>
  );
}

function HouseIllustration() {
  return (
    <svg viewBox="0 0 160 150" fill="none" aria-hidden>
      <path
        d="M24 68 80 23l56 45v66H24V68Z"
        stroke="currentColor"
        strokeWidth="2"
      />
      <path
        d="m13 77 67-55 67 55M67 134V91h26v43M39 74h17v18H39zM104 74h17v18h-17zM109 40V24h14v27"
        stroke="currentColor"
        strokeWidth="2"
      />
      <path
        d="M12 135h137M80 8V0M15 29l-8-7M143 30l9-8"
        stroke="currentColor"
        strokeWidth="2"
      />
      <path
        d="M36 120c-14-26-25-5-11 7 2 2 8 5 11 7 4-7 19-16 13-23-6-8-12 1-13 9Z"
        fill="currentColor"
        fillOpacity=".15"
        stroke="currentColor"
        strokeWidth="1.5"
      />
    </svg>
  );
}
