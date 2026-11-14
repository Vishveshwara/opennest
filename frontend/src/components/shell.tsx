"use client";

import Link from "next/link";
import { useState } from "react";
import { House, Moon, Sun, ArrowUpRight } from "lucide-react";
import { useSession } from "./session";
import { Button } from "./ui/button";

export function Shell({ children }: { children: React.ReactNode }) {
  const { config, account, ready, logout } = useSession();
  const [dark, setDark] = useState(false);
  const path = (value: string) => `${config.basePath}${value}`;
  function toggleTheme() {
    const value = !dark;
    setDark(value);
    document.documentElement.classList.toggle("dark", value);
  }
  return (
    <>
      <a className="skip-link" href="#main">
        Skip to content
      </a>
      {config.mode === "SANDBOX" && (
        <div className="sandbox-banner">
          Recruiter sandbox · Fabricated listings and personas · Contact actions
          disabled · Resets every six hours
        </div>
      )}
      <header className="site-header">
        <div className="container header-inner">
          <Link href={path("/")} className="brand" aria-label="OpenNest home">
            <span className="brand-mark">
              <House size={23} strokeWidth={1.7} />
            </span>
            OpenNest<span className="brand-dot">.</span>
          </Link>
          <nav aria-label="Main navigation">
            <Link href={path("/")} className="nav-link">
              Find a place
            </Link>
            {config.mode === "SANDBOX" && (
              <Link href={path("/personas")} className="nav-link">
                Personas
              </Link>
            )}
            <Button asChild variant="outline" size="small">
              <Link href={path("/my-listings")}>
                List your place <ArrowUpRight size={14} />
              </Link>
            </Button>
            {account ? (
              <>
                <Link
                  href={path("/account")}
                  className="nav-link account-link"
                  aria-label={`My account: ${account.fullName}`}
                  title={account.fullName}
                >
                  <span>My account</span>
                  <span className="account-name">{account.fullName}</span>
                </Link>
                <Button
                  variant="ghost"
                  size="small"
                  onClick={() => void logout()}
                >
                  Sign out
                </Button>
              </>
            ) : (
              <Link
                href={path(config.mode === "SANDBOX" ? "/personas" : "/login")}
                className="nav-link"
              >
                {ready ? "Sign in" : "Checking sign-in…"}
              </Link>
            )}
            <Button
              variant="ghost"
              size="small"
              aria-label={dark ? "Use light theme" : "Use dark theme"}
              onClick={toggleTheme}
            >
              {dark ? <Sun size={18} /> : <Moon size={18} />}
            </Button>
          </nav>
        </div>
      </header>
      <main id="main" tabIndex={-1}>
        {children}
      </main>
      <footer className="site-footer">
        <div className="container footer-inner">
          <div>
            <Link href={path("/")} className="brand">
              OpenNest<span className="brand-dot">.</span>
            </Link>
            <p>A place for your next chapter.</p>
          </div>
          <div className="footer-links">
            <span>Across India. Without brokerage.</span>
            <a href={config.sourceUrl} target="_blank" rel="noreferrer">
              Corresponding source
            </a>
            <a href={config.licenseUrl} target="_blank" rel="noreferrer">
              AGPL-3.0-only
            </a>
            <a href="/third-party-notices.txt">Third-party notices</a>
          </div>
        </div>
      </footer>
    </>
  );
}
