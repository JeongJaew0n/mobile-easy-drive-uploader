# 맥락

## 건드리는 파일

| 파일 | 할 일 |
|---|---|
| `feature/gallery/MediaSource.kt` | 신규. 경로 → 출처 분류(순수 함수) |
| `feature/gallery/GalleryViewModel.kt` | `selectedTab` 상태, `setTab()`, `contentFlow` 에서 탭 우선 적용 |
| `feature/gallery/GalleryScreen.kt` | 상단바 아래 `PrimaryTabRow` |
| `feature/gallery/GalleryRoute.kt` | `onTabChange` 연결 |
| `res/values/strings.xml` | 탭 4개 라벨 |
| `docs/glossary/README.md` | **먼저** '출처' 용어 추가 |
| `docs/GALLERY.md` 또는 해당 설계 문서 | 탭 규칙 기록 |
| `docs/manual-tests/01-gallery-basics.md` | 기기 확인 항목 |

## 이미 있어서 쓸 수 있는 것

- `MediaItem.relativePath` — MediaStore 조회에 이미 포함(`"DCIM/Camera/"` 형태). 재조회 불필요.
- `GalleryViewModel.filterVersion` / `animatedVersion` — 필터가 바뀐 첫 목록은 애니메이션 없이 교체.
  탭 전환도 같은 취급이라 `filterVersion++` 만 하면 된다.
- `MemoryFilters` — combine 인자 수를 줄이려 묶어둔 데이터 클래스. 탭도 여기에 넣는다.

## 조심할 것

- `dayCounts` 는 **기간 필터 직전** 목록으로 센다. 탭을 그보다 앞에 넣어야
  기간 선택 달력이 그 탭의 날짜만 보여준다.
- `albumsFrom(items)` 는 이동 대상 폴더 목록이다. 탭으로 걸러진 목록에서 뽑으면
  "카메라 탭에서 카카오톡 폴더로 이동"이 불가능해진다 → **앨범 목록은 탭 적용 전 목록에서 뽑는다.**
- 선택 모드에서는 탭을 숨긴다. 탭을 누르면 선택이 풀리는데, 선택 상단바와 함께 두면
  실수로 누르기 쉽다.
- `MediaFilter.Trashed` 는 휴지통 화면이 따로 쓴다. 탭은 갤러리 목록에만 붙인다.
