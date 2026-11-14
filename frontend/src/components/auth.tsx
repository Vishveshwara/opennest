"use client";

import { useEffect, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowUpRight, MessageCircle, ShieldCheck } from "lucide-react";
import { z } from "zod";
import {
  authenticatedSchema,
  challengeSchema,
  challengeStatusSchema,
  loginCompletionSchema,
  personasSchema,
} from "@/lib/contracts";
import { errorMessage } from "@/lib/api";
import { safeReturnPath } from "@/lib/search";
import { useSession } from "./session";
import { Button } from "./ui/button";
import { Notice, TrustBadge } from "./common";

export function Login() {
  const { api, refresh, rotate, config } = useSession();
  const router = useRouter(),
    params = useSearchParams();
  const [challenge, setChallenge] = useState<z.infer<
      typeof challengeSchema
    > | null>(null),
    [phone, setPhone] = useState(""),
    [code, setCode] = useState(""),
    [fullName, setFullName] = useState(""),
    [completion, setCompletion] = useState<"CODE" | "NAME_REQUIRED">("CODE"),
    [resendAt, setResendAt] = useState<string | null>(null),
    [resendsRemaining, setResendsRemaining] = useState<number | null>(null),
    [now, setNow] = useState<number | null>(null),
    [status, setStatus] = useState(""),
    [error, setError] = useState(""),
    [operation, setOperation] = useState<"IDLE" | "STARTING" | "COMPLETING" | "RESENDING">("IDLE"),
    [expired, setExpired] = useState(false);
  const busy = operation !== "IDLE";
  const challengeExpired =
    expired ||
    (challenge !== null && now !== null && now >= Date.parse(challenge.expiresAt));
  const restartSeconds =
    challenge !== null && now !== null
      ? Math.max(0, Math.ceil((Date.parse(challenge.restartAt) - now) / 1000))
      : null;
  const resendSeconds =
    resendAt !== null && now !== null
      ? Math.max(0, Math.ceil((Date.parse(resendAt) - now) / 1000))
      : null;
  const showResend =
    completion === "CODE" && ["CODE_SENT", "CODE_READY"].includes(status);
  const retryDelivery = status === "CODE_READY";
  const canResend = showResend && !busy && !challengeExpired &&
    (retryDelivery || resendSeconds === 0 && resendsRemaining !== 0);
  useEffect(() => {
    if (!challenge) return;
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, [challenge]);
  useEffect(() => {
    if (!challenge) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout>;
    const poll = async () => {
      if (Date.now() >= Date.parse(challenge.expiresAt)) {
        if (alive) {
          setExpired(true);
          setStatus("Challenge expired");
        }
        return;
      }
      try {
        const value = await api.request(
          `/api/v1/auth/telegram/challenges/${challenge.challengeId}`,
          challengeStatusSchema,
        );
        if (alive) {
          setStatus(value.status);
          if (value.resendAt) {
            const deadline = value.resendAt;
            setResendAt((current) =>
              current === null || Date.parse(deadline) > Date.parse(current)
                ? deadline
                : current,
            );
          }
          if (value.resendsRemaining !== undefined) {
            const remaining = value.resendsRemaining;
            setResendsRemaining((current) => current === null ? remaining : Math.min(current, remaining));
          }
          if (["CANCELLED", "LOCKED"].includes(value.status)) setExpired(true);
        }
      } catch (error) {
        if (alive) setError(errorMessage(error));
      }
      if (alive) timer = setTimeout(() => void poll(), 3000);
    };
    void poll();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [api, challenge]);
  async function start(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (busy) return;
    setOperation("STARTING");
    setError("");
    try {
      const value = await api.request(
        "/api/v1/auth/telegram/challenges",
        challengeSchema,
        "POST",
        { phoneNumber: phone },
      );
      setChallenge(value);
      setNow(Date.now());
      setStatus(value.status);
      setExpired(false);
      setPhone("");
      setCode("");
      setFullName("");
      setCompletion("CODE");
      setResendAt(null);
      setResendsRemaining(null);
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setOperation("IDLE");
    }
  }
  async function complete(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!challenge || challengeExpired || busy) return;
    if (completion === "NAME_REQUIRED" && !fullName.trim()) {
      setError("Enter your full name.");
      return;
    }
    setOperation("COMPLETING");
    setError("");
    try {
      const result = await api.request(
        `/api/v1/auth/telegram/challenges/${challenge.challengeId}/complete`,
        loginCompletionSchema,
        "POST",
        completion === "NAME_REQUIRED"
          ? { code, fullName: fullName.trim() }
          : { code },
      );
      if (result.status === "NAME_REQUIRED") {
        setCompletion("NAME_REQUIRED");
        return;
      }
      setCode("");
      setFullName("");
      rotate();
      await api.token();
      await refresh();
      router.replace(safeReturnPath(params.get("returnTo"), config.basePath));
      router.refresh();
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setOperation("IDLE");
    }
  }
  async function resend() {
    if (!challenge || !canResend) return;
    setOperation("RESENDING");
    setError("");
    try {
      const value = await api.request(
        `/api/v1/auth/telegram/challenges/${challenge.challengeId}/resend`,
        challengeStatusSchema,
        "POST",
        {},
      );
      setStatus(value.status);
      setResendAt(value.resendAt ?? null);
      if (value.resendsRemaining !== undefined) {
        const remaining = value.resendsRemaining;
        setResendsRemaining((current) => current === null ? remaining : Math.min(current, remaining));
      }
      setNow(Date.now());
      setCode("");
      const expiresAt = value.expiresAt;
      if (expiresAt) {
        setChallenge((current) => current && current.expiresAt !== expiresAt ? { ...current, expiresAt } : current);
      }
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setOperation("IDLE");
    }
  }
  return (
    <div className="container section auth-layout">
      <div className="auth-intro">
        <p className="eyebrow">Your next chapter starts here</p>
        <h1>
          A familiar hello.
          <br />A new beginning.
        </h1>
        <p>
          Sign in with your Telegram account to connect with owners, share
          reviews, or list a place of your own.
        </p>
        <div className="auth-note">
          <ShieldCheck size={23} />
          <p>
            Your Telegram contact and the code entered here establish your
            OpenNest session. This verifies account contact access, not property
            ownership.
          </p>
        </div>
      </div>
      <section className="panel auth-panel">
        <span className="icon-tile">
          <MessageCircle size={25} />
        </span>
        <h2>
          {completion === "NAME_REQUIRED"
            ? "Create your OpenNest account"
            : "Welcome to OpenNest"}
        </h2>
        <p>
          {completion === "NAME_REQUIRED"
            ? "Your Telegram code is confirmed. Add your name to finish signing up."
            : "Use the +91 number associated with your Telegram account."}
        </p>
        {error && <Notice error>{error}</Notice>}
        {!challenge ? (
          <form onSubmit={start} className="stack">
            <label className="field">
              Phone number
              <input
                type="tel"
                autoComplete="tel"
                inputMode="tel"
                pattern="\+91[0-9]{10}"
                placeholder="+91 followed by 10 digits"
                required
                value={phone}
                onChange={(event) => setPhone(event.target.value)}
              />
            </label>
            <Button disabled={busy} type="submit">
              {busy ? "Starting…" : "Continue with Telegram"}{" "}
              <ArrowUpRight size={16} />
            </Button>
            <p className="fine-print">
              You will share your own contact in the private bot chat, then
              enter its code in this browser. Browsing does not require an
              account.
            </p>
          </form>
        ) : (
          <div className="stack">
            {completion === "CODE" && (
              <>
                <ol className="login-steps">
                  <li>Open the Telegram bot and press Start.</li>
                  <li>Use its button to share your own +91 contact.</li>
                  <li>Enter the six-digit code here, in this browser.</li>
                </ol>
                <Button asChild variant="outline">
                  <a href={challenge.botUrl} target="_blank" rel="noreferrer">
                    Open Telegram <ArrowUpRight size={16} />
                  </a>
                </Button>
              </>
            )}
            <p role="status" className="fine-print">
              {challengeExpired ? "Challenge expired" : status.replaceAll("_", " ")} · Expires{" "}
              {new Date(challenge.expiresAt).toLocaleTimeString("en-IN", {
                hour: "2-digit",
                minute: "2-digit",
              })}
            </p>
            <form onSubmit={complete} className="stack">
              {completion === "NAME_REQUIRED" ? (
                <label className="field">
                  Full name
                  <input
                    type="text"
                    autoComplete="name"
                    maxLength={120}
                    required
                    value={fullName}
                    onChange={(event) => setFullName(event.target.value)}
                    disabled={busy || challengeExpired}
                  />
                </label>
              ) : (
                <label className="field">
                  Telegram code
                  <input
                    aria-label="Telegram code"
                    type="text"
                    inputMode="numeric"
                    autoComplete="one-time-code"
                    pattern="[0-9]{6}"
                    maxLength={6}
                    required
                    value={code}
                    onChange={(event) =>
                      setCode(event.target.value.replace(/\D/g, ""))
                    }
                    disabled={busy || challengeExpired}
                  />
                </label>
              )}
              <Button disabled={busy || challengeExpired} type="submit">
                {completion === "NAME_REQUIRED"
                  ? operation === "COMPLETING" ? "Creating account…" : "Create account"
                  : operation === "COMPLETING" ? "Signing in…" : "Sign in"}
              </Button>
            </form>
            {showResend && (
              <Button
                variant="outline"
                disabled={!canResend}
                onClick={() => void resend()}
              >
                {operation === "RESENDING" ? "Sending code…" : retryDelivery ? "Retry code delivery" : resendsRemaining === 0 ? "Resend limit reached" : "Resend code"}
                {!retryDelivery && resendsRemaining !== 0 && resendSeconds !== null && resendSeconds > 0 ? ` (${resendSeconds}s)` : ""}
              </Button>
            )}
            {showResend && !retryDelivery && resendsRemaining === 0 && (
              <p className="fine-print">Use Start again to request a new challenge when its countdown ends.</p>
            )}
            {showResend && !retryDelivery && resendsRemaining !== 0 && resendSeconds === null && (
              <p className="fine-print">Waiting for resend availability.</p>
            )}
            <Button
              variant="ghost"
              disabled={busy || restartSeconds !== 0}
              onClick={() => {
                setChallenge(null);
                setCode("");
                setFullName("");
                setCompletion("CODE");
                setResendAt(null);
                setResendsRemaining(null);
                setStatus("");
                setExpired(false);
                setNow(null);
                setError("");
              }}
            >
              Start again{restartSeconds !== null && restartSeconds > 0 ? ` (${restartSeconds}s)` : ""}
            </Button>
            <p className="fine-print">
              Bot approval and status updates alone do not sign you in.
            </p>
          </div>
        )}
      </section>
    </div>
  );
}

export function AuthGate({ children }: { children: React.ReactNode }) {
  const { account, ready, config } = useSession();
  if (!ready) return <p role="status">Checking your session…</p>;
  if (!account)
    return (
      <div className="panel empty-state">
        <h2>Sign in to continue</h2>
        <p>
          Your session may have ended. Your saved listings remain with your
          account.
        </p>
        <Button asChild>
          <Link
            href={`${config.basePath}${config.mode === "SANDBOX" ? "/personas" : "/login"}?returnTo=${encodeURIComponent(typeof window === "undefined" ? `${config.basePath}/my-listings` : window.location.pathname + window.location.search)}`}
          >
            {config.mode === "SANDBOX"
              ? "Select a persona"
              : "Sign in with Telegram"}
          </Link>
        </Button>
      </div>
    );
  return children;
}

export function AccountView() {
  const { account, config } = useSession();
  return (
    <div className="container section">
      <p className="eyebrow">Your OpenNest</p>
      <h1>My account</h1>
      <AuthGate>
        {account && (
          <section className="panel account-panel">
            <h2>{account.fullName}</h2>
            <TrustBadge value={account.trustBadge} />
            <dl className="detail-list">
              <div>
                <dt>Your contact</dt>
                <dd>{account.phoneNumber ?? "No verified contact"}</dd>
              </div>
              <div>
                <dt>Contact status</dt>
                <dd>
                  {account.phoneVerified
                    ? "Verified account contact"
                    : "Not verified"}
                </dd>
              </div>
              <div>
                <dt>Community rating</dt>
                <dd>
                  {account.averageRating === null
                    ? "Not yet rated"
                    : `${account.averageRating.toFixed(1)} / 5`}{" "}
                  · {account.reviewCount} reviews
                </dd>
              </div>
            </dl>
            <p className="fine-print">
              Contact verification proves Telegram account contact access. It
              does not prove property ownership or fresh SIM possession.
            </p>
            <Button asChild>
              <Link href={`${config.basePath}/my-listings`}>
                Manage my listings
              </Link>
            </Button>
          </section>
        )}
      </AuthGate>
    </div>
  );
}

export function Personas() {
  const { api, config, refresh, rotate } = useSession();
  const router = useRouter(),
    params = useSearchParams();
  const [personas, setPersonas] = useState<
      z.infer<typeof personasSchema>["personas"]
    >([]),
    [error, setError] = useState(""),
    [busy, setBusy] = useState(false);
  useEffect(() => {
    let alive = true;
    void api
      .request("/api/v1/sandbox/personas", personasSchema)
      .then((value) => {
        if (alive) setPersonas(value.personas);
      })
      .catch((error) => {
        if (alive) setError(errorMessage(error));
      });
    return () => {
      alive = false;
    };
  }, [api]);
  async function select(key: string) {
    setBusy(true);
    setError("");
    try {
      await api.request(
        "/api/v1/sandbox/personas/select",
        authenticatedSchema,
        "POST",
        { personaKey: key },
      );
      rotate();
      await api.token();
      await refresh();
      router.replace(safeReturnPath(params.get("returnTo"), config.basePath));
      router.refresh();
    } catch (error) {
      setError(errorMessage(error));
    } finally {
      setBusy(false);
    }
  }
  return (
    <div className="container section">
      <p className="eyebrow">A hands-on look at OpenNest</p>
      <h1>Choose a perspective.</h1>
      <p>
        These personas and listings are fabricated. Try owner workflows,
        ratings, and report scenarios. Callable contact data is disabled.
      </p>
      <Notice>
        Data resets every six hours in Asia/Kolkata. After a reset, choose a
        persona again. Normal ownership, review eligibility, and publication
        rules still apply.
      </Notice>
      {error && <Notice error>{error}</Notice>}
      <div className="persona-grid">
        {personas.map((persona) => (
          <button
            className="panel persona-card"
            key={persona.key}
            onClick={() => void select(persona.key)}
            disabled={busy}
          >
            <span className="avatar">{persona.displayName.slice(0, 1)}</span>
            <h2>{persona.displayName}</h2>
            <span className="text-link">
              Explore this persona <ArrowUpRight size={15} />
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}
