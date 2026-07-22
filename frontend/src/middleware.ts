import { NextRequest, NextResponse } from "next/server";
import { SESSION_HINT_COOKIE_NAME } from "@/lib/api-client";

// Redirects an already-signed-in visitor away from public/auth routes to /dashboard before
// any HTML renders. The cookie is a UX hint only — AuthGuard and the backend remain the sole
// authority on whether the session is actually valid, so a stale hint just costs one extra
// redirect hop (this route → /dashboard → AuthGuard bounces back to /login).
export function middleware(request: NextRequest) {
  if (request.cookies.has(SESSION_HINT_COOKIE_NAME)) {
    return NextResponse.redirect(new URL("/dashboard", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/", "/login", "/register"],
};
