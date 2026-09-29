import type { Metadata } from "next";
import PageShell from "../components/PageShell";
import { getServerLocale } from "@/lib/getServerLocale";
import { getDictionary } from "@/lib/i18n";

export async function generateMetadata(): Promise<Metadata> {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  return { title: dict.download.meta.title, description: dict.download.meta.description };
}

// BYOK(자기 키 지참) 전환으로 앱이 자체 API 키를 재판매하지 않게 되어 배포를 재개함.
const DISTRIBUTION_SUSPENDED = false;

export default async function DownloadPage() {
  const locale = await getServerLocale();
  const dict = getDictionary(locale);
  const d = dict.download;
  const contact = d.warningContact.replace(
    "{email}",
    '<a href="mailto:secretary245@gmail.com">secretary245@gmail.com</a>'
  );

  if (DISTRIBUTION_SUSPENDED) {
    return (
      <PageShell>
        <section className="section section-wide">
          <h2 className="section-title">{d.heading}</h2>

          <div className="security-callout">
            <p className="security-callout-title">{d.suspendedTitle}</p>
            <p>{d.suspendedBody}</p>
            <p className="security-callout-contact" dangerouslySetInnerHTML={{ __html: contact }} />
          </div>
        </section>
      </PageShell>
    );
  }

  return (
    <PageShell>
      <section className="section section-wide">
        <h2 className="section-title">{d.heading}</h2>

        <div className="security-callout">
          <p className="security-callout-title">{d.warningTitle}</p>
          <p>{d.warningBody}</p>
          <p className="security-callout-contact" dangerouslySetInnerHTML={{ __html: contact }} />
        </div>

        <div className="download-block">
          <a href="/api/download" className="download-button">
            {d.downloadButton}
          </a>
          <p className="version-label">{d.versionLabel}</p>
          <p className="version-note">{d.versionNote}</p>
        </div>

        <div className="intro-block">
          <h3>{d.stepsHeading}</h3>
          <ol className="install-steps">
            {d.steps.map((step, i) => (
              <li key={i} dangerouslySetInnerHTML={{ __html: step }} />
            ))}
          </ol>
        </div>

        <div className="intro-block">
          <h3>{d.previewHeading}</h3>
          <p className="section-sub">{d.previewSub}</p>

          {d.guides.map((guide, i) => (
            <div className="guide-card" key={i}>
              <h4>{guide.heading}</h4>
              <p dangerouslySetInnerHTML={{ __html: guide.body }} />
              <img src={guide.image} alt={guide.imageAlt} className="guide-image" />
              {guide.note && <p dangerouslySetInnerHTML={{ __html: guide.note }} />}
            </div>
          ))}
        </div>
      </section>
    </PageShell>
  );
}
