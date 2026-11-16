"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from "react";
import { useRouter } from "next/navigation";
import { accountSchema, type Account } from "@/lib/contracts";
import { ApiError, browserApi, errorMessage } from "@/lib/api";
import type { AppConfig } from "@/lib/config";
import { Button } from "./ui/button";

type Session = {
  config: AppConfig;
  account: Account | null;
  ready: boolean;
  status: "checking" | "ready" | "unavailable";
  epoch: number;
  api: ReturnType<typeof browserApi>;
  rotate: () => void;
  refresh: () => Promise<Account>;
  logout: () => Promise<void>;
};
const Context = createContext<Session | null>(null);

export function SessionProvider({
  config,
  children,
}: {
  config: AppConfig;
  children: React.ReactNode;
}) {
  const [account, setAccount] = useState<Account | null>(null),
    [status, setStatus] = useState<Session["status"]>("checking"),
    [epoch, setEpoch] = useState(0),
    [notice, setNotice] = useState("");
  const router = useRouter();
  const api = useMemo(
    () =>
      browserApi(config.browserApiOrigin, () => {
        setAccount(null);
        setStatus("ready");
        setNotice("");
        setEpoch((value) => value + 1);
      }),
    [config.browserApiOrigin],
  );
  const refresh = useCallback(async () => {
    setStatus("checking");
    try {
      const profile = await api.request("/api/v1/users/me", accountSchema);
      setAccount(profile);
      setStatus("ready");
      setNotice("");
      return profile;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) {
        setAccount(null);
        setStatus("ready");
        setNotice("");
      } else {
        setStatus("unavailable");
        setNotice(errorMessage(error));
      }
      throw error;
    }
  }, [api]);
  const rotate = () => {
    api.clear();
    setAccount(null);
    setEpoch((value) => value + 1);
  };
  useEffect(() => {
    let alive = true;
    void api
      .probe("/api/v1/users/me", accountSchema)
      .then((profile) => {
        if (alive) {
          setAccount(profile);
          setStatus("ready");
        }
      })
      .catch((error) => {
        if (!alive) return;
        if (error instanceof ApiError && error.status === 401) {
          setStatus("ready");
        } else {
          setStatus("unavailable");
          setNotice(errorMessage(error));
        }
      });
    return () => {
      alive = false;
    };
  }, [api]);
  const logout = async () => {
    try {
      if (!(await api.logout())) return;
      setAccount(null);
      setStatus("ready");
      setEpoch((value) => value + 1);
      setNotice("");
      router.replace(`${config.basePath}/`);
      router.refresh();
    } catch (error) {
      setNotice(errorMessage(error));
    }
  };
  return (
    <Context.Provider
      value={{
        config: { ...config, apiOrigin: config.browserApiOrigin },
        account,
        ready: status === "ready",
        status,
        epoch,
        api,
        rotate,
        refresh,
        logout,
      }}
    >
      {notice && (
        <p role="alert" className="notice">
          {notice}
          {status === "unavailable" && (
            <Button type="button" variant="outline" onClick={() => void refresh().catch(() => {})}>
              Retry session check
            </Button>
          )}
        </p>
      )}
      {children}
    </Context.Provider>
  );
}

export function useSession() {
  const value = useContext(Context);
  if (!value) throw new Error("OpenNest session provider is required");
  return value;
}
