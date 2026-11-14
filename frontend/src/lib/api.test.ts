import { describe, expect, it, vi } from "vitest";
import { z } from "zod";
import { ApiError, browserApi, readApi } from "./api";

describe("API privacy and session boundaries", () => {
  it("converts a rejected browser fetch to a safe service error", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockRejectedValue(new TypeError("Failed to fetch")),
    );
    await expect(
      readApi("http://localhost:8080", "/api/v1/users/me", z.unknown()),
    ).rejects.toEqual(new ApiError(503));
  });
  it("ignores a delayed logout after another session is installed", async () => {
    let finish: (response: Response) => void = () => {};
    let posted: () => void = () => {};
    const pending = new Promise<void>((resolve) => {
      posted = resolve;
    });
    let tokens = 0;
    const expired = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation((url: string) =>
        url.endsWith("csrf")
          ? Promise.resolve(
              new Response(
                JSON.stringify({
                  token: String(++tokens),
                  headerName: "X-CSRF-TOKEN",
                }),
              ),
            )
          : new Promise<Response>((resolve) => {
              finish = resolve;
              posted();
            }),
      ),
    );
    const api = browserApi("http://localhost:8080", expired);
    const logout = api.logout();
    await pending;
    api.clear();
    const fresh = await api.token();
    finish(new Response(null, { status: 204 }));
    expect(await logout).toBe(false);
    expect(await api.token()).toBe(fresh);
    expect(tokens).toBe(2);
    expect(expired).not.toHaveBeenCalled();
  });
  it("does not rotate an anonymous session when its initial account probe returns 401", async () => {
    const expired = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 401 })),
    );
    const api = browserApi("http://localhost:8080", expired);
    await expect(
      api.probe("/api/v1/users/me", z.unknown()),
    ).rejects.toMatchObject({ status: 401 });
    expect(expired).not.toHaveBeenCalled();
  });
  it("uses no-store and validates external responses", async () => {
    const fetch = vi
      .fn()
      .mockResolvedValue(new Response(JSON.stringify({ count: "invalid" })));
    vi.stubGlobal("fetch", fetch);
    await expect(
      readApi(
        "http://localhost:8080",
        "/api/v1/listings/search",
        z.object({ count: z.number() }),
      ),
    ).rejects.toMatchObject({ status: 502 });
    expect(fetch.mock.calls[0][1]).toMatchObject({
      cache: "no-store",
      redirect: "error",
    });
  });
  it("does not expose provider response bodies", async () => {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValue(
          new Response("private provider detail", { status: 503 }),
        ),
    );
    await expect(
      readApi("http://localhost:8080", "/api/v1/users/me", z.unknown()),
    ).rejects.toThrow(new ApiError(503).message);
  });
  it("fetches new CSRF after session rotation", async () => {
    const fetch = vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        new Response(
          JSON.stringify(
            url.endsWith("csrf")
              ? {
                  token: String(fetch.mock.calls.length),
                  headerName: "X-CSRF-TOKEN",
                }
              : { ok: true },
          ),
        ),
      ),
    );
    vi.stubGlobal("fetch", fetch);
    const api = browserApi("http://localhost:8080", vi.fn());
    await api.request(
      "/api/v1/action",
      z.object({ ok: z.boolean() }),
      "POST",
      {},
    );
    api.clear();
    await api.request(
      "/api/v1/action",
      z.object({ ok: z.boolean() }),
      "POST",
      {},
    );
    expect(
      fetch.mock.calls.filter(([url]) => url.endsWith("csrf")),
    ).toHaveLength(2);
    expect(fetch.mock.calls[1][1]).toMatchObject({
      credentials: "include",
      headers: { "X-CSRF-TOKEN": "1" },
    });
    expect(fetch.mock.calls[3][1]).toMatchObject({
      headers: { "X-CSRF-TOKEN": "3" },
    });
  });
  it("rejects private results that arrive after a session change", async () => {
    let resolve: (response: Response) => void = () => {};
    vi.stubGlobal(
      "fetch",
      vi.fn().mockImplementation(
        () =>
          new Promise<Response>((done) => {
            resolve = done;
          }),
      ),
    );
    const api = browserApi("http://localhost:8080", vi.fn());
    const result = api.request(
      "/api/v1/users/me",
      z.object({ id: z.string() }),
    );
    api.clear();
    resolve(new Response(JSON.stringify({ id: "old-persona" })));
    await expect(result).rejects.toMatchObject({ status: 401 });
  });
  it("drops stale CSRF after a forbidden logout and obtains a new token on retry", async () => {
    let attempts = 0;
    const fetch = vi.fn().mockImplementation((url: string) =>
      Promise.resolve(
        url.endsWith("csrf")
          ? new Response(
              JSON.stringify({
                token: String(++attempts),
                headerName: "X-CSRF-TOKEN",
              }),
            )
          : new Response(null, { status: attempts === 1 ? 403 : 204 }),
      ),
    );
    vi.stubGlobal("fetch", fetch);
    const api = browserApi("http://localhost:8080", vi.fn());
    await expect(api.logout()).rejects.toMatchObject({ status: 403 });
    await api.logout();
    expect(attempts).toBe(2);
  });
  it("clears private session state when logout reports expiry", async () => {
    const expired = vi.fn();
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockImplementation((url: string) =>
          Promise.resolve(
            url.endsWith("csrf")
              ? new Response(
                  JSON.stringify({ token: "test", headerName: "X-CSRF-TOKEN" }),
                )
              : new Response(null, { status: 401 }),
          ),
        ),
    );
    const api = browserApi("http://localhost:8080", expired);
    await expect(api.logout()).rejects.toMatchObject({ status: 401 });
    expect(expired).toHaveBeenCalledOnce();
  });
});
