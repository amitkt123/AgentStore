import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { SESSION_COOKIE } from "../session";

export class ApiError extends Error {
  status: number;
  code: string | null;
  details: Record<string, unknown>;
  constructor(status: number, code: string | null, message: string, details: Record<string, unknown> = {}) {
    super(message);
    this.status = status;
    this.code = code;
    this.details = details;
  }
}

export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const token = cookies().get(SESSION_COOKIE)?.value;
  const headers: Record<string, string> = { ...((init.headers as Record<string, string>) ?? {}) };
  if (typeof init.body === "string") headers["Content-Type"] = "application/json";
  if (token) headers.Authorization = `Bearer ${token}`;
  return fetch(`${process.env.BACKEND_URL}${path}`, { ...init, headers, cache: "no-store" });
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const res = await apiFetch(path, init);
  if (res.status === 401) redirect("/login?expired=1");
  if (!res.ok) {
    const body = (await res.json().catch(() => null)) as ({ code?: string; message?: string } & Record<string, unknown>) | null;
    throw new ApiError(res.status, body?.code ?? null, body?.message || `Request failed (${res.status})`, body ?? {});
  }
  if (res.status === 204) return undefined as T;
  return (await res.json()) as T;
}

export const json = (body: unknown) => JSON.stringify(body);
