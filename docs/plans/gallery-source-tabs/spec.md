# 갤러리 출처 탭

## 왜

한 목록에 카메라 사진과 카카오톡 이미지가 섞여 들어온다. 이 기기 기준으로
카메라 4023장 사이에 카카오톡 1013장이 날짜순으로 끼어 있어, 찍은 사진을 훑어보기가 어렵다.

## 무엇을

갤러리 상단에 **고정 4탭**을 둔다. 탭은 사진의 **출처**로 나눈다.

| 탭 | 대상 | 이 기기 |
|---|---|---|
| 전체 | 전부 | 6179 |
| 카메라 | `DCIM/` 아래, 스크린샷 제외 | 4493 |
| 스크린샷 | 경로에 `Screenshots` 세그먼트 | 469 |
| 다른 앱 | 나머지(`Pictures/<앱>`, `Download`, `Documents` …) | 1217 |

### 왜 이 규칙인가

기기를 실제로 훑어보고 정했다(2026-09-17).

```
DCIM/Camera/                4023    카메라 앱
DCIM/중국 연태 …/             378    사용자가 직접 만든 앨범
DCIM/으으/  DCIM/일본어 …/     66    〃
DCIM/CandyCam/ DCIM/행복이/    20    카메라류 앱
DCIM/Screenshots/            461    스크린샷
Pictures/KakaoTalk/         1013    다른 앱
Pictures/페이북·Naver·Somoim…   40    〃
Download/  Documents/…        95    〃
SilentCamera/ (최상위)         17    〃 — DCIM 밖이라 '다른 앱'
```

- **`DCIM/` 아래는 전부 카메라 탭.** 사용자가 직접 만든 앨범(중국 연태·으으)도 "내가 남긴 사진"이다.
  폴더 이름으로 카메라 앱을 가려내려 하면 기기·앱마다 달라 끝이 없다.
- **스크린샷은 `DCIM/` 아래여도 따로.** 개수가 많고(469) 성격이 달라 카메라 사진과 섞이면 같은 문제가 반복된다.
  `DCIM/Screenshots` 와 `Pictures/Screenshots` 가 둘 다 있어 **경로 세그먼트**로 판정한다.
- **`DCIM/` 밖은 전부 다른 앱.** `SilentCamera/` 처럼 최상위에 쓰는 카메라류 앱도 여기로 간다.
  아쉽지만 "DCIM 밖에 쓰는 앱"이라는 사실은 맞고, 규칙이 한 줄로 유지된다.

### 탭과 기존 필터

**탭을 바꾸면 즐겨찾기·기간·카테고리·백업 안 됨 필터가 모두 풀린다**(2026-09-17 사용자 결정).
선택 모드도 해제한다. 탭은 "지금 무엇을 보고 있는가"의 최상위 기준이고,
이전 탭에서 걸어둔 조건이 따라오면 결과가 비어 보이는 이유를 알기 어렵다.

### 비어 있는 탭

4탭은 **항상 보인다**. 개수가 0이면 기존 빈 목록 문구를 쓴다.
데이터에 따라 탭이 생겼다 사라지면 위치가 흔들려서, 고정을 택한다.

## 어떻게

### 분류 — `MediaSource`

```kotlin
enum class MediaSource { CAMERA, SCREENSHOT, OTHER }

fun MediaSource.Companion.of(item: MediaItem): MediaSource
```

`MediaItem.relativePath` 만 본다(이미 있는 필드, MediaStore 재조회 없음).
순수 함수라 단위 테스트로 굳힌다. 경로가 빈 레거시 행은 `OTHER`.

둘 곳: 지금은 갤러리만 쓰므로 `feature/gallery/MediaSource.kt`.
자동 백업·자동 태그가 같이 쓰게 되면 그때 `core/domain/model` 로 내린다.

### 상태

`GalleryViewModel` 에 `MutableStateFlow<GalleryTab>` 을 더한다(`GalleryTab.All` 이 기본).
`MemoryFilters` 보다 **앞에서** 거른다 — 탭이 가장 바깥 범위다.

```
observeMedia(filter)
  → 탭(출처)          ← 새로 추가
  → 백업 안 됨
  → 카테고리
  → dayCounts 계산
  → 기간
```

`dayCounts` 를 탭 안쪽에서 세므로 기간 선택 달력도 그 탭의 날짜만 보여준다.

`setTab()` 은 `clearSelection()` + `filterVersion++` 에 더해 다른 필터 상태를 전부 초기값으로 되돌린다.

### 화면

`GalleryScreen` 의 `Scaffold(topBar=)` 안, `GalleryTopBar` **아래**에 `PrimaryTabRow`.
선택 모드에서는 상단바가 `SelectionTopBar` 로 바뀌므로 탭도 함께 사라진다(기존 `AnimatedContent` 안에 둔다).

탭 라벨은 `res/values/strings.xml`. 개수는 라벨에 넣지 않는다 —
탭마다 세려면 전체 목록을 네 번 훑어야 하고, 개수는 이미 상단바에 나온다.

## 확인

- 단위 테스트: `MediaSourceTest` (경로 분류 경계), `GalleryViewModel` 의 탭 전환 시 필터 초기화
- detekt · testDebugUnitTest · assembleDebug · lintDebug
- 기기: `docs/manual-tests/01-gallery-basics.md` 에 항목 추가
