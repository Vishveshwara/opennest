import { z } from "zod";
import { csrfSchema } from "./contracts";

const messages: Record<number, string> = {
  400: "Check the fields and try again.",
  401: "Your session ended. Sign in again, or select a sandbox persona.",
  403: "This action is unavailable. Check your permissions and verified contact.",
  404: "This listing or resource is unavailable.",
  409: "This action conflicts with the current listing or account state. Refresh and review before trying again.",
  413: "This file or request is too large.",
  429: "The allowance or request limit was reached. Please try again later.",
  503: "OpenNest cannot reach a required service. Please try again later.",
};

export class ApiError extends Error {
  constructor(readonly status: number) {
    super(
      messages[status] ??
        "OpenNest could not complete this request. Please try again.",
    );
  }
}

export async function readApi<T>(
  origin: string,
  path: string,
  schema: z.ZodType<T>,
  options: RequestInit = {},
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`${origin}${path}`, {
      ...options,
      cache: "no-store",
      redirect: "error",
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === "AbortError")
      throw error;
    throw new ApiError(503);
  }
  if (!response.ok) throw new ApiError(response.status);
  try {
    return schema.parse(await response.json());
  } catch {
    throw new ApiError(502);
  }
}

export function browserApi(origin: string, onExpired: () => void) {
  let csrf: z.infer<typeof csrfSchema> | null = null;
  let generation = 0;
  const clear = () => {
    generation++;
    csrf = null;
  };
  async function token() {
    if (csrf) return csrf;
    const started = generation;
    const result = await readApi(origin, "/api/v1/auth/csrf", csrfSchema, {
      credentials: "include",
    });
    if (started !== generation) throw new ApiError(401);
    csrf = result;
    return result;
  }
  async function request<T>(
    path: string,
    schema: z.ZodType<T>,
    method = "GET",
    body?: unknown,
    signal?: AbortSignal,
    notifyExpired = true,
  ): Promise<T> {
    const started = generation;
    try {
      const headers: Record<string, string> = {};
      if (method !== "GET") {
        const value = await token();
        headers[value.headerName] = value.token;
        headers["Content-Type"] = "application/json";
      }
      const result = await readApi(origin, path, schema, {
        method,
        headers,
        body: body === undefined ? undefined : JSON.stringify(body),
        credentials: "include",
        signal,
      });
      if (started !== generation) throw new ApiError(401);
      return result;
    } catch (error) {
      if (
        error instanceof ApiError &&
        error.status === 401 &&
        started === generation &&
        notifyExpired
      ) {
        clear();
        onExpired();
      }
      if (error instanceof ApiError && error.status === 403) csrf = null;
      throw error;
    }
  }
  async function logout() {
    const started = generation;
    const value = await token();
    if (started !== generation) return false;
    let response: Response;
    try {
      response = await fetch(`${origin}/api/v1/auth/logout`, {
        method: "POST",
        credentials: "include",
        cache: "no-store",
        headers: { [value.headerName]: value.token },
        redirect: "error",
      });
    } catch {
      throw new ApiError(503);
    }
    if (started !== generation) return false;
    if (!response.ok) {
      if (response.status === 401) {
        clear();
        onExpired();
      }
      if (response.status === 403) csrf = null;
      throw new ApiError(response.status);
    }
    clear();
    return true;
  }
  const probe = <T>(path: string, schema: z.ZodType<T>) =>
    request(path, schema, "GET", undefined, undefined, false);
  return { request, probe, clear, token, logout };
}

export function errorMessage(error: unknown) {
  return error instanceof ApiError
    ? error.message
    : "OpenNest could not complete this request. Please try again.";
}
