# Arena Agent

LMArena(`https://arena.ai/text/direct?model_a=max`) 웹을 AI 백엔드로 쓰고, Android 접근성 서비스로 기기를 직접 조작하는 에이전트 앱.

## 빌드 (GitHub Actions)
1. 이 폴더를 GitHub 저장소에 push (main/master)
2. Actions 탭 → **Build APK** → Artifacts 의 `arena-agent-debug-apk` 다운로드
3. APK 설치

## 첫 실행
1. 앱 실행 → 로딩 UI → Google 로그인 창 → 로그인 (쿠키는 저장되어 재실행 시 유지)
2. 상단 좌측 버튼 또는 "접근성 서비스 켜기" → **Arena Agent** 활성화
   - Android 13+ 사이드로드 앱: 앱 정보 → ⋮ → **제한된 설정 허용** 후 활성화
3. 명령 입력 (예: "음량 50%로", "배터리 몇 퍼야?", "10분 타이머", "와이파이 꺼줘")
4. (선택) 밝기/화면 시간 조절은 앱 정보 → **시스템 설정 수정 허용**, 방해금지 모드 제어는 **방해 금지 접근 허용** 을 켜 두면 UI 조작 없이 바로 처리됩니다.

## 구조
- `ArenaController` : WebView 로 LMArena 로그인/전송/스트리밍/중단/새 세션 (DOM 주입). `work` 블록이 닫히면 응답 종료를 기다리지 않고 즉시 다음 단계로 진행
- `WorkParser` : 응답의 ```work JSON 블록 해석
- `AgentViewModel` : 목표 달성까지 반복하는 에이전트 루프 (최대 40 step). API 전용 턴은 커서/화면 덤프 생략, `done`+명령은 같은 턴에 실행 후 성공 시 즉시 종료
- `SystemApi` : UI 없이 처리하는 빠른 경로 (볼륨/벨소리/밝기/손전등/DND/미디어/알람/타이머/설정 화면 직행/패널/전화·문자·메일·지도·캘린더/임의 intent)
- `AgentAccessibilityService` : 화면 덤프 + tap/tap_text/swipe/scroll/type/key/launch_app/open_url/read_screen. 고정 delay 대신 화면이 안정될 때까지 적응형 대기
- `AgentPrompt` : 시스템 프롬프트. 우선순위 = 상태로 답변 → 시스템 API → 앱/인텐트 → UI 조작. 매 턴 `[LIVE_STATE]`(볼륨·배터리·네트워크·권한 등) 제공
- `ui/Theme.kt` : 팔레트(순수 검정/블랙그레이/화이트/블루), 블러+페이드 등장 `Reveal`, `blurFade`
- `ui/ChatScreen.kt` : Claude 앱 스타일 채팅 UI (랜덤 인사말, 스트리밍 텍스트, 팽창하는 입력창, 전송/중지 전환)
- `ui/Icons.kt` : Canvas 아이콘
