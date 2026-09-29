import { NextResponse } from "next/server";
import { ApiError } from "@/lib/api/http";
import { getRunSnapshot } from "@/lib/api/runs";

export async function GET(_req: Request, { params }: { params: { id: string } }) {
  try {
    return NextResponse.json(await getRunSnapshot(params.id));
  } catch (e) {
    // api() redirects on 401 by throwing NEXT_REDIRECT, which must propagate.
    if (e instanceof ApiError) return NextResponse.json({ message: e.message }, { status: e.status });
    if (e instanceof Error && e.message === "NEXT_REDIRECT") return new NextResponse(null, { status: 401 });
    throw e;
  }
}
