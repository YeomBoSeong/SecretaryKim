"use client";

import { FormEvent, useEffect, useState } from "react";
import { useLocale } from "./LocaleProvider";

interface Review {
  id: string;
  nickname: string;
  title: string;
  content: string;
  rating: number;
  createdAt: string;
}

function formatDate(iso: string) {
  const d = new Date(iso);
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, "0")}.${String(d.getDate()).padStart(2, "0")}`;
}

function Stars({ value, label }: { value: number; label: string }) {
  return (
    <span className="board-stars" aria-label={label}>
      {"★★★★★".slice(0, value)}
      <span className="board-stars-empty">{"★★★★★".slice(value)}</span>
    </span>
  );
}

export default function ReviewBoard() {
  const { dict } = useLocale();
  const b = dict.board;
  const r = dict.reviews;

  const [posts, setPosts] = useState<Review[] | null>(null);
  const [nickname, setNickname] = useState("");
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [rating, setRating] = useState(5);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const load = async () => {
    const res = await fetch("/api/reviews", { cache: "no-store" });
    const data = await res.json();
    setPosts(data.posts ?? []);
  };

  useEffect(() => {
    load();
  }, []);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    if (!nickname.trim() || !title.trim() || !content.trim()) {
      setError(b.validationError);
      return;
    }
    setSubmitting(true);
    try {
      const res = await fetch("/api/reviews", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nickname, title, content, rating }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => null);
        throw new Error(data?.error ?? b.genericError);
      }
      setTitle("");
      setContent("");
      setRating(5);
      await load();
    } catch (err) {
      setError(err instanceof Error ? err.message : b.genericError);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <section className="section section-wide">
        <h2 className="section-title">{r.heading}</h2>
        <p className="section-sub">{r.sub}</p>

        <form className="board-form" onSubmit={onSubmit}>
          <div className="board-rating-input">
            {[1, 2, 3, 4, 5].map((n) => (
              <button
                key={n}
                type="button"
                className={`board-rating-star${n <= rating ? " board-rating-star-active" : ""}`}
                aria-label={r.ratingLabel(n)}
                onClick={() => setRating(n)}
              >
                ★
              </button>
            ))}
          </div>
          <input
            type="text"
            placeholder={b.nicknamePlaceholder}
            value={nickname}
            onChange={(e) => setNickname(e.target.value)}
            maxLength={30}
          />
          <input
            type="text"
            placeholder={b.titlePlaceholder}
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            maxLength={100}
          />
          <textarea
            placeholder={r.contentPlaceholder}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            maxLength={2000}
            rows={4}
          />
          {error && <p className="board-error">{error}</p>}
          <button type="submit" className="board-submit" disabled={submitting}>
            {submitting ? b.submitting : r.submitButton}
          </button>
        </form>
      </section>

      <section className="section section-wide">
        <h3 className="board-list-title">{r.listTitle(posts ? posts.length : null)}</h3>
        {posts === null && <p className="board-empty">{b.loading}</p>}
        {posts !== null && posts.length === 0 && <p className="board-empty">{r.empty}</p>}
        <ul className="board-list">
          {posts?.map((post) => (
            <li key={post.id} className="board-post">
              <div className="board-post-head">
                <span className="board-post-title">
                  <Stars value={post.rating} label={r.starLabel(post.rating)} /> {post.title}
                </span>
                <span className="board-post-meta">
                  {post.nickname} · {formatDate(post.createdAt)}
                </span>
              </div>
              <p className="board-post-content">{post.content}</p>
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
