# 기간 선택 UI — kizitonwose Calendar 로 교체

작성 2026-09-09. 사용자 피드백: Material3 `DateRangePickerDialog` 기반 기간 선택이 "너무 구리다".

## 1. 문제

- M3 기본 피커는 다이얼로그 안에 세로 스크롤 달력을 통째로 넣어 크고 밋밋하다. 셀 커스터마이즈가 거의 불가능하다.
- **갤러리 맥락이 없다.** 사진이 있는 날과 없는 날을 구분하지 않아 빈 날을 고르고 "이 기간에 촬영한 항목이 없습니다" 를 보게 된다.
- UTC 자정 밀리초를 주기 때문에 `DateRangeDialog` 가 로컬 날짜로 변환하는 코드를 따로 들고 있었다.

## 2. 선택: kizitonwose/Calendar (`com.kizitonwose.calendar:compose` 2.10.1, Apache 2.0)

Compose 달력 라이브러리 중 가장 널리 쓰이고 활발히 유지된다. 월 그리드·스크롤·월 경계 처리만 제공하고 **날짜 셀은 전부 우리가 그린다** — 갤러리 전용 표현(사진 있는 날 강조, 개수 점)이 가능하다. Android 아티팩트는 `java.time` 을 그대로 쓰므로 `DateRange(LocalDate)` 와 변환 없이 맞는다.
대안으로 본 `maxkeppeler/sheets-compose-dialogs` 는 가장 적은 코드로 바꿀 수 있지만 셀 표현이 라이브러리에 고정돼 "갤러리답게" 만들 수 없어 제외.

## 3. UX 스펙

바텀시트(`ModalBottomSheet`) 하나로 구성한다.

```
┌─────────────────────────────────┐
│ 기간 선택                 [해제] │  ← 현재 기간이 있을 때만 해제
│ [오늘] [최근 7일] [최근 30일] [올해] │  ← 기존 DatePreset 칩 유지
│ 일  월  화  수  목  금  토        │
│ 2026년 9월                       │
│        1   2   3   4   5   6    │  ← 사진 없는 날: 흐림(alpha 0.38)·탭 불가
│  7   8 ...                       │     사진 있는 날: 아래 점, 탭 가능
│ 2026년 8월                       │  ← 위로 스크롤하면 과거(세로 달력, 최신 달이 맨 아래·처음 보임)
│ ...                              │
│                 [취소]   [적용]  │
└─────────────────────────────────┘
```

- **범위 선택**: 시작일 탭 → 종료일 탭. 종료일이 시작일보다 앞이면 시작일을 다시 잡는다. 시작일만 있는 상태로 적용하면 하루. 이미 완성된 범위에서 다시 탭하면 새 시작일. 이 로직은 순수 함수 `DateRangeSelection` 으로 분리해 단위 테스트한다.
- **범위 표시**: 시작·끝은 원형(primary), 사이는 띠(primaryContainer). 한 셀이 시작이면서 끝(하루)이면 원형만. 애니메이션은 넣지 않는다 — 셀은 42 × 월 수 개라 `if` 로 넣고 빼는 대신 배경색만 바꾼다(`ANIMATION_IMPROVEMENT.md` §1 "의미 없는 동작은 넣지 않는다").
- **달력 범위**: 가장 오래된 사진의 달 ~ 이번 달. 처음 보이는 달은 현재 기간의 시작 달, 없으면 이번 달. 오늘 이후 날짜는 흐림.
- **사진 개수 데이터**: `GalleryViewModel.Catalog` 에 `dayCounts: Map<LocalDate, Int>` 를 추가한다. 기간 필터 **이전**(즐겨찾기·백업 안 됨 필터는 반영) 목록에서 날짜별로 센다 — 기간을 바꾸려는 사람이 보는 달력이 현재 기간에 갇히면 안 되기 때문. 6천 장 O(n) 한 번, 목록이 바뀔 때만 재계산(선택 토글과 무관 — Catalog 분리 원칙 그대로).
- **접근성**: 날짜 셀 `contentDescription` = "9월 5일, 항목 13개" / 사진 없는 날은 비활성.

## 4. 파일

| 파일 | 내용 |
|---|---|
| `gradle/libs.versions.toml`, `app/build.gradle.kts` | `kizitonwose-calendar-compose` 등록 |
| `feature/gallery/DateRangeSheet.kt` (신규, `DateRangeDialog.kt` 삭제) | 바텀시트·칩·요일 헤더·`VerticalCalendar`·버튼 |
| `feature/gallery/DateRangeSelection.kt` (신규) | 탭 → (start, end) 순수 로직 + `toDateRange()` |
| `feature/gallery/GallerySection.kt` | `countByDay(items, zone)` |
| `feature/gallery/GalleryViewModel.kt` | `Catalog.dayCounts`, `Content.dayCounts` |
| `feature/gallery/GalleryScreen.kt` | 다이얼로그 → 시트 교체 |
| `res/values/strings.xml` | 시트 제목·월 라벨·셀 설명 |
| 테스트 | `DateRangeSelectionTest`, `GallerySectionTest`(countByDay) |

## 5. 검증

- 로컬: detekt · 단위 테스트 · assembleDebug · lint. R8 는 리플렉션 없는 라이브러리라 규칙 추가 없음(`assembleRelease` 로 `missing_rules.txt` 부재 확인).
- 기기(사용자 지시가 있을 때만): `manual-tests/01-gallery-basics.md` GAL-12/13 을 새 UI 기준으로 고치고 GAL-22(사진 없는 날 비활성·개수 점), GAL-23(과거 달로 스크롤 → 가장 오래된 달에서 멈춤) 추가.
