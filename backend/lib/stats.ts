import { getRedis } from "./redis";

const DOWNLOAD_COUNT_KEY = "stats:downloads:total";
const dailyDownloadKey = (date: string) => `stats:downloads:daily:${date}`;

function toKstDateStr(d: Date): string {
  return d.toLocaleDateString("en-CA", { timeZone: "Asia/Seoul" });
}

function todayKST(): string {
  return toKstDateStr(new Date());
}

export async function incrementDownloadCount(): Promise<void> {
  const redis = getRedis();
  await Promise.all([
    redis.incr(DOWNLOAD_COUNT_KEY),
    redis.incr(dailyDownloadKey(todayKST())),
  ]);
}
