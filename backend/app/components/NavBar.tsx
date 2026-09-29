"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useState } from "react";
import { useLocale } from "./LocaleProvider";

export default function NavBar() {
  const pathname = usePathname();
  const [open, setOpen] = useState(false);
  const { locale, dict, setLocale } = useLocale();

  const otherLocale = locale === "ko" ? "en" : "ko";

  return (
    <header className="navbar">
      <div className="navbar-inner">
        <Link href="/" className="navbar-brand" onClick={() => setOpen(false)}>
          <img src="/logo.png" alt="" className="navbar-logo" />
          {dict.brand}
        </Link>

        <nav className="navbar-links-desktop">
          {dict.nav.items.map((item) => (
            <Link
              key={item.href}
              href={item.href}
              className={pathname === item.href ? "navbar-link-active" : ""}
            >
              {item.label}
            </Link>
          ))}
        </nav>

        <div className="navbar-actions">
          <button
            type="button"
            className="navbar-lang-toggle"
            onClick={() => setLocale(otherLocale)}
          >
            {dict.nav.langToggle}
          </button>

          <button
            type="button"
            className={`navbar-toggle${open ? " navbar-toggle-open" : ""}`}
            aria-label={open ? dict.nav.closeMenu : dict.nav.openMenu}
            aria-expanded={open}
            onClick={() => setOpen((v) => !v)}
          >
            <span />
            <span />
            <span />
          </button>
        </div>
      </div>

      <nav className={`navbar-links-mobile${open ? " navbar-links-mobile-open" : ""}`}>
        {dict.nav.items.map((item) => (
          <Link
            key={item.href}
            href={item.href}
            className={pathname === item.href ? "navbar-link-active" : ""}
            onClick={() => setOpen(false)}
          >
            {item.label}
          </Link>
        ))}
      </nav>
    </header>
  );
}
