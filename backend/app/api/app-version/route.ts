import { NextResponse } from "next/server";

// 사이드로드 배포라 플레이스토어의 인앱 업데이트 기능을 쓸 수 없어서, 앱이 실행될 때마다
// 이 엔드포인트로 최신 버전을 물어봐서 스스로 업데이트 안내 배너를 띄운다.
// 새 APK를 배포할 때마다 versionCode/versionName을 android/app/build.gradle.kts와 맞춰서 갱신할 것.
export async function GET() {
  return NextResponse.json({
    versionCode: 3,
    versionName: "0.3",
    downloadUrl: "https://secretarykim.net/download",
  });
}
