import { StrictMode } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { getConfig } from "@/lib/config";
import { SessionProvider, useSession } from "./session";
import { Shell } from "./shell";
import { AuthGate } from "./auth";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }),
}));

function Status() {
  const { ready, status } = useSession();
  return <p>{status === "unavailable" ? "Session unavailable" : ready ? "Session check complete" : "Checking session"}</p>;
}
function mount() {
  return render(
    <StrictMode>
      <SessionProvider config={{ ...getConfig(), mapKey: "" }}>
        <Status />
      </SessionProvider>
    </StrictMode>,
  );
}

it("labels an unfinished session check without implying the visitor is signed in", async () => {
  const pending = Promise.withResolvers<Response>();
  vi.stubGlobal("fetch", vi.fn(() => pending.promise));
  render(
    <SessionProvider config={getConfig()}>
      <Shell>
        <Status />
      </Shell>
    </SessionProvider>,
  );
  expect(
    screen.getByRole("link", { name: "Checking sign-in…" }),
  ).toBeInTheDocument();
  pending.resolve(new Response(null, { status: 401 }));
  expect(
    await screen.findByRole("link", { name: "Sign in" }),
  ).toBeInTheDocument();
});

it("shows a handled outage when the browser fetch rejects", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
  );
  mount();
  expect(await screen.findByRole("alert")).toHaveTextContent(
    "OpenNest cannot reach a required service",
  );
  expect(screen.getByText("Session unavailable")).toBeInTheDocument();
  expect(screen.queryByText("Failed to fetch")).not.toBeInTheDocument();
});

it.each(["network", "503"])("keeps a failed %s probe out of sign-in paths and retries the profile", async (failure) => {
  const fetch = vi.fn();
  if (failure === "network") fetch.mockRejectedValueOnce(new TypeError("Failed to fetch"));
  else fetch.mockResolvedValueOnce(new Response(null, { status: 503 }));
  fetch.mockResolvedValueOnce(Response.json({
    id: "00000000-0000-4000-8000-000000000001", fullName: "Test Resident", role: "USER",
    phoneNumber: null, phoneVerified: false, averageRating: null, reviewCount: 0,
    brokerReportCount: 0, trustBadge: "STANDARD",
  }));
  vi.stubGlobal("fetch", fetch);
  render(<SessionProvider config={getConfig()}><Shell><AuthGate><p>Private content</p></AuthGate></Shell></SessionProvider>);
  expect(await screen.findByText("Sign-in unavailable")).toBeInTheDocument();
  expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
  expect(screen.queryByText("Sign in to continue")).not.toBeInTheDocument();
  expect(screen.queryByText("Private content")).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Retry session check" }));
  expect(await screen.findByText("Private content")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "My account: Test Resident" })).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledTimes(2);
});

it("uses the normal signed-out state when a retried probe returns 401", async () => {
  vi.stubGlobal("fetch", vi.fn().mockResolvedValueOnce(new Response(null, { status: 503 }))
    .mockResolvedValueOnce(new Response(null, { status: 401 })));
  render(<SessionProvider config={getConfig()}><Shell><AuthGate>Private content</AuthGate></Shell></SessionProvider>);
  fireEvent.click(await screen.findByRole("button", { name: "Retry session check" }));
  expect(await screen.findByText("Sign in to continue")).toBeInTheDocument();
  expect(screen.getByRole("link", { name: "Sign in" })).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});

it("treats an anonymous 401 as an ordinary signed-out session", async () => {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
  );
  mount();
  expect(await screen.findByText("Session check complete")).toBeInTheDocument();
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
});
