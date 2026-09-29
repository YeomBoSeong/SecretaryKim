package com.ybs.assistant.network

/** 김비서 웹사이트/백엔드(Vercel) 주소. */
object BackendConfig {
    const val BASE_URL = "https://backend-eta-sooty-89.vercel.app"

    /** 앱 실행 시 최신 버전을 확인하는 엔드포인트. */
    const val APP_VERSION_URL = "$BASE_URL/api/app-version"

    /** 새 버전 안내에서 열어줄 다운로드 페이지. */
    const val DOWNLOAD_PAGE_URL = "https://secretarykim.net/download"
}
