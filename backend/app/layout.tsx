import type { Metadata } from "next";
import "./globals.css";
import LocaleProvider from "./components/LocaleProvider";
import { getServerLocale } from "@/lib/getServerLocale";
import { getDictionary } from "@/lib/i18n";

const BASE_URL = "https://secretarykim.net";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  return {
    metadataBase: new URL(BASE_URL),
    title: dict.meta.title,
    description: dict.meta.description,
    openGraph: {
      title: dict.meta.title,
      description: dict.meta.description,
      url: "/",
      siteName: dict.brand,
      images: ["/logo.png"],
      locale: locale === "ko" ? "ko_KR" : "en_US",
      type: "website",
    },
    twitter: {
      card: "summary",
      title: dict.meta.title,
      description: dict.meta.description,
      images: ["/logo.png"],
    },
    verification: {
      google: process.env.GOOGLE_SITE_VERIFICATION || undefined,
      other: process.env.NAVER_SITE_VERIFICATION
        ? { "naver-site-verification": process.env.NAVER_SITE_VERIFICATION }
        : undefined,
    },
  };
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  const locale = await getServerLocale();

  return (
    <html lang={locale} data-scroll-behavior="smooth">
      <body>
        <LocaleProvider initialLocale={locale}>{children}</LocaleProvider>
      </body>
    </html>
  );
}
