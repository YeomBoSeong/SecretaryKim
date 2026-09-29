import { NextRequest, NextResponse } from "next/server";
import {
  LOCALE_COOKIE,
  defaultLocale,
  localeFromAcceptLanguage,
  SEARCH_BOT_USER_AGENT_PATTERN,
} from "./lib/i18n";

export function middleware(request: NextRequest) {
  const response = NextResponse.next();

  if (!request.cookies.has(LOCALE_COOKIE)) {
    const userAgent = request.headers.get("user-agent") ?? "";
    const locale = SEARCH_BOT_USER_AGENT_PATTERN.test(userAgent)
      ? defaultLocale
      : localeFromAcceptLanguage(request.headers.get("accept-language"));
    response.cookies.set(LOCALE_COOKIE, locale, {
      path: "/",
      maxAge: 60 * 60 * 24 * 365,
      sameSite: "lax",
    });
  }

  return response;
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|api|.*\\.(?:png|jpg|jpeg|svg|gif|webp|mp4|ico)$).*)"],
};
