import { beforeEach, describe, expect, it, vi } from "vitest";

const cookieStore = { set: vi.fn() };
vi.mock("next/headers", () => ({
  cookies: vi.fn(async () => cookieStore),
}));

const redirect = vi.fn();
vi.mock("next/navigation", () => ({
  redirect: (...args: unknown[]) => redirect(...args),
}));

function fakeJwt(payload: Record<string, unknown>): string {
  const base64url = (obj: unknown) =>
    Buffer.from(JSON.stringify(obj)).toString("base64").replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
  return `${base64url({ alg: "HS256" })}.${base64url(payload)}.fake-signature`;
}

function formOf(fields: Record<string, string>): FormData {
  const form = new FormData();
  for (const [key, value] of Object.entries(fields)) form.append(key, value);
  return form;
}

function jsonResponse(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

describe("loginAction", () => {
  beforeEach(() => {
    vi.stubGlobal("fetch", vi.fn());
    cookieStore.set.mockClear();
    redirect.mockClear();
    process.env.BACKEND_URL = "http://backend.test";
  });

  it("logs a member in and redirects to their role home, without side/orgName in the request", async () => {
    const { loginAction } = await import("./actions");
    const token = fakeJwt({ sub: "u1", orgId: "o1", role: "BUYER_ADMIN" });
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { token }));

    await loginAction({ error: null, needsWorkspace: false }, formOf({ email: "a@acme.example", password: "pw" }));

    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(JSON.parse(init!.body as string)).toEqual({ email: "a@acme.example", password: "pw" });
    expect(cookieStore.set).toHaveBeenCalledWith("karya_session", token, expect.objectContaining({ httpOnly: true }));
    expect(redirect).toHaveBeenCalledWith("/catalog");
  });

  it("reports NO_WORKSPACE as an explanatory, resumable error instead of a bare failure", async () => {
    const { loginAction } = await import("./actions");
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(422, { code: "NO_WORKSPACE", message: "no workspace" }));

    const result = await loginAction(
      { error: null, needsWorkspace: false },
      formOf({ email: "orphan@acme.example", password: "pw" }),
    );

    expect(result.needsWorkspace).toBe(true);
    expect(result.error).toMatch(/create one to continue/i);
    expect(cookieStore.set).not.toHaveBeenCalled();
    expect(redirect).not.toHaveBeenCalled();
  });

  it("sends side and orgName once the user fills in the workspace picker, and logs them into the new org", async () => {
    const { loginAction } = await import("./actions");
    const token = fakeJwt({ sub: "u1", orgId: "o2", role: "DEVELOPER" });
    vi.mocked(fetch).mockResolvedValueOnce(jsonResponse(200, { token }));

    await loginAction(
      { error: "You're not a member of any workspace any more — create one to continue", needsWorkspace: true },
      formOf({ email: "orphan@acme.example", password: "pw", side: "DEVELOPER", orgName: "Fresh Start Co" }),
    );

    const [, init] = vi.mocked(fetch).mock.calls[0];
    expect(JSON.parse(init!.body as string)).toEqual({
      email: "orphan@acme.example",
      password: "pw",
      side: "DEVELOPER",
      orgName: "Fresh Start Co",
    });
    expect(redirect).toHaveBeenCalledWith("/dashboard");
  });

  it("treats a wrong password as an ordinary failure, never NO_WORKSPACE, even with workspace fields present", async () => {
    const { loginAction } = await import("./actions");
    vi.mocked(fetch).mockResolvedValueOnce(new Response(null, { status: 401 }));

    const result = await loginAction(
      { error: null, needsWorkspace: false },
      formOf({ email: "orphan@acme.example", password: "wrong", side: "DEVELOPER", orgName: "Fresh Start Co" }),
    );

    expect(result).toEqual({ error: "Invalid email or password", needsWorkspace: false });
  });
});
