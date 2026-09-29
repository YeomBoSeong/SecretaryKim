"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import NavBar from "./components/NavBar";
import Footer from "./components/Footer";
import { useLocale } from "./components/LocaleProvider";

function HeroSplash({ onDone }: { onDone: () => void }) {
  const [leaving, setLeaving] = useState(false);
  const { dict } = useLocale();

  useEffect(() => {
    const leaveTimer = setTimeout(() => setLeaving(true), 1700);
    const doneTimer = setTimeout(onDone, 2300);
    return () => {
      clearTimeout(leaveTimer);
      clearTimeout(doneTimer);
    };
  }, [onDone]);

  return (
    <div className={`splash${leaving ? " splash-leaving" : ""}`}>
      <p className="splash-sub">{dict.home.splashSub}</p>
      <h1 className="splash-title">{dict.home.splashTitle}</h1>
    </div>
  );
}

function PhoneMockup({ src, alt }: { src: string; alt: string }) {
  return (
    <div className="phone">
      <div className="phone-notch" />
      <img src={src} alt={alt} className="phone-screen" />
    </div>
  );
}

function HeroSlider() {
  const [index, setIndex] = useState(0);
  const { dict } = useLocale();
  const slides = dict.home.slides;

  useEffect(() => {
    setIndex(0);
  }, [slides]);

  useEffect(() => {
    const timer = setInterval(() => {
      setIndex((i) => (i + 1) % slides.length);
    }, 4200);
    return () => clearInterval(timer);
  }, [slides.length]);

  const go = (delta: number) => {
    setIndex((i) => (i + delta + slides.length) % slides.length);
  };

  return (
    <div className="slider">
      <div className="slider-track">
        {slides.map((slide, i) => (
          <div
            key={slide.eyebrow}
            className={`slide slide-tone-${["blue", "purple", "teal"][i % 3]}${
              i === index ? " slide-active" : ""
            }`}
          >
            <div className="slide-content">
              <p className="slide-eyebrow">{slide.eyebrow}</p>
              <h2 className="slide-title">
                {slide.title.split("\n").map((line) => (
                  <span key={line} className="slide-title-line">
                    {line}
                  </span>
                ))}
              </h2>
              <p className="slide-desc">{slide.desc}</p>
              <Link href="/download" className="slide-cta">
                {dict.home.cta}
              </Link>
            </div>
            <div className="slide-visual">
              <PhoneMockup src={slide.image} alt={slide.eyebrow} />
            </div>
          </div>
        ))}
      </div>

      <button
        type="button"
        className="slider-arrow slider-arrow-prev"
        aria-label={dict.home.prevSlide}
        onClick={() => go(-1)}
      >
        ‹
      </button>
      <button
        type="button"
        className="slider-arrow slider-arrow-next"
        aria-label={dict.home.nextSlide}
        onClick={() => go(1)}
      >
        ›
      </button>

      <div className="slider-dots">
        {slides.map((slide, i) => (
          <button
            key={slide.eyebrow}
            type="button"
            aria-label={dict.home.dotLabel(i + 1)}
            className={`slider-dot${i === index ? " slider-dot-active" : ""}`}
            onClick={() => setIndex(i)}
          />
        ))}
      </div>
    </div>
  );
}

export default function HomePage() {
  const [showSplash, setShowSplash] = useState(true);

  return (
    <>
      {showSplash && <HeroSplash onDone={() => setShowSplash(false)} />}

      <div id="top" className={`page${showSplash ? " page-waiting" : " page-ready"}`}>
        <NavBar />
        <HeroSlider />
        <Footer />
      </div>
    </>
  );
}
