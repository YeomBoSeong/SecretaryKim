import type { Metadata } from "next";
import PageShell from "../components/PageShell";
import { getServerLocale } from "@/lib/getServerLocale";
import { getDictionary } from "@/lib/i18n";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  return { title: dict.intro.meta.title, description: dict.intro.meta.description };
}

export default async function IntroPage() {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  const d = dict.intro;

  return (
    <PageShell>
      <section className="section section-wide">
        <h2 className="section-title">{d.heading}</h2>

        <div className="intro-video">
          <video controls playsInline preload="metadata" poster="/logo.png">
            <source src="/videos/demo.mp4" type="video/mp4" />
          </video>
        </div>

        <div className="intro-block">
          <h3>{d.whyHeading}</h3>
          <p>{d.whyBody}</p>
        </div>

        <div className="intro-block">
          <h3>{d.featuresHeading}</h3>
          <ul className="feature-list">
            {d.features.map((f, i) => (
              <li key={i}>
                <span className="feature-icon">{f.icon}</span>
                {f.text}
              </li>
            ))}
          </ul>
        </div>

        <div className="intro-block">
          <h3>{d.usecasesHeading}</h3>
          <ul className="usecase-list">
            {d.usecases.map((u, i) => (
              <li key={i}>{u}</li>
            ))}
          </ul>
        </div>
      </section>
    </PageShell>
  );
}
