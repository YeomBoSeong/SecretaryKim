import { NextRequest, NextResponse } from "next/server";
import { addPost, listPosts } from "../../../lib/board";

export async function GET() {
  const posts = await listPosts("reviews");
  return NextResponse.json({ posts });
}

export async function POST(req: NextRequest) {
  const body = await req.json().catch(() => null);
  const nickname = typeof body?.nickname === "string" ? body.nickname.trim().slice(0, 30) : "";
  const title = typeof body?.title === "string" ? body.title.trim().slice(0, 100) : "";
  const content = typeof body?.content === "string" ? body.content.trim().slice(0, 2000) : "";
  const ratingRaw = Number(body?.rating);
  const rating = Number.isFinite(ratingRaw) ? Math.min(5, Math.max(1, Math.round(ratingRaw))) : 5;

  if (!nickname || !title || !content) {
    return NextResponse.json({ error: "닉네임, 제목, 내용을 모두 입력해주세요." }, { status: 400 });
  }

  const post = await addPost("reviews", { nickname, title, content, rating });
  return NextResponse.json({ post }, { status: 201 });
}
