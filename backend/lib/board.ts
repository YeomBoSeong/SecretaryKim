import { getRedis } from "./redis";

export type BoardType = "qna" | "reviews";

export interface BoardPost {
  id: string;
  nickname: string;
  title: string;
  content: string;
  rating?: number;
  createdAt: string;
}

const MAX_POSTS = 200;

function boardKey(board: BoardType) {
  return `board:${board}`;
}

function parsePost(raw: unknown): BoardPost | null {
  try {
    const value = typeof raw === "string" ? JSON.parse(raw) : raw;
    if (value && typeof value === "object" && "id" in value) {
      return value as BoardPost;
    }
    return null;
  } catch {
    return null;
  }
}

export async function listPosts(board: BoardType): Promise<BoardPost[]> {
  const redis = getRedis();
  const raw = await redis.lrange(boardKey(board), 0, 99);
  return raw
    .map(parsePost)
    .filter((post): post is BoardPost => post !== null);
}

export async function addPost(
  board: BoardType,
  post: Omit<BoardPost, "id" | "createdAt">
): Promise<BoardPost> {
  const redis = getRedis();
  const entry: BoardPost = {
    ...post,
    id: crypto.randomUUID(),
    createdAt: new Date().toISOString(),
  };
  await redis.lpush(boardKey(board), JSON.stringify(entry));
  await redis.ltrim(boardKey(board), 0, MAX_POSTS - 1);
  return entry;
}
