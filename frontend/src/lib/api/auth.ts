import { apiFetch } from "./http";

const post = (path: string, body: unknown) =>
  fetch(`${process.env.BACKEND_URL}/api/v1/auth${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
    cache: "no-store",
  });

export const authApi = {
  login: (b: { email: string; password: string; remember: boolean }) => post("/login", b),
  signup: (b: { fullName: string; orgName: string; email: string; password: string; side: "BUYER" | "DEVELOPER" }) => post("/signup", b),
  verifyEmail: (token: string) => post("/email/verify", { token }),
  resendVerification: () => apiFetch("/api/v1/auth/email/resend", { method: "POST" }),
  forgot: (email: string) => post("/password/forgot", { email }),
  reset: (token: string, password: string) => post("/password/reset", { token, password }),
  logout: (refreshToken: string) => post("/logout", { refreshToken }),
};
