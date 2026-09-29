export type Locale = "ko" | "en";

export const LOCALE_COOKIE = "locale";
export const defaultLocale: Locale = "ko";

export function resolveLocale(value?: string | null): Locale {
  return value === "en" ? "en" : "ko";
}

/** 비한국어권 접속자는 기본적으로 영어로 노출되도록 Accept-Language를 검사합니다. */
export function localeFromAcceptLanguage(header: string | null | undefined): Locale {
  // Accept-Language를 아예 안 보내는 요청(검색엔진 크롤러가 흔함)은 한국어 서비스 기본값을 따른다.
  if (!header) return defaultLocale;
  return header.toLowerCase().includes("ko") ? "ko" : "en";
}

/** 구글/네이버 등 주요 검색엔진 크롤러의 User-Agent 패턴. 한국어 콘텐츠가 색인되도록 강제한다. */
export const SEARCH_BOT_USER_AGENT_PATTERN =
  /googlebot|bingbot|yeti|naver|daumoa|baiduspider|yandexbot|duckduckbot|slurp|facebookexternalhit|kakaotalk-scrap/i;

interface NavItem {
  href: string;
  label: string;
}

interface Slide {
  eyebrow: string;
  title: string;
  desc: string;
  image: string;
}

interface GuideCard {
  heading: string;
  body: string;
  imageAlt: string;
  image: string;
  note?: string;
}

interface Faq {
  q: string;
  a: string;
}

export interface Dictionary {
  meta: {
    title: string;
    description: string;
  };
  brand: string;
  nav: {
    items: NavItem[];
    openMenu: string;
    closeMenu: string;
    langToggle: string;
  };
  footer: {
    contactLabel: string;
    note: string;
    copyright: string;
  };
  home: {
    splashSub: string;
    splashTitle: string;
    slides: Slide[];
    cta: string;
    prevSlide: string;
    nextSlide: string;
    dotLabel: (n: number) => string;
  };
  download: {
    meta: { title: string; description: string };
    heading: string;
    suspendedTitle: string;
    suspendedBody: string;
    warningTitle: string;
    warningBody: string;
    warningContact: string;
    downloadButton: string;
    versionLabel: string;
    versionNote: string;
    stepsHeading: string;
    steps: string[];
    previewHeading: string;
    previewSub: string;
    guides: GuideCard[];
  };
  intro: {
    meta: { title: string; description: string };
    heading: string;
    whyHeading: string;
    whyBody: string;
    featuresHeading: string;
    features: { icon: string; text: string }[];
    usecasesHeading: string;
    usecases: string[];
  };
  help: {
    meta: { title: string; description: string };
    heading: string;
    firstUseHeading: string;
    firstUseBody: string;
    faqHeading: string;
    faqs: Faq[];
  };
  board: {
    nicknamePlaceholder: string;
    titlePlaceholder: string;
    validationError: string;
    genericError: string;
    submitting: string;
    loading: string;
  };
  qna: {
    meta: { title: string; description: string };
    heading: string;
    sub: string;
    contentPlaceholder: string;
    submitButton: string;
    listTitle: (n: number | null) => string;
    empty: string;
  };
  reviews: {
    meta: { title: string; description: string };
    heading: string;
    sub: string;
    contentPlaceholder: string;
    submitButton: string;
    listTitle: (n: number | null) => string;
    empty: string;
    starLabel: (n: number) => string;
    ratingLabel: (n: number) => string;
  };
}

const ko: Dictionary = {
  meta: {
    title: "김비서 — AI 비서 안드로이드 앱",
    description: "Claude API 기반 안드로이드 AI 비서 앱, 김비서를 다운로드하세요.",
  },
  brand: "김비서",
  nav: {
    items: [
      { href: "/intro", label: "앱 소개" },
      { href: "/download", label: "다운로드" },
      { href: "/qna", label: "Q&A" },
      { href: "/reviews", label: "후기" },
      { href: "/help", label: "도움말" },
    ],
    openMenu: "메뉴 열기",
    closeMenu: "메뉴 닫기",
    langToggle: "English",
  },
  footer: {
    contactLabel: "문의",
    note: "Android 11(API 30) 이상 · 공식 스토어 미배포, 직접 배포 앱입니다.",
    copyright: "© 2026 김비서. All rights reserved.",
  },
  home: {
    splashSub: "당신의 삶을 더 윤택하게,",
    splashTitle: "김비서",
    cta: "APK 다운로드",
    prevSlide: "이전 슬라이드",
    nextSlide: "다음 슬라이드",
    dotLabel: (n) => `${n}번째 슬라이드 보기`,
    slides: [
      {
        eyebrow: "AI 비서",
        title: "당신의 화면을 보고,\n대신 움직입니다",
        desc: "채팅으로 시킨 작업을 화면을 직접 보고 대신 실행해요. 앱 실행, 검색, 문서 작성까지 말만 하면 됩니다.",
        image: "/screens/chat.png",
      },
      {
        eyebrow: "요금제",
        title: "내 Claude API 키로\n안전하게",
        desc: "직접 발급받은 Claude API 키를 앱에 등록하면 끝. 김비서는 중간에서 요금을 받지 않고, 사용한 만큼 Anthropic에 직접 결제돼요.",
        image: "/screens/subscription.png",
      },
      {
        eyebrow: "권한 설정도 간단하게",
        title: "화면 안내를 따라\n권한만 켜면 끝",
        desc: "단계별 안내와 실제 화면 캡처로 설정 과정에서 헤매지 않아요.",
        image: "/screens/permission.png",
      },
    ],
  },
  download: {
    meta: {
      title: "다운로드 — 김비서",
      description: "김비서 APK를 다운로드하고 설치하는 방법을 안내합니다.",
    },
    heading: "다운로드",
    suspendedTitle: "🚫 앱 배포가 중단되었습니다",
    suspendedBody:
      "보안 및 법적 리스크에 대한 자체 판단에 따라 김비서 앱의 배포를 중단했습니다. 그동안 이용해주신 분들께는 죄송한 말씀 드리며, 당분간 신규 다운로드는 제공하지 않습니다.",
    warningTitle: "⚠️ 설치 전 꼭 확인해주세요",
    warningBody:
      "이 앱은 접근성 서비스 권한을 통해 휴대폰의 화면을 조작할 수 있는 앱입니다. 불법적인 해커가 휴대폰에 침입하여 이 앱을 사용한다면 큰 문제가 발생할 수 있습니다. 따라서 플레이 스토어 등 정식 스토어에서는 이런 앱의 출시를 막고 있습니다. 또한 화면 조작을 하는 AI는 사용자가 의도치 않은 동작을 수행할 수도 있습니다. 이런 위험 사항들을 유의하여 다운로드를 진행해주시기 바랍니다.",
    warningContact: "궁금한 점은 {email}으로 문의해주세요.",
    downloadButton: "APK 다운로드",
    versionLabel: "ver 3",
    versionNote: "직접 발급받은 Claude API 키로 동작(BYOK) · Android 11(API 30) 이상",
    stepsHeading: "설치 방법",
    steps: [
      '위 "APK 다운로드" 버튼을 눌러 파일을 받습니다.',
      '설치 시 "출처를 알 수 없는 앱" 경고가 뜨면 <strong>허용</strong>을 눌러주세요. (공식 스토어가 아닌 직접 배포 방식이라 뜨는 정상적인 안내예요.)',
      "설치 후 앱을 열고 화면 안내에 따라 권한을 설정하면 바로 사용할 수 있어요.",
    ],
    previewHeading: "설치 과정 미리보기",
    previewSub:
      "실제 기기로 직접 설치해보며 겪었던 단계들을 미리 안내해드려요. 당황하지 않으셔도 돼요 — 정상적인 안내 화면이에요.",
    guides: [
      {
        heading: "① Google Play 프로텍트 경고 — 무시하고 설치",
        body: '공식 스토어 외부에서 받은 앱이라 Google Play 프로텍트가 검사 경고를 보여줄 수 있어요. 신뢰할 수 있다고 판단되면 <strong>무시하고 설치</strong>를 눌러 진행하면 돼요.',
        imageAlt: "설치 경고 화면",
        image: "/guide/install-warning.png",
      },
      {
        heading: "② Google Play 프로텍트가 앱을 완전히 차단할 때",
        body: '경고를 넘기고 나면 곧이어 "기기 보호를 위해 앱 차단됨" 화면이 반드시 한 번 더 뜨는데, 정상적인 안내니 당황하지 마세요. 이 화면에서는 무시하고 진행할 방법이 없으니, Play 스토어 앱을 열어 <strong>프로필 → Play 프로텍트 → 설정(⚙)</strong>에서 앱 검사를 잠시 꺼둔 뒤 처음부터 다시 설치를 진행해주세요.',
        imageAlt: "Google Play 프로텍트에 의해 기기 보호를 위해 앱이 차단됨 화면",
        image: "/guide/playprotect-blocked.png",
        note: "⚠️ <strong>설치가 끝난 뒤에는 같은 화면에서 앱 검사를 다시 켜두는 것을 잊지 마세요.</strong> 잠시 꺼둔 건 설치를 위한 임시 조치일 뿐, 휴대폰 보호를 위해 설치 후에는 원래대로 켜두시는 걸 권장해요.",
      },
      {
        heading: "③ 접근성 화면에서 앱이 비활성화되어 보일 때",
        body: '설정 → 접근성 → 설치된 앱 목록에서 김비서가 회색으로 "제한된 설정으로 제어됨"이라고 뜨며 눌리지 않을 수 있어요. Android가 사이드로드 앱의 민감한 권한을 한 번 더 확인시키는 보안 장치예요.',
        imageAlt: "제한된 설정으로 제어됨 화면",
        image: "/guide/restricted-settings.png",
        note: "해결 방법: <strong>설정 → 애플리케이션 → 김비서 → 우측 상단 ⋮ 메뉴 → 제한된 설정 허용</strong>을 누른 뒤, 접근성 화면으로 돌아오면 정상적으로 켤 수 있어요.",
      },
    ],
  },
  intro: {
    meta: {
      title: "앱 소개 — 김비서",
      description: "김비서가 왜 만들어졌는지, 어떤 기능을 제공하는지 알아보세요.",
    },
    heading: "앱 소개",
    whyHeading: "왜 김비서를 만들었나요",
    whyBody:
      "Claude Code의 기능을 휴대폰에 그대로 구현하면, 모바일에서도 복잡한 작업을 LLM이 직접 처리해주고 그 작업이 이루어지는 동안 다른 일을 할 수 있으니 더 생산적이지 않을까 하는 생각에서 출발했습니다. 또한 휴대폰을 잘 다루기 어려운 어르신들이나 신체가 불편한 장애인분들께도 도움이 되지 않을까 싶어서 만들게 되었습니다.",
    featuresHeading: "주요 기능",
    features: [
      { icon: "🖐️", text: "인공지능이 직접 화면을 보고 탭, 스와이프, 입력을 수행해 작업을 자동 수행" },
      { icon: "📁", text: "휴대폰에 저장된 문서/파일을 찾아 읽고 수정" },
      { icon: "📎", text: "인공지능에게 사진, PDF, 문서를 첨부해 작업 가능" },
      { icon: "🎙️", text: "음성으로 묻고 음성으로 답변 듣기" },
    ],
    usecasesHeading: "이럴 때 써보세요",
    usecases: [
      "여러 앱을 오가거나 단순 입력을 반복해야되는 작업을 자동으로 하고 싶을 때",
      "게임이나 문서 작업 등 컴퓨터로 해야되는 작업이 있어 바쁜데 모바일로 처리해야 하는 일이 있을 때",
      "파일을 컴퓨터로 보내지 않고 휴대폰에서 바로 수정하고 싶을 때",
    ],
  },
  help: {
    meta: {
      title: "도움말 — 김비서",
      description: "김비서 사용법과 자주 묻는 질문을 확인하세요.",
    },
    heading: "도움말",
    firstUseHeading: "앱을 처음 쓰신다면",
    firstUseBody:
      "채팅 화면 오른쪽 위 물음표(❓) 아이콘을 누르면 언제든 권한 설정 방법, 사용 중 주의사항, 작업이 끝난 후 돌아오는 방법 같은 사용 가이드를 볼 수 있어요.",
    faqHeading: "자주 묻는 질문",
    faqs: [
      {
        q: "Q. 접근성 권한이 꼭 필요한가요?",
        a: "A. 화면을 대신 조작하려면 필요해요. 꺼둔 상태에서도 일반 대화는 그대로 이용할 수 있어요.",
      },
      {
        q: "Q. API 키는 어떻게 발급받나요?",
        a: "A. console.anthropic.com 에서 가입한 뒤 \"API Keys\" 메뉴에서 발급받을 수 있어요. 발급받은 키를 앱 설정 화면의 \"API 키 설정\"에 붙여넣으면 바로 사용할 수 있어요.",
      },
      {
        q: "Q. 요금은 어떻게 청구되나요?",
        a: "A. 김비서 앱 자체는 별도 요금을 받지 않아요. 사용한 만큼 Anthropic이 등록하신 API 키로 직접 청구해요.",
      },
      {
        q: "Q. 이 앱은 왜 스토어에 없나요?",
        a: "A. 화면 조작 권한(접근성 서비스) 관련 정책 때문에 공식 스토어에 올릴 수 없어서, 이 페이지를 통해 직접 배포하고 있어요.",
      },
    ],
  },
  board: {
    nicknamePlaceholder: "닉네임",
    titlePlaceholder: "제목",
    validationError: "닉네임, 제목, 내용을 모두 입력해주세요.",
    genericError: "등록에 실패했습니다.",
    submitting: "등록 중...",
    loading: "불러오는 중...",
  },
  qna: {
    meta: {
      title: "Q&A — 김비서",
      description: "김비서에 대해 궁금한 점을 질문하고 답변을 확인하세요.",
    },
    heading: "Q&A",
    sub: "궁금한 점을 남겨주시면 확인 후 답변드릴게요.",
    contentPlaceholder: "내용을 입력해주세요.",
    submitButton: "질문 등록",
    listTitle: (n) => `전체 질문 ${n !== null ? `${n}건` : ""}`,
    empty: "아직 등록된 질문이 없어요. 첫 질문을 남겨보세요!",
  },
  reviews: {
    meta: {
      title: "후기 — 김비서",
      description: "김비서를 사용해본 분들의 후기를 확인하고 남겨보세요.",
    },
    heading: "후기",
    sub: "김비서를 사용해보신 소감을 남겨주세요.",
    contentPlaceholder: "사용 후기를 남겨주세요.",
    submitButton: "후기 등록",
    listTitle: (n) => `전체 후기 ${n !== null ? `${n}건` : ""}`,
    empty: "아직 등록된 후기가 없어요. 첫 후기를 남겨보세요!",
    starLabel: (n) => `별점 ${n}점`,
    ratingLabel: (n) => `${n}점`,
  },
};

const en: Dictionary = {
  meta: {
    title: "Secretary Kim — AI Assistant for Android",
    description: "Download Secretary Kim, a Claude-powered AI assistant app for Android.",
  },
  brand: "Secretary Kim",
  nav: {
    items: [
      { href: "/intro", label: "About" },
      { href: "/download", label: "Download" },
      { href: "/qna", label: "Q&A" },
      { href: "/reviews", label: "Reviews" },
      { href: "/help", label: "Help" },
    ],
    openMenu: "Open menu",
    closeMenu: "Close menu",
    langToggle: "한국어",
  },
  footer: {
    contactLabel: "Contact",
    note: "Android 11 (API 30) or higher · Not on official app stores — distributed directly.",
    copyright: "© 2026 Secretary Kim. All rights reserved.",
  },
  home: {
    splashSub: "Making your life easier,",
    splashTitle: "Secretary Kim",
    cta: "Download APK",
    prevSlide: "Previous slide",
    nextSlide: "Next slide",
    dotLabel: (n) => `Go to slide ${n}`,
    slides: [
      {
        eyebrow: "AI Assistant",
        title: "It sees your screen,\nand acts for you",
        desc: "Tell it what to do in chat, and it looks at your screen and carries it out for you — launching apps, searching, writing documents, just by asking.",
        image: "/screens-en/chat.png",
      },
      {
        eyebrow: "Pricing",
        title: "Bring your own\nClaude API key",
        desc: "Register your own Claude API key in the app and that's it. Secretary Kim doesn't charge anything in between — you pay Anthropic directly for what you use.",
        image: "/screens-en/subscription.png",
      },
      {
        eyebrow: "Simple permission setup",
        title: "Follow the on-screen guide,\njust flip the switch",
        desc: "Step-by-step instructions with real screenshots mean you'll never get lost during setup.",
        image: "/screens-en/permission.png",
      },
    ],
  },
  download: {
    meta: {
      title: "Download — Secretary Kim",
      description: "How to download and install the Secretary Kim APK.",
    },
    heading: "Download",
    suspendedTitle: "🚫 Distribution suspended",
    suspendedBody:
      "Based on our own assessment of security and legal risk, we've suspended distribution of the Secretary Kim app. We're sorry to everyone who's been using it — new downloads aren't available for now.",
    warningTitle: "⚠️ Please read before installing",
    warningBody:
      "This app uses the Accessibility Service permission to control your phone's screen. If a bad actor ever gained access to your phone and misused this permission, it could cause serious harm — which is why official stores like Google Play don't allow apps like this. Also keep in mind that an AI controlling your screen may occasionally do something you didn't intend. Please keep these risks in mind before downloading.",
    warningContact: "Questions? Reach out at {email}.",
    downloadButton: "Download APK",
    versionLabel: "ver 3",
    versionNote: "Runs on your own Claude API key (BYOK) · Android 11 (API 30) or higher",
    stepsHeading: "How to install",
    steps: [
      'Tap the "Download APK" button above to get the file.',
      'If you see an "unknown app" warning during install, tap <strong>Install anyway</strong>. (This is expected — it appears because the app isn\'t distributed through an official store.)',
      "After installing, open the app and follow the on-screen guide to set up permissions — then you're ready to go.",
    ],
    previewHeading: "A preview of the install process",
    previewSub:
      "Here's a walkthrough of the steps you may run into when installing on a real device. Don't worry if you see these — they're expected.",
    guides: [
      {
        heading: "① Google Play Protect warning — install anyway",
        body: 'Since this app comes from outside an official store, Google Play Protect may show a warning screen. If you trust the source, tap <strong>Install anyway</strong> to continue.',
        imageAlt: "Install warning screen",
        image: "/guide-en/install-warning.png",
      },
      {
        heading: "② When Google Play Protect fully blocks the app",
        body: 'After the warning, you may see an "App blocked to protect your device" screen once more — this is expected, so don\'t worry. There\'s no way to bypass this screen directly, so open the Play Store app and go to <strong>Profile → Play Protect → Settings (⚙)</strong>, pause app scanning, then start the install over from the beginning.',
        imageAlt: "Screen showing the app blocked by Google Play Protect to protect the device",
        image: "/guide-en/playprotect-blocked.png",
        note: "⚠️ <strong>Once installation is finished, don't forget to turn app scanning back on from that same screen.</strong> Pausing it was only a temporary step for installing — turning it back on afterward is recommended to keep your phone protected.",
      },
      {
        heading: "③ If the app looks disabled on the Accessibility screen",
        body: 'Under Settings → Accessibility → Installed apps, Secretary Kim may appear grayed out and unresponsive, labeled "Controlled by restricted setting." This is a security measure that makes Android double-check sensitive permissions for sideloaded apps.',
        imageAlt: "Controlled by restricted setting screen",
        image: "/guide-en/restricted-settings.png",
        note: "How to fix it: tap <strong>Settings → Apps → Secretary Kim → the ⋮ menu (top right) → Allow restricted settings</strong>, then go back to the Accessibility screen — you'll be able to turn it on normally.",
      },
    ],
  },
  intro: {
    meta: {
      title: "About — Secretary Kim",
      description: "Learn why Secretary Kim was built and what it can do.",
    },
    heading: "About",
    whyHeading: "Why we built Secretary Kim",
    whyBody:
      "The idea started with a simple question: if you could bring Claude Code's capabilities to your phone, wouldn't it be more productive to let an LLM handle complex tasks directly while you do something else? We also hoped it could help older adults who find phones hard to navigate, and people with disabilities that make using a phone difficult.",
    featuresHeading: "Key features",
    features: [
      { icon: "🖐️", text: "The AI looks at your screen directly and taps, swipes, and types to carry out tasks automatically" },
      { icon: "📁", text: "Finds, reads, and edits documents and files stored on your phone" },
      { icon: "📎", text: "Attach photos, PDFs, or documents for the AI to work with" },
      { icon: "🎙️", text: "Ask by voice and hear the answer read back to you" },
    ],
    usecasesHeading: "Try it when you...",
    usecases: [
      "want to automate a task that means switching between several apps or repeating simple inputs",
      "are busy with something on your computer (a game, document work) and also need to handle something on your phone",
      "want to edit a file right on your phone instead of sending it to a computer first",
    ],
  },
  help: {
    meta: {
      title: "Help — Secretary Kim",
      description: "Check how to use Secretary Kim and find answers to common questions.",
    },
    heading: "Help",
    firstUseHeading: "New to the app?",
    firstUseBody:
      "Tap the question mark (❓) icon at the top right of the chat screen any time to see a guide covering how to set up permissions, what to watch out for while a task is running, and how to get back after it's done.",
    faqHeading: "Frequently asked questions",
    faqs: [
      {
        q: "Q. Is the accessibility permission really necessary?",
        a: "A. Yes, it's needed for the app to control your screen on your behalf. You can still use regular chat with it turned off.",
      },
      {
        q: "Q. How do I get an API key?",
        a: "A. Sign up at console.anthropic.com, then create one under the \"API Keys\" menu. Paste the key into \"API Key Setup\" in the app's settings screen and you're ready to go.",
      },
      {
        q: "Q. How does billing work?",
        a: "A. Secretary Kim itself doesn't charge anything. Anthropic bills you directly, through your own API key, for what you actually use.",
      },
      {
        q: "Q. Why isn't this app on an app store?",
        a: "A. Store policies don't allow apps with screen-control (accessibility service) permissions, so we distribute it directly through this page instead.",
      },
    ],
  },
  board: {
    nicknamePlaceholder: "Nickname",
    titlePlaceholder: "Title",
    validationError: "Please fill in your nickname, title, and message.",
    genericError: "Something went wrong while submitting.",
    submitting: "Submitting...",
    loading: "Loading...",
  },
  qna: {
    meta: {
      title: "Q&A — Secretary Kim",
      description: "Ask questions about Secretary Kim and see the answers.",
    },
    heading: "Q&A",
    sub: "Leave a question and we'll get back to you.",
    contentPlaceholder: "Type your question here.",
    submitButton: "Post question",
    listTitle: (n) => `All questions${n !== null ? ` (${n})` : ""}`,
    empty: "No questions yet. Be the first to ask one!",
  },
  reviews: {
    meta: {
      title: "Reviews — Secretary Kim",
      description: "Read reviews from people who use Secretary Kim, or leave your own.",
    },
    heading: "Reviews",
    sub: "Tell us what you think of Secretary Kim.",
    contentPlaceholder: "Share your review here.",
    submitButton: "Post review",
    listTitle: (n) => `All reviews${n !== null ? ` (${n})` : ""}`,
    empty: "No reviews yet. Be the first to leave one!",
    starLabel: (n) => `Rating: ${n} out of 5`,
    ratingLabel: (n) => `${n} star${n === 1 ? "" : "s"}`,
  },
};

export const dictionaries: Record<Locale, Dictionary> = { ko, en };

export function getDictionary(locale: Locale): Dictionary {
  return dictionaries[locale];
}
