import { NextResponse } from "next/server";
import { apiFetch } from "@/lib/api/http";
import { parseMonth } from "@/lib/month";

// The browser cannot attach the bearer token, so the statement download is proxied here with the session's auth.
export async function GET(_req: Request, { params }: { params: { month: string } }) {
  const month = parseMonth(params.month);
  if (!month) return NextResponse.json({ message: "Month must be YYYY-MM" }, { status: 400 });
  const res = await apiFetch(`/api/v1/developer/statements/${month}/download`);
  if (!res.ok) {
    const body = await res.text();
    return new NextResponse(body, { status: res.status, headers: { "Content-Type": res.headers.get("Content-Type") ?? "application/json" } });
  }
  const headers = new Headers();
  for (const name of ["Content-Type", "Content-Disposition"]) {
    const value = res.headers.get(name);
    if (value) headers.set(name, value);
  }
  return new NextResponse(res.body, { status: 200, headers });
}
