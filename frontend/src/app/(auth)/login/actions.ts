"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { decodeSessionToken, roleHome, SESSION_COOKIE } from "@/lib/session";
import type { AuthResponse } from "@/lib/types";

export interface LoginState {
  error: string | null;
}

export async function loginAction(_prevState: LoginState, formData: FormData): Promise<LoginState> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password }),
  });
  if (!res.ok) return { error: "Invalid email or password" };

  const { token } = (await res.json()) as AuthResponse;
  const claims = decodeSessionToken(token);
  if (!claims) return { error: "Login succeeded but the session could not be read" };

  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect(roleHome(claims.role));
}
