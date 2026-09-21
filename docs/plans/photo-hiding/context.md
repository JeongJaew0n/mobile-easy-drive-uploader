# 맥락

## 새로 만드는 것

| 파일 | 역할 |
|---|---|
| `core/data/hidden/HiddenMediaEntity.kt` | Room `hidden_media(mediaId PK, hiddenAt)` |
| `core/data/hidden/HiddenMediaDao.kt` | 관찰·추가·해제·정리 |
| `core/data/hidden/HiddenMediaRepository.kt` | 위를 감싼다 |
| `core/data/hidden/HiddenPin.kt` | PBKDF2 해시·검증·잠금 계산(순수 함수) |
| `core/data/hidden/HiddenPinRepository.kt` | DataStore 에 소금·해시·실패 횟수·잠금 시각 |
| `feature/hidden/HiddenRoute.kt` `HiddenScreen.kt` `HiddenViewModel.kt` | PIN 게이트 + 숨긴 사진 그리드 |
| `docs/PHOTO_HIDING.md` | 설계 |
| `docs/manual-tests/13-photo-hiding.md` | 기기 확인 |

## 고치는 것

| 파일 | 할 일 |
|---|---|
| `AppDatabase.kt` | v9 → v10, `HiddenMediaEntity` + AutoMigration |
| `GalleryViewModel.kt` | 숨김 id 를 빼고 목록·`dayCounts` 계산, `hideSelected()` |
| `GalleryActions.kt` | ⋮ 에 "숨긴 사진 보기", 선택 하단 바에 "숨기기" |
| `MediaViewerViewModel.kt` | 숨김 제외 + `hide()` |
| `ViewerChrome.kt` / 상세보기 ⋮ | "숨기기" 항목 |
| `NavKeys.kt` | `HiddenKey` |
| `AppNavigation.kt` | 진입 연결 |
| `strings.xml` | 문구 |
| `docs/glossary/README.md` | '숨김' 용어 |

## 이미 있어서 쓸 수 있는 것

- **`TrashScreen`/`TrashViewModel`** — 별도 화면 + 날짜 그리드 + 선택 + 하단 액션의 완성된 본보기.
  `GalleryGrid` 를 그대로 재사용한다.
- **`MediaActionController`** — 숨김은 MediaStore 를 건드리지 않으므로 **쓰지 않는다**(동의 흐름 불필요).
- **`GalleryViewModel.MemoryFilters`** — 숨김도 여기 끼워 넣는다. 탭보다 **더 앞**이다(전 화면 공통).
- **`UserPreferencesRepository`** — DataStore 패턴. PIN 은 **별도 DataStore 파일**로 둔다
  (환경설정 백업에 섞이지 않게).

## 조심할 것

- **`dayCounts` 는 숨김 제외 후 세야 한다.** 달력에 점이 있는데 목록이 비면 버그로 보인다.
- **상세보기 범위도 빼야 한다.** 갤러리에서 안 보이는데 스와이프로 나오면 숨김이 아니다.
- **PIN 을 DataStore 에 평문으로 두지 않는다.** 해시+소금만.
- **잠금 시각은 `System.currentTimeMillis()` 로 저장한다.** 사용자가 기기 시계를 되돌리면 잠금이 풀린다 —
  이건 감수한다(로컬 앱이고, 시계 조작까지 막으려면 서버가 필요하다). 문서에 적는다.
- 숨긴 사진을 **영구 삭제**하면 `hidden_media` 에 고아 행이 남는다 → 훑을 때 정리(`prune`).
- 화면 캡처 차단(`FLAG_SECURE`)은 **넣지 않는다**. 숨긴 사진 화면에만 걸면 일관성이 없고,
  이 기능은 암호화가 아니라 엿보기 방지다. 과하게 약속하지 않는다.
