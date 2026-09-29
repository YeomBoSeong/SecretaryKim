import { cookies } from "next/headers";
import { LOCALE_COOKIE, Locale, resolveLocale } from "./i18n";

export async function getServerLocale(): Promise<Locale> {
  const store = await cookies();
  return resolveLocale(store.get(LOCALE_COOKIE)?.value);
}
