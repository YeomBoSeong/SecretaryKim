import type { Metadata } from "next";
import PageShell from "../components/PageShell";
import QnaBoard from "../components/QnaBoard";
import { getServerLocale } from "@/lib/getServerLocale";
import { getDictionary } from "@/lib/i18n";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  return { title: dict.qna.meta.title, description: dict.qna.meta.description };
}

export default function QnaPage() {
  return (
    <PageShell>
      <QnaBoard />
    </PageShell>
  );
}
