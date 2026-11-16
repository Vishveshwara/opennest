import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { z } from "zod";
import { ApiError } from "@/lib/api";
import {
  challengeSchema,
  challengeStatusSchema,
  loginCompletionSchema,
} from "@/lib/contracts";
import { Login } from "./auth";

const doubles = vi.hoisted(() => ({
  api: { request: vi.fn(), token: vi.fn() },
  refresh: vi.fn(),
  rotate: vi.fn(),
  router: { replace: vi.fn(), refresh: vi.fn() },
}));

vi.mock("next/navigation", () => ({
  useRouter: () => doubles.router,
  useSearchParams: () => new URLSearchParams("returnTo=/account"),
}));
vi.mock("./session", () => ({
  useSession: () => ({
    api: doubles.api,
    refresh: doubles.refresh,
    rotate: doubles.rotate,
    config: { basePath: "", mode: "PRIMARY" },
  }),
}));

const startedAt = Date.parse("2026-11-14T04:30:00Z");
const challengesPath = "/api/v1/auth/telegram/challenges";
const challengeId = "00000000-0000-4000-8000-000000000001";
const challengePath = `${challengesPath}/${challengeId}`;
const phoneNumber = "+910000000001";
const code = "123456";
const completionBody = z.object({ code: z.string(), fullName: z.string().optional() });
let returning = false;
let failNextResend = false;
let resendAt = "";
let deliveryStatus: "CODE_SENT" | "CODE_READY" = "CODE_SENT";
let resendsRemaining = 3;

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(startedAt);
  vi.clearAllMocks();
  returning = false;
  failNextResend = false;
  deliveryStatus = "CODE_SENT";
  resendsRemaining = 3;
  resendAt = new Date(startedAt + 30_000).toISOString();
  doubles.api.token.mockResolvedValue(undefined);
  doubles.refresh.mockResolvedValue(undefined);
  doubles.api.request.mockImplementation(
    async (path: string, schema: z.ZodType, method = "GET", body?: unknown) => {
      if (path === challengesPath && method === "POST") {
        return schema.parse({
          challengeId,
          botUrl: "https://t.me/OpenNestTestBot?start=fabricated-browser-token",
          expiresAt: new Date(startedAt + 300_000).toISOString(),
          restartAt: new Date(Date.now() + 60_000).toISOString(),
          status: "WAITING_BOT",
        });
      }
      if (path === challengePath && method === "GET") {
        return schema.parse({
          status: deliveryStatus,
          expiresAt: new Date(startedAt + 300_000).toISOString(),
          resendAt,
          resendsRemaining,
        });
      }
      if (path === `${challengePath}/complete` && method === "POST") {
        const completion = completionBody.parse(body);
        return schema.parse({
          status: returning || completion.fullName ? "AUTHENTICATED" : "NAME_REQUIRED",
        });
      }
      if (path === `${challengePath}/resend` && method === "POST") {
        if (failNextResend) {
          failNextResend = false;
          throw new ApiError(503);
        }
        if (deliveryStatus === "CODE_SENT") resendsRemaining = Math.max(0, resendsRemaining - 1);
        deliveryStatus = "CODE_SENT";
        resendAt = new Date(Date.now() + 30_000).toISOString();
        return schema.parse({
          status: "CODE_SENT",
          expiresAt: new Date(startedAt + 300_000).toISOString(),
          resendAt,
          resendsRemaining,
        });
      }
      throw new Error("Unexpected controlled authentication request");
    },
  );
});

afterEach(() => {
  cleanup();
  vi.clearAllTimers();
  vi.useRealTimers();
});

async function startLogin() {
  render(<Login />);
  fireEvent.change(screen.getByRole("textbox", { name: "Phone number" }), {
    target: { value: phoneNumber },
  });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Continue with Telegram" }));
  });
  expect(doubles.api.request).toHaveBeenCalledWith(
    challengesPath,
    challengeSchema,
    "POST",
    { phoneNumber },
  );
}

async function submitCode() {
  fireEvent.change(screen.getByRole("textbox", { name: "Telegram code" }), {
    target: { value: code },
  });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Sign in" }));
  });
}

async function advance(milliseconds: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(milliseconds);
  });
}

function challengeCreations() {
  return doubles.api.request.mock.calls.filter(
    ([path, , method]) => path === challengesPath && method === "POST",
  );
}

function resendRequests() {
  return doubles.api.request.mock.calls.filter(
    ([path, , method]) => path === `${challengePath}/resend` && method === "POST",
  );
}

it("creates a named account only after a valid OTP and reuses the same private challenge and code", async () => {
  await startLogin();
  expect(screen.queryByRole("textbox", { name: "Full name" })).not.toBeInTheDocument();
  await submitCode();
  expect(doubles.api.request).toHaveBeenCalledWith(
    `${challengePath}/complete`, loginCompletionSchema, "POST", { code },
  );
  expect(screen.getByRole("heading", { name: "Create your OpenNest account" })).toBeInTheDocument();
  const fullName = screen.getByRole("textbox", { name: "Full name" });
  expect(fullName).toBeRequired();
  expect(fullName).toHaveAttribute("maxlength", "120");
  expect(fullName).toHaveAttribute("autocomplete", "name");
  expect(screen.queryByRole("textbox", { name: "Telegram code" })).not.toBeInTheDocument();
  expect(doubles.rotate).not.toHaveBeenCalled();
  expect(doubles.refresh).not.toHaveBeenCalled();
  fireEvent.change(fullName, { target: { value: "  Fabricated OpenNest user  " } });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Create account" }));
  });
  expect(doubles.api.request).toHaveBeenLastCalledWith(
    `${challengePath}/complete`, loginCompletionSchema, "POST", { code, fullName: "Fabricated OpenNest user" },
  );
  expect(challengeCreations()).toHaveLength(1);
  expect(doubles.rotate).toHaveBeenCalledOnce();
  expect(doubles.api.token).toHaveBeenCalledOnce();
  expect(doubles.refresh).toHaveBeenCalledOnce();
  expect(doubles.router.replace).toHaveBeenCalledWith("/account");
});

it("signs an existing account in without asking for a name or submitting a replacement name", async () => {
  returning = true;
  await startLogin();
  await submitCode();
  expect(screen.queryByRole("textbox", { name: "Full name" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Create account" })).not.toBeInTheDocument();
  expect(doubles.api.request).toHaveBeenCalledWith(
    `${challengePath}/complete`, loginCompletionSchema, "POST", { code },
  );
  expect(doubles.rotate).toHaveBeenCalledOnce();
  expect(doubles.refresh).toHaveBeenCalledOnce();
  expect(doubles.router.replace).toHaveBeenCalledWith("/account");
});

it("waits for the resend deadline then posts an empty body, retains the challenge and replaces the cooldown", async () => {
  await startLogin();
  const input = screen.getByRole("textbox", { name: "Telegram code" });
  fireEvent.change(input, { target: { value: code } });
  expect(screen.getByRole("button", { name: "Resend code (30s)" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: /^Resend code/ }));
  expect(resendRequests()).toHaveLength(0);
  await advance(29_000);
  expect(screen.getByRole("button", { name: "Resend code (1s)" })).toBeDisabled();
  await advance(1_000);
  expect(screen.getByRole("button", { name: "Resend code" })).toBeEnabled();
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Resend code" }));
  });
  expect(doubles.api.request).toHaveBeenCalledWith(
    `${challengePath}/resend`, challengeStatusSchema, "POST", {},
  );
  expect(resendRequests()).toHaveLength(1);
  expect(screen.getByRole("textbox", { name: "Telegram code" })).toHaveValue("");
  expect(screen.getByRole("button", { name: "Resend code (30s)" })).toBeDisabled();
  expect(screen.getByRole("link", { name: "Open Telegram" })).toHaveAttribute(
    "href", "https://t.me/OpenNestTestBot?start=fabricated-browser-token",
  );
  expect(challengeCreations()).toHaveLength(1);
});

it("preserves the challenge and typed code when a resend fails so the visitor can retry", async () => {
  await startLogin();
  fireEvent.change(screen.getByRole("textbox", { name: "Telegram code" }), {
    target: { value: code },
  });
  await advance(30_000);
  failNextResend = true;
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Resend code" }));
  });
  expect(screen.getByRole("alert")).toHaveTextContent("OpenNest cannot reach a required service");
  expect(screen.getByRole("textbox", { name: "Telegram code" })).toHaveValue(code);
  expect(screen.getByRole("button", { name: "Resend code" })).toBeEnabled();
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Resend code" }));
  });
  expect(resendRequests()).toHaveLength(2);
  expect(challengeCreations()).toHaveLength(1);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByRole("textbox", { name: "Telegram code" })).toHaveValue("");
});

it("keeps the restart cooldown visible and clears signup data only after an allowed reset", async () => {
  await startLogin();
  await submitCode();
  fireEvent.change(screen.getByRole("textbox", { name: "Full name" }), {
    target: { value: "Fabricated pending name" },
  });
  expect(screen.getByRole("button", { name: "Start again (60s)" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: /^Start again/ }));
  expect(screen.getByRole("textbox", { name: "Full name" })).toHaveValue("Fabricated pending name");
  expect(challengeCreations()).toHaveLength(1);
  await advance(59_000);
  expect(screen.getByRole("button", { name: "Start again (1s)" })).toBeDisabled();
  await advance(1_000);
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Start again" }));
  });
  expect(screen.getByRole("textbox", { name: "Phone number" })).toHaveValue("");
  expect(screen.queryByRole("textbox", { name: "Full name" })).not.toBeInTheDocument();
  expect(screen.queryByRole("textbox", { name: "Telegram code" })).not.toBeInTheDocument();
  expect(challengeCreations()).toHaveLength(1);
  fireEvent.change(screen.getByRole("textbox", { name: "Phone number" }), {
    target: { value: phoneNumber },
  });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Continue with Telegram" }));
  });
  expect(challengeCreations()).toHaveLength(2);
  expect(screen.getByRole("textbox", { name: "Telegram code" })).toHaveValue("");
  expect(screen.queryByRole("textbox", { name: "Full name" })).not.toBeInTheDocument();
});

it("blocks fresh resends at the limit and retains restart guidance even after a stale status increases the allowance", async () => {
  resendsRemaining = 0;
  await startLogin();
  expect(screen.getByRole("button", { name: "Resend limit reached" })).toBeDisabled();
  expect(screen.getByText("Use Start again to request a new challenge when its countdown ends.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Start again (60s)" })).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "Resend limit reached" }));
  expect(resendRequests()).toHaveLength(0);
  resendsRemaining = 3;
  await advance(30_000);
  expect(screen.getByRole("button", { name: "Resend limit reached" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Start again (30s)" })).toBeDisabled();
  await advance(30_000);
  expect(screen.getByRole("button", { name: "Start again" })).toBeEnabled();
  expect(screen.getByRole("button", { name: "Resend limit reached" })).toBeDisabled();
  expect(challengeCreations()).toHaveLength(1);
  expect(resendRequests()).toHaveLength(0);
});

it("retries pending code delivery immediately at zero allowance and recovers the same challenge", async () => {
  deliveryStatus = "CODE_READY";
  resendsRemaining = 0;
  await startLogin();
  expect(screen.getByRole("button", { name: "Retry code delivery" })).toBeEnabled();
  expect(screen.queryByRole("button", { name: "Resend limit reached" })).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Start again (60s)" })).toBeDisabled();
  fireEvent.change(screen.getByRole("textbox", { name: "Telegram code" }), {
    target: { value: code },
  });
  await act(async () => {
    fireEvent.click(screen.getByRole("button", { name: "Retry code delivery" }));
  });
  expect(doubles.api.request).toHaveBeenCalledWith(
    `${challengePath}/resend`, challengeStatusSchema, "POST", {},
  );
  expect(resendRequests()).toHaveLength(1);
  expect(challengeCreations()).toHaveLength(1);
  expect(screen.getByRole("textbox", { name: "Telegram code" })).toHaveValue("");
  expect(screen.getByRole("button", { name: "Resend limit reached" })).toBeDisabled();
  expect(screen.getByRole("link", { name: "Open Telegram" })).toHaveAttribute(
    "href", "https://t.me/OpenNestTestBot?start=fabricated-browser-token",
  );
  returning = true;
  await submitCode();
  expect(doubles.api.request).toHaveBeenCalledWith(
    `${challengePath}/complete`, loginCompletionSchema, "POST", { code },
  );
  expect(doubles.rotate).toHaveBeenCalledOnce();
  expect(doubles.router.replace).toHaveBeenCalledWith("/account");
});
