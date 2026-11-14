import { StrictMode } from "react";
import { render, screen } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import { getConfig } from "@/lib/config";
import { SessionProvider, useSession } from "./session";
import { Shell } from "./shell";

vi.mock("next/navigation", () => ({
  useRouter: () => ({ replace: vi.fn(), refresh: vi.fn() }),
}));

function Status() {
  const { ready } = useSession();
  return <p>{ready ? "Session check complete" : "Checking session"}</p>;
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
  expect(screen.getByText("Session check complete")).toBeInTheDocument();
  expect(screen.queryByText("Failed to fetch")).not.toBeInTheDocument();
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
