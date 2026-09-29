"use client";

import { FormEvent, useEffect, useState } from "react";
import { useLocale } from "./LocaleProvider";

interface Post {
  id: string;
  nickname: string;
  title: string;
  content: string;
  createdAt: string;
}

function formatDate(iso: string) {
  const d = new Date(iso);
  return `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, "0")}.${String(d.getDate()).padStart(2, "0")}`;
}

export default function QnaBoard() {
  const { dict } = useLocale();
  const b = dict.board;
  const q = dict.qna;

  const [posts, setPosts] = useState<Post[] | null>(null);
  const [nickname, setNickname] = useState("");
  const [title, setTitle] = useState("");
  const [content, setContent] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");

  const load = async () => {
    const res = await fetch("/api/qna", { cache: "no-store" });
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
      const res = await fetch("/api/qna", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ nickname, title, content }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => null);
        throw new Error(data?.error ?? b.genericError);
      }
      setTitle("");
      setContent("");
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
        <h2 className="section-title">{q.heading}</h2>
        <p className="section-sub">{q.sub}</p>

        <form className="board-form" onSubmit={onSubmit}>
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
            placeholder={q.contentPlaceholder}
            value={content}
            onChange={(e) => setContent(e.target.value)}
            maxLength={2000}
            rows={4}
          />
          {error && <p className="board-error">{error}</p>}
          <button type="submit" className="board-submit" disabled={submitting}>
            {submitting ? b.submitting : q.submitButton}
          </button>
        </form>
      </section>

      <section className="section section-wide">
        <h3 className="board-list-title">{q.listTitle(posts ? posts.length : null)}</h3>
        {posts === null && <p className="board-empty">{b.loading}</p>}
        {posts !== null && posts.length === 0 && <p className="board-empty">{q.empty}</p>}
        <ul className="board-list">
          {posts?.map((post) => (
            <li key={post.id} className="board-post">
              <div className="board-post-head">
                <span className="board-post-title">{post.title}</span>
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
