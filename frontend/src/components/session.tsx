"use client";

import {
  createContext,
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
  logout: () => Promise<void>;
};
const Context = createContext<Session | null>(null);
const SESSION_CHECK_TIMEOUT_MS = 8_000;

function createSessionClient(
  origin: string,
  update: {
    ready: (account: Account | null) => void;
    expired: () => void;
    unavailable: (error: unknown) => void;
  },
) {
  let activeCheck: { timeout: ReturnType<typeof setTimeout> } | null = null;
  function cancelCheck() {
    if (activeCheck) clearTimeout(activeCheck.timeout);
    activeCheck = null;
  }
  const api = browserApi(origin, () => {
    cancelCheck();
    update.expired();
  });
  function checkSession(refreshing = false) {
    cancelCheck();
    const check = {
      timeout: setTimeout(() => {
        if (activeCheck !== check) return;
        activeCheck = null;
        update.unavailable(new ApiError(503));
      }, SESSION_CHECK_TIMEOUT_MS),
    };
    activeCheck = check;
    void api
      .probe("/api/v1/users/me", accountSchema)
      .then((profile) => {
        if (activeCheck === check) update.ready(profile);
      })
      .catch((error) => {
        if (activeCheck !== check) return;
        if (error instanceof ApiError && error.status === 401) {
          if (refreshing) {
            api.clear();
            update.expired();
          } else {
            update.ready(null);
          }
        } else {
          update.unavailable(error);
        }
      })
      .finally(() => {
        if (activeCheck === check) cancelCheck();
      });
  }
  return { api, cancelCheck, checkSession };
}

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
  const { api, cancelCheck, checkSession } = useMemo(() => {
    function ready(profile: Account | null) {
      setAccount(profile);
      setStatus("ready");
      setNotice("");
    }
    return createSessionClient(config.browserApiOrigin, {
      ready,
      expired: () => {
        ready(null);
        setEpoch((value) => value + 1);
      },
      unavailable: (error) => {
        setStatus("unavailable");
        setNotice(errorMessage(error));
      },
    });
  }, [config.browserApiOrigin]);
  const startProfileCheck = () => {
    setStatus("checking");
    checkSession(true);
  };
  const rotate = () => {
    api.clear();
    setAccount(null);
    setNotice("");
    setEpoch((value) => value + 1);
    startProfileCheck();
  };
  useEffect(() => {
    checkSession();
    return cancelCheck;
  }, [cancelCheck, checkSession]);
  const logout = async () => {
    try {
      if (!(await api.logout())) return;
      cancelCheck();
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
        logout,
      }}
    >
      {notice && (
        <p role="alert" className="notice">
          {notice}
          {status === "unavailable" && (
            <Button type="button" variant="outline" onClick={startProfileCheck}>
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
