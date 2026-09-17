"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { decodeSessionToken, roleHome, SESSION_COOKIE } from "@/lib/session";
import type { AuthResponse } from "@/lib/types";

export interface SignupState {
  error: string | null;
}

export async function signupAction(_prevState: SignupState, formData: FormData): Promise<SignupState> {
  const orgName = String(formData.get("orgName") ?? "");
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");

  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/signup`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ orgName, orgType: "BUYER", email, password, role: "BUYER_ADMIN" }),
  });
  if (!res.ok) return { error: "Could not create an account — check your details" };

  const { token } = (await res.json()) as AuthResponse;
  const claims = decodeSessionToken(token);
  if (!claims) return { error: "Signup succeeded but the session could not be read" };

  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect(roleHome(claims.role));
}
