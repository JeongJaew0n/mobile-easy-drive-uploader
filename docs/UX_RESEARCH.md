# UX 조사 — 무엇을 고치면 좋은가

2026-09-29. 요구: "전체적으로 UX 를 조사해서 어떻게 고치면 좋을지 연구해 봐. 연구하고 나서 그게 좋을지 또 한 번 연구해 봐. 3번 반복. 적극 검색."

**구현하지 않았다.** 제안 → 반론 찾기 → 가능한지·규칙과 부딪히는지 확인, 세 바퀴를 돌렸다. 각 바퀴에서 무엇이 바뀌었는지 남긴다.
기기로 사용성 시험을 한 것은 아니다 — 근거는 코드에서 센 사실과 공개된 연구·가이드다.

## 0. 지금 앱의 모양 (코드에서 센 것)

| 자리 | 지금 |
|---|---|
| 갤러리 위 | 상단바(기간·설정·⋮) + 탭 다섯(고른 사진·전체·카메라·스크린샷·다른 앱) |
| ⋮ 메뉴 | **9개** — 앨범, 숨긴 사진, 카테고리, 백업 안 된 항목만, 중복 사진, Google Drive, 즐겨찾기만, 올린 사진 기기에서 삭제, 휴지통 |
| 선택 하단바 | **아이콘만 9개** — 고른 사진, 휴지통, 즐겨찾기, 완전 삭제, 숨기기, 이름 변경, 앨범 이동, 카테고리(+업로드는 상단바) |
| 격자 | 칸 크기 고정(최소 100dp). 핀치로 칸 수 바꾸기·날짜 스크러버 없음 |
| 썸네일 배지 | **백업된 것**에 구름✓ |
| 휴지통 이동 | 시스템 확인 창(미디어 관리 권한이 없으면 매번). 실행 취소 없음 — Drive 화면에만 있다 |
| 백업·업로드 | 업로드는 선택 모드 상단바에서만. 자동 백업·업로드 목록은 설정 안 |
| 첫 실행 | 안내 없음. 시작 탭 "고른 사진" 이 처음엔 비어 있고 문구로만 안내 |
| 설정 | 한 화면에 20줄 남짓(계정·업로드·갤러리·원격 저장소) |

## 1. 1차 — 조사와 제안

| # | 제안 | 근거 |
|---|---|---|
| P1 | 하단 내비게이션(사진·앨범·백업·메뉴)으로 ⋮ 9개를 꺼낸다 | 3~5개의 최상위 목적지는 내비게이션 바가 맞다[M3 Navigation bar]. Google 포토(사진·컬렉션·만들기)·삼성 갤러리(사진·앨범·스토리·메뉴) 모두 이 구조[9to5Google][SammyGuru] |
| P2 | 선택 하단바를 **글자 붙은 4개 + 더보기**로 | M3 하단 앱바는 동작 수가 정해져 있고 나머지는 더보기로[M3 App bars]. Google 포토 선택 바는 공유·앨범에 추가·삭제·더보기 + 끌어올리는 시트[Android Police] |
| P3 | 휴지통 이동에 "실행 취소" 스낵바 | 되돌릴 수 있는 동작은 확인 창보다 실행 취소가 낫다. 확인 창은 되돌릴 수 없는 것에만[NN/g Confirmation][NN/g User mistakes] |
| P4 | 백업 배지를 Google 포토처럼 — **안 된 것·실패한 것**만 표시 | Google 포토는 백업된 것은 빈 칸, 안 된 것에 구름/(slash), 대기에 ↑를 단다[Google Photos Help][Guiding Tech] |
| P5 | 격자 핀치 확대·축소 + 오른쪽 날짜 스크러버 | Google 포토의 기본 조작. 6천 장을 손가락으로 튕겨 내려가는 것은 길다[Google Design][owncloud #1402] |
| P6 | 설정을 묶고 드문 것은 한 단계 안으로 | 점진적 공개 — 단계는 3 이하로[LogRocket][UXPin] |
| P7 | 첫 실행 안내(튜토리얼) | 기능(고른 사진·다른 계정 업로드·보기 폴더)이 많다 |

## 2. 2차 — 그게 맞나 (반론 찾기)

| # | 찾은 것 | 결론 |
|---|---|---|
| P1 | 숨긴 내비게이션은 **발견율을 거의 절반으로** 떨어뜨리고 과제 시간·체감 난이도를 올린다(179명 정량 시험)[NN/g Hamburger]. ⋮ 9개가 바로 그 숨긴 내비게이션이다 | **유지·강화.** 다만 위 탭 다섯 + 아래 내비게이션이면 층이 둘이 된다 → 탭은 **"사진" 목적지 안에서만** 보이게 |
| P2 | 글자 없는 아이콘은 뜻을 맞히는 비율이 88%(글자 있음)→60%, **앱 고유 아이콘이면 34%**[NN/g Icon usability]. 우리 9개 중 고른 사진(책갈피+)·숨기기·카테고리·앨범 이동은 앱 고유다 | **유지·강화** — 가장 근거가 센 제안 |
| P3 | 공식 문서: 기본 갤러리가 아닌 앱은 **휴지통 넣기·빼기 모두 매번 확인 창**, 미디어 관리 권한이 있을 때만 생략[Android Developers — Media] | **수정.** 권한이 없으면 실행 취소가 시스템 창을 한 번 더 띄운다. → 실행 취소는 **미디어 관리 권한이 있을 때만**. 없으면 첫 휴지통 확인 직후 "매번 묻지 않게 하기(권한 허용)" 를 **그 자리에서** 한 번 제안 |
| P4 | Google 포토는 백업된 것이 **기본 상태**라 표시를 빼고 예외만 보인다. 우리도 목적이 백업이라 "안 된 것" 이 할 일이다 | **유지.** 단 지금 사용자가 ✓ 로 안심하는 것일 수 있어 "✓ 도 보기" 를 설정으로 남긴다 |
| P5 | 격자 칸 수 변경은 널리 기대되는 조작[owncloud #1402]. 우리 격자엔 길게 눌러 끌기 선택이 있다 — 핀치(두 손가락)와 겹치지 않는다 | **유지.** 스크러버는 연·월 이동(DateJump)이 기간 시트에 이미 있어 그걸 격자 옆으로 꺼내는 쪽이 싸다 |
| P6 | 규칙 자체는 맞다. 다만 P1 의 "메뉴" 목적지가 생기면 중복·카테고리·자동 태그는 설정이 아니라 메뉴 쪽 도구다 | **P1 에 흡수** |
| P7 | 튜토리얼은 많이들 건너뛰고, 본 사람도 성공률·시간 이득이 없다. 제자리 도움말·빈 화면 안내가 낫다[NN/g Onboarding][NN/g Mobile tutorials][NN/g Empty states] | **버린다.** 대신 빈 화면에 **바로 갈 버튼** — "고른 사진" 이 비었으면 "전체에서 고르기" 버튼 |

## 3. 3차 — 만들 수 있나, 규칙·결정과 부딪히나

| # | 확인한 것 | 결론 |
|---|---|---|
| P1 | 사용자가 정한 것: "앱을 켜면 고른 사진부터"(2026-09-27). 하단 내비게이션을 넣어도 "사진" 목적지의 첫 탭이 고른 사진이면 그대로 지켜진다. 앨범은 지금 ⋮ → 시트(2026-09-28)인데, 목적지가 생기면 그리로 옮기는 것이 자연스럽다 | **가능.** 앨범 시트는 목적지 화면으로 승격 |
| P2 | 이 프로젝트의 Material3 는 **1.4.0** 이고, 1.4 는 새 플로팅 툴바(Expressive API)를 뺐다(1.5 알파에만 있다)[Compose Material3 releases][composables] | **기존 부품으로** — `BottomAppBar` 에 글자 붙은 버튼 4개(업로드·고른 사진·휴지통·더보기) + 나머지는 바텀시트. 알파 라이브러리는 들이지 않는다 |
| P3 | `MANAGE_MEDIA` 는 이미 설정에 "확인 없이 편집" 으로 있다 | **가능, 작다** |
| P4 | 배지는 한 곳(`MediaThumbnail`)이다. 대기·실패 상태는 업로드 큐에 이미 있다 | **가능, 작다** |
| P5 | `CLAUDE.md` 모션 규칙: 크기 애니메이션·`tween(숫자)` 금지. 칸 수는 애니메이션 없이 바꾸고, 보던 첫 항목을 기준으로 스크롤 위치를 되잡아야 한다 | **가능, 중간.** 스크롤 보존이 까다롭다 |
| P7' | 빈 화면 버튼은 탭을 바꾸는 한 줄 | **가능, 아주 작다** |

## 4. 권장 — 순서

| 순서 | 무엇 | 크기 | 왜 먼저 |
|---|---|---|---|
| 1 | **선택 하단바**: 글자 붙은 4개(업로드·고른 사진·휴지통·더보기) + 나머지는 바텀시트 | 중 | 근거가 가장 세다(34%). 매일 쓰는 곳 |
| 2 | **빈 "고른 사진" 에 "전체에서 고르기" 버튼** | 아주 작음 | 첫 실행 사용자가 빈 첫 화면에서 막힌다 |
| 3 | **썸네일 배지 반전** — 안 된 것·대기·실패만, ✓ 는 설정으로 | 작음 | 백업 앱의 할 일이 눈에 띈다 |
| 4 | **휴지통**: 권한 있으면 실행 취소, 없으면 첫 확인 뒤 권한 제안 | 작음 | 확인 창 피로를 줄인다 |
| 5 | **하단 내비게이션**: 사진(탭 다섯)·앨범·백업(업로드 목록+자동 백업+실패)·메뉴(숨긴 사진·휴지통·중복·카테고리·자동 태그·Drive·설정) | 큼 | 발견율 근거가 세지만 화면 구조를 바꾼다 — 1~4 뒤에 |
| 6 | **격자 핀치 칸 수 + 연·월 이동을 격자 옆으로** | 중 | 있으면 좋다. 스크롤 보존을 제대로 해야 한다 |

**하지 않을 것:** 첫 실행 튜토리얼(P7) — 근거가 반대다. Material3 1.5 알파 도입 — 한 부품 때문에 알파를 들이지 않는다.

## 5. 사용자 결정 (2026-10-05)

- **5번 하단 내비게이션: 한다.** 견본 https://claude.ai/artifact/Ki2xyNBWPgdCpHiH7AytT8 → `docs/plans/bottom-navigation/spec.md`
- **3번 배지: 구름 ✓ 를 기본으로 켠다.** 대기·실패 배지를 더하고 ✓ 는 설정에서 끌 수 있게 — 같은 계획에 묶었다
- **나머지(1·2·4·6): "작업 시작해"(2026-10-05).** 선택 하단바의 넷은 제안대로 업로드·고른 사진·휴지통·더보기
  → `docs/plans/ux-round2/spec.md`
- 백업 칸의 "올린 사진 기기에서 삭제" 가 올린 사진 전부(숨긴 것 제외)를 대상으로 바뀐 것을 승인(2026-10-05)

## 5-1. 처음에 물었던 것

- **하단 내비게이션(5번)을 할지.** 화면 구조가 바뀌고, 앨범이 ⋮ 에서 목적지로 옮겨 간다
- **배지 반전(3번)의 기본값.** ✓ 를 기본으로 숨길지, 켜 둘지
- 선택 하단바의 4개 고르기 — 제안은 업로드·고른 사진·휴지통·더보기. 즐겨찾기를 자주 쓰면 고른 사진과 바꿀 수 있다

## 6. 모르는 것

- 실제 사용자가 무엇을 가장 자주 누르는지 — 앱에 사용 통계가 없다. 선택 하단바의 4개는 추정이다
- 한국 사용자의 기대(삼성 갤러리 습관)가 Google 포토식 배지와 맞는지 — 삼성 갤러리는 백업 배지를 쓰지 않는다

## 출처

- [M3 Navigation bar](https://m3.material.io/components/navigation-bar/guidelines) · [M3 Tabs](https://m3.material.io/components/tabs/guidelines) · [M3 App bars](https://m3.material.io/components/app-bars/guidelines) · [M3 Toolbars](https://m3.material.io/components/toolbars/guidelines)
- [NN/g — Hamburger Menus and Hidden Navigation Hurt UX Metrics](https://www.nngroup.com/articles/hamburger-menus/)
- [NN/g — Icon Usability](https://www.nngroup.com/articles/icon-usability/)
- [NN/g — Confirmation Dialogs Can Prevent User Errors](https://www.nngroup.com/articles/confirmation-dialog/) · [NN/g — Preventing User Errors](https://www.nngroup.com/articles/user-mistakes/)
- [NN/g — Onboarding Tutorials vs. Contextual Help](https://www.nngroup.com/articles/onboarding-tutorials/) · [NN/g — Mobile Tutorials](https://www.nngroup.com/articles/mobile-tutorials/) · [NN/g — Empty States](https://www.nngroup.com/articles/empty-state-interface-design/)
- [Android Developers — Access media files from shared storage](https://developer.android.com/training/data-storage/shared/media) · [Partial photo/video access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access)
- [Compose Material3 releases](https://developer.android.com/jetpack/androidx/releases/compose-material3) · [composables — HorizontalFloatingToolbar](https://composables.com/material3/horizontalfloatingtoolbar)
- [9to5Google — year-long Google Photos redesign](https://9to5google.com/2025/03/23/year-long-google-photos-redesign/) · [Android Police — Google Photos multi-select](https://www.androidpolice.com/google-photos-is-testing-a-fancy-new-multi-select-and-sharing-workflow/)
- [SammyGuru — Samsung Gallery One UI 8.5](https://sammyguru.com/samsung-gallery-gets-stacked-albums-new-navigation-bar-in-one-ui-8-5/)
- [Google Photos Help — Check your backup](https://support.google.com/photos/answer/9343402?hl=en) · [Guiding Tech — cloud icon](https://www.guidingtech.com/what-is-cloud-symbol-in-google-photos/)
- [Google Design — Building the Google Photos Web UI](https://medium.com/google-design/google-photos-45b714dfbed1) · [owncloud/android #1402](https://github.com/owncloud/android/issues/1402)
- [LogRocket — Progressive disclosure](https://blog.logrocket.com/ux-design/progressive-disclosure-ux-types-use-cases/) · [UXPin — Progressive disclosure](https://www.uxpin.com/studio/blog/what-is-progressive-disclosure/)
