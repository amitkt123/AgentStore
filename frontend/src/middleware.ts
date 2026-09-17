import { NextRequest, NextResponse } from "next/server";
import { decodeSessionToken, SESSION_COOKIE } from "@/lib/session";
import { resolveRedirect } from "@/lib/routing";

export function middleware(request: NextRequest) {
  const token = request.cookies.get(SESSION_COOKIE)?.value;
  const claims = token ? decodeSessionToken(token) : null;
  const redirectTo = resolveRedirect(request.nextUrl.pathname, claims);
  if (redirectTo) return NextResponse.redirect(new URL(redirectTo, request.url));
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!api|_next/static|_next/image|favicon.ico).*)"],
};
