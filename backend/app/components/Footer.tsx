"use client";

import { useLocale } from "./LocaleProvider";

export default function Footer() {
  const { dict } = useLocale();

  return (
    <footer className="footer">
      <div className="footer-inner">
        <p className="footer-meta">
          {dict.footer.contactLabel}: <a href="mailto:secretary245@gmail.com">secretary245@gmail.com</a>
          <br />
          {dict.footer.note}
        </p>

        <p className="footer-copyright">{dict.footer.copyright}</p>
      </div>
    </footer>
  );
}
