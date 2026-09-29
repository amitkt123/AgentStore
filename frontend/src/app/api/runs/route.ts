import { NextRequest, NextResponse } from "next/server";
import { apiFetch } from "@/lib/api/http";

export async function GET(req: NextRequest) {
  const res = await apiFetch(`/api/v1/runs${req.nextUrl.search}`);
  const body = await res.text();
  return new NextResponse(body || null, { status: res.status, headers: { "Content-Type": "application/json" } });
}
