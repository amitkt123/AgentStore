"use server";
import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { authApi } from "@/lib/api/auth";
import type { AuthResponse } from "@/lib/api/types";
import { safeNext } from "@/lib/safeNext";
import { REFRESH_COOKIE, clearSession, writeSession } from "@/lib/session";

export type FormState = { error?: string; ok?: string };

async function messageOf(res: Response, fallback: string): Promise<string> {
  const body = (await res.json().catch(() => null)) as { message?: string } | null;
  return body?.message || fallback;
}

export async function loginAction(_prev: FormState, fd: FormData): Promise<FormState> {
  const res = await authApi.login({
    email: String(fd.get("email") ?? ""),
    password: String(fd.get("password") ?? ""),
    remember: fd.get("remember") === "on",
  });
  if (res.status === 401) return { error: "That email and password don't match." };
  if (!res.ok) return { error: await messageOf(res, "We couldn't sign you in. Try again.") };
  writeSession(cookies(), (await res.json()) as AuthResponse);
  redirect(safeNext(String(fd.get("next") ?? "")));
}

export async function signupAction(_prev: FormState, fd: FormData): Promise<FormState> {
  const res = await authApi.signup({
    fullName: String(fd.get("fullName") ?? ""),
    orgName: String(fd.get("orgName") ?? ""),
    email: String(fd.get("email") ?? ""),
    password: String(fd.get("password") ?? ""),
    side: fd.get("side") === "DEVELOPER" ? "DEVELOPER" : "BUYER",
  });
  if (res.status === 409) return { error: "An account with that email already exists. Sign in instead." };
  if (!res.ok) return { error: await messageOf(res, "We couldn't create your account. Check the details and try again.") };
  writeSession(cookies(), (await res.json()) as AuthResponse);
  redirect("/home");
}

export async function forgotAction(_prev: FormState, fd: FormData): Promise<FormState> {
  await authApi.forgot(String(fd.get("email") ?? ""));
  return { ok: "If that email has an account, a reset link is on its way." };
}

export async function resetAction(_prev: FormState, fd: FormData): Promise<FormState> {
  const res = await authApi.reset(String(fd.get("token") ?? ""), String(fd.get("password") ?? ""));
  if (!res.ok) return { error: await messageOf(res, "That reset link is invalid or has expired.") };
  redirect("/login");
}

export async function logoutAction() {
  const jar = cookies();
  const refresh = jar.get(REFRESH_COOKIE)?.value;
  if (refresh) await authApi.logout(refresh).catch(() => null);
  clearSession(jar);
  redirect("/login");
}
