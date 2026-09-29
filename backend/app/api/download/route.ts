import { NextResponse } from "next/server";
import { incrementDownloadCount } from "../../../lib/stats";

export async function GET(request: Request) {
  await incrementDownloadCount().catch((err) => console.error("failed to record download", err));
  return NextResponse.redirect(new URL("/downloads/secretarykim-v3.apk", request.url));
}
