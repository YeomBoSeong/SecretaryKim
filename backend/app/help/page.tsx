import type { Metadata } from "next";
import PageShell from "../components/PageShell";
import { getServerLocale } from "@/lib/getServerLocale";
import { getDictionary } from "@/lib/i18n";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  return { title: dict.help.meta.title, description: dict.help.meta.description };
}

export default async function HelpPage() {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  const d = dict.help;

  return (
    <PageShell>
      <section className="section section-wide">
        <h2 className="section-title">{d.heading}</h2>

        <div className="intro-block">
          <h3>{d.firstUseHeading}</h3>
          <p>{d.firstUseBody}</p>
        </div>

        <div className="intro-block">
          <h3>{d.faqHeading}</h3>
          <div className="faq">
            {d.faqs.map((faq, i) => (
              <div className="faq-item" key={i}>
                <p className="faq-q">{faq.q}</p>
                <p className="faq-a">{faq.a}</p>
              </div>
            ))}
          </div>
        </div>
      </section>
    </PageShell>
  );
}
