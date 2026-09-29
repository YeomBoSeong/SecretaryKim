import type { Metadata } from "next";
import PageShell from "../components/PageShell";
import ReviewBoard from "../components/ReviewBoard";
import { getServerLocale } from "@/lib/getServerLocale";
import { getDictionary } from "@/lib/i18n";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  return { title: dict.reviews.meta.title, description: dict.reviews.meta.description };
}

export default function ReviewsPage() {
  return (
    <PageShell>
      <ReviewBoard />
    </PageShell>
  );
}
