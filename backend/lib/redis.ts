import { Redis } from "@upstash/redis";

let _redis: Redis | null = null;

// Vercel Marketplace의 Upstash KV 통합은 KV_REST_API_URL/KV_REST_API_TOKEN으로 주입한다
// (Redis.fromEnv()가 찾는 UPSTASH_REDIS_REST_URL/TOKEN과 이름이 다름).
export function getRedis(): Redis {
  if (!_redis) {
    const url = process.env.KV_REST_API_URL ?? process.env.UPSTASH_REDIS_REST_URL!;
    const token = process.env.KV_REST_API_TOKEN ?? process.env.UPSTASH_REDIS_REST_TOKEN!;
    _redis = new Redis({ url, token });
  }
  return _redis;
}
