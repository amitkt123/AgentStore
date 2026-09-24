"use server";

import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { decodeSessionToken, roleHome, SESSION_COOKIE } from "@/lib/session";
import type { AuthResponse } from "@/lib/types";

export interface LoginState {
  error: string | null;
  /** True once the backend reports the account has no workspace left — the form should ask for one. */
  needsWorkspace: boolean;
}

export async function loginAction(_prevState: LoginState, formData: FormData): Promise<LoginState> {
  const email = String(formData.get("email") ?? "");
  const password = String(formData.get("password") ?? "");
  const sideValue = formData.get("side");
  const side = sideValue === "DEVELOPER" || sideValue === "BUYER" ? sideValue : undefined;
  const orgNameValue = formData.get("orgName");
  const orgName = orgNameValue ? String(orgNameValue) : undefined;

  const res = await fetch(`${process.env.BACKEND_URL}/api/v1/auth/login`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, ...(side && orgName ? { side, orgName } : {}) }),
  });

  if (res.status === 422) {
    const body = (await res.json().catch(() => null)) as { code?: string } | null;
    if (body?.code === "NO_WORKSPACE") {
      return {
        error: "You're not a member of any workspace any more — create one to continue",
        needsWorkspace: true,
      };
    }
    return { error: "Could not sign in — check your details", needsWorkspace: false };
  }
  if (!res.ok) return { error: "Invalid email or password", needsWorkspace: false };

  const { token } = (await res.json()) as AuthResponse;
  const claims = decodeSessionToken(token);
  if (!claims) return { error: "Login succeeded but the session could not be read", needsWorkspace: false };

  (await cookies()).set(SESSION_COOKIE, token, { httpOnly: true, sameSite: "lax", path: "/" });
  redirect(roleHome(claims.role));
}
