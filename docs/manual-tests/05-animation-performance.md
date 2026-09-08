# 05 · 애니메이션 체감 / 프레임 / 전력

배경과 근거: `../ANIMATION_IMPROVEMENT.md`. 1단계(`4ea8662`) 구현분의 확인 목록.

## 체감 확인

개발자 옵션 "애니메이션 배율" 을 **5x** 로 올려 천천히 보면 겹침·점프가 잘 보인다.

| ID | 시나리오 | 기대 결과 | 상태 | 메모 |
|---|---|---|---|---|
| ANI-01 | 화면 전환(갤러리→설정 등) | 새 화면이 살짝 작게 시작해 페이드+확대, 이전 화면 빠르게 사라짐 | ⬜ | fade-through 250ms |
| ANI-02 | 뒤로가기 제스처를 천천히 끌기 (Android 14+) | 진행률에 따라 떠나는 화면이 축소·투명, 놓으면 완료/복귀 | ⬜ | One UI 제스처 설정에 따라 다를 수 있음 |
| ANI-03 | 선택 모드 진입/해제 | 상단바 페이드 교차, 하단 바 아래서 올라옴/내려감 | ⬜ | |
| ANI-04 | 썸네일 선택/해제 | 0.88배로 부드럽게 축소·복귀, 체크 표시 커지며 등장 | ⬜ | 지속 시간·배율 감각 조정 포인트 |
| ANI-05 | 날짜 전체 선택(30개 이상 화면) | 동시에 축소돼도 끊김 없음 | ⬜ | graphicsLayer 라 레이아웃 비용 0 이어야 함 |
| ANI-06 | 휴지통 이동 후 | 사라진 자리로 남은 항목이 미끄러져 채움 | ⬜ | animateItem |
| ANI-07 | 기간/즐겨찾기 필터 전환 | 수백 개가 동시에 미끄러지지 **않고** 교체됨 | ⬜ | `animateItemChanges=false` 1회 |
| ANI-08 | 업로드 시작/종료, 기간 적용/해제 | 배너가 펴지며 등장·접히며 사라짐, 그리드가 점프하지 않음 | ⬜ | 150ms |
| ANI-09 | 상세보기 컨트롤 토글 | 상·하단 바 슬라이드, 중앙 재생 버튼 fade+scale(세로로 펴지지 않음) | ⬜ | |
| ANI-10 | 업로드 진행 바 | 1초마다 계단식이 아니라 200ms 씩 부드럽게 | ⬜ | |
| ANI-11 | 드래그 자동 스크롤 | 부드럽게 흐름(끊김 없음) | ⬜ | withFrameNanos |
| ANI-12 | 개발자 옵션 "애니메이션 끄기"(배율 0) 후 앱 재시작 | 모든 전환이 즉시 실행 | ⬜ | `LocalMotion.reduceMotion` |
| ANI-13 | 상세보기 두 번 탭 | **탭한 지점을 중심으로** 스프링 확대, 다시 두 번 탭하면 1배로 복귀 | ⬜ | 2단계 D1 |
| ANI-14 | 확대 후 가장자리 밖으로 끌고 손 떼기 | 빈 공간이 생기지 않게 경계 안으로 스프링 복귀. 핀치로 1배 미만이면 1배로 | ⬜ | `clampOffset` |
| ANI-15 | 갤러리 → 상세보기 진입/복귀 | 살짝 작게(0.92) 시작해 커지며 열리고, 닫을 때 작아지며 사라짐. 뒤로 제스처 중에도 동일 | ⬜ | 2단계 V2 |
| ANI-16 | 상세보기에서 아래로 끌기 | 끌수록 작아지고 투명, 220px 넘겨 놓으면 닫힘·덜 끌면 스프링 복귀. 확대 중엔 동작 안 함 | ⬜ | 2단계 V3 |
| ANI-17 | 상세보기 진입 직후 이미지 | 회색 화면 없이 썸네일이 먼저 보이고 원본으로 교체 | ⬜ | 2단계 D6 (Coil placeholderMemoryCacheKey) |
| ANI-18 | Drive 새 폴더 / 업로드 목록 완료 정리 | 항목이 미끄러지며 들어오고 나감 | ⬜ | 2단계 R1 |
| ANI-19 | 앨범 이동 | 바텀시트가 아래에서 올라옴, 바깥 탭·아래로 끌어 닫기 | ⬜ | 2단계 R4 |

| ANI-20 | 갤러리에서 썸네일 탭 | 썸네일이 제자리에서 커지며 상세보기로 이어짐(히어로), 끝난 뒤 이미지가 튀지 않음. 회전 후 같은 항목 재진입 시엔 히어로 없이 열림(정상) | ⬜ | 3단계 V1 |
| ANI-21 | 히어로 진행 중 화면 | 배경이 어둡게 전환되고 상·하단 바는 바로 표시 | ⬜ | |

## 프레임 측정 (릴리스 빌드)

### 방법 A — Macrobenchmark (권장, 수치가 JSON 으로 남음)

```bash
# 실기기(API 33+, 개발자 옵션 켬) 연결 후. 권한이 없으면 그리드가 뜨지 않으므로 먼저:
adb shell pm grant com.jjw.easygallery android.permission.READ_MEDIA_IMAGES
adb shell pm grant com.jjw.easygallery android.permission.READ_MEDIA_VIDEO
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
# 결과: baselineprofile/build/outputs/connected_android_test_additional_output/**/*.json
#   startupNoCompilation vs startupBaselineProfile → timeToInitialDisplayMs
#   galleryScrollBaselineProfile → frameDurationCpuMs P50/P90/P95/P99, frameOverrunMs
```

### 방법 B — gfxinfo (빠른 확인)

```bash
./gradlew :app:assembleRelease          # 서명 설정 필요 시 debug 키로 임시 서명
adb install -r app/build/outputs/apk/release/app-release.apk
PKG=com.jjw.easygallery                  # 릴리스는 .debug 접미사 없음
adb shell dumpsys gfxinfo $PKG reset
# 시나리오: 갤러리 플링 5회 / 선택 모드 진입·해제 5회 / 상세보기 열고 닫기 5회
adb shell dumpsys gfxinfo $PKG | grep -E "Total frames|Janky|90th|95th|99th"
```

| ID | 시나리오 | 목표 | 결과 | 상태 | 메모 |
|---|---|---|---|---|---|
| PERF-01 | 그리드 플링 5회 | janky < 5%, 95th < 16ms(60Hz) / < 8ms(120Hz) | | ⬜ | 디버그 기준값: janky 16.7%, 90th 65ms (2026-09-07) |
| PERF-04 | Baseline Profile 생성 | `./gradlew :app:generateBaselineProfile` (GMD `pixel6Api34` 자동 다운로드 또는 연결 기기) → `app/src/release/generated/baselineProfiles/baseline-prof.txt` 생성, 커밋 | | ⬜ | 첫 실행은 에뮬레이터 이미지(~1.5GB) 다운로드 |
| PERF-05 | 콜드 스타트 프로파일 유/무 비교 | `startupBaselineProfile` 의 TTID 가 `startupNoCompilation` 보다 짧음(보통 20~40%) | | ⬜ | 프로파일 파일이 있어야 의미 있음 |
| PERF-02 | 선택 모드 진입·해제 5회 | 95th < 16ms | | ⬜ | Catalog 분리 효과 확인 |
| PERF-03 | 상세보기 열기·닫기 5회 | 95th < 16ms | | ⬜ | |

## 전력 측정

```bash
adb shell dumpsys batterystats --reset
# 5분 시나리오: 갤러리 스크롤 2분 → 영상 재생 2분(컨트롤 숨긴 상태 유지) → 화면 켠 채 대기 1분
adb shell dumpsys batterystats $PKG | grep -iE "cpu|wake|Total"
```

| ID | 비교 | 기대 | 결과 | 상태 | 메모 |
|---|---|---|---|---|---|
| PWR-01 | 1단계 전(`0b68643`) vs 후(`4ea8662`) 앱 CPU 시간 | 감소 또는 동일 (폴링 제거·Catalog·프레임 동기 스크롤) | | ⬜ | 증가하면 해당 커밋 되돌림 |
| PWR-02 | 영상 컨트롤 숨김 상태 2분 | 앱 wakeup 이 재생 전과 유사 | | ⬜ | D7 검증 |

## 재구성 확인 (선택)

Android Studio Layout Inspector → 재구성 카운트 켜기 → 선택 토글 1회 → `MediaThumbnail` 재구성 수가 화면에 보이는 개수(≈30)를 넘지 않아야 함.
