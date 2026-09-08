# 수동 테스트 대기 목록

실기기·계정·네트워크가 필요해 **코드 단계에서 확인하지 못한 항목**을 여기에 쌓는다.
테스트하면 이 문서를 고친다 — 별도 보고서를 만들지 않는다.

## 규칙

- 기능을 구현했지만 기기에서 눌러보지 못했으면 **같은 커밋에** 해당 파일에 항목을 추가한다.
- 상태 표기
  - `⬜` 미확인
  - `✅ 2026-09-08` 확인됨(날짜)
  - `❌ 2026-09-08` 문제 발견 → 같은 줄 "메모" 칸에 증상, 수정 커밋이 들어가면 다시 `⬜` 로 되돌려 재확인
  - `➖` 이 기기에서 확인 불가(예: API 29 전용 경로) — 이유를 메모에
- 한 파일은 한 영역. 항목 ID 는 `영역-번호` (예: `GAL-03`). 커밋 메시지·이슈에서 ID 로 참조한다.
- 확인 기기는 기본적으로 Galaxy S23+ (Android 16, API 36). 다른 기기로 확인했으면 메모에 적는다.
- 성능·전력 측정은 **릴리스 빌드**로 한다. 절차는 `05-animation-performance.md`.

## 파일

| 파일 | 영역 |
|---|---|
| `01-gallery-basics.md` | 갤러리 조회·권한·선택(드래그, 날짜 전체)·기간 필터 |
| `02-google-drive.md` | GCP 등록, 로그인, Drive 탐색, 업로드 E2E·재개·알림 |
| `03-gallery-crud.md` | 삭제·휴지통·즐겨찾기·이름 변경·이동, MANAGE_MEDIA |
| `04-viewer.md` | 상세보기: 확대, 영상 재생, 음량·배속·회전, 정보 패널 |
| `05-animation-performance.md` | 애니메이션 체감 확인, 프레임·전력 측정 절차 |
| `06-release-build.md` | 릴리스(R8) APK 런타임 확인 |
| `07-auto-backup.md` | 자동 백업: 트리거·중복 방지·기존 항목 백업·마이그레이션 |
| `08-duplicates.md` | 완전 중복 탐지: 검사·그룹·기본 선택·휴지통 |

## 실기기 연결

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
$ANDROID_HOME/platform-tools/adb connect 192.168.0.3:5555   # 폰에서 무선 디버깅 켠 뒤 (IP·포트는 바뀔 수 있음)
$ANDROID_HOME/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

디버그 빌드 패키지는 `com.jjw.easygallery.debug`.
