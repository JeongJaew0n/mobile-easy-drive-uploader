# 삼성 갤러리 연동 검토

2026-09-23 작성, 같은 날 **실기기로 측정 완료**. 추정으로 적었던 부분을 결과로 바꿨다.

## 요약

| 항목 | 공유 | 근거 |
|---|---|---|
| **앨범** | **이미 된다** | 안드로이드에서 앨범은 곧 폴더고, 우리는 실제 폴더를 옮긴다 |
| **즐겨찾기** | **된다 (양방향, 측정 완료)** | 삼성도 플랫폼 `IS_FAVORITE` 를 쓴다 |
| **그룹** | **안 된다 (2026-09-27 재확인)** — 카톡에서 보이는 건 앨범(폴더)이다(§4.2) | 삼성 내부 DB. 읽기 권한이 `signature\|privileged` 라 삼성·Google 시스템 앱만 읽는다(§4.1) |

## 1. 먼저 — "연동 API" 는 없다

삼성 갤러리(`com.sec.android.gallery3d`)는 **공개 SDK 도, 문서화된 ContentProvider 도 제공하지 않는다.**
두 앱이 무언가를 나눠 갖는 길은 **MediaStore 하나뿐**이다.

그래서 질문은 "삼성과 연동할 수 있나" 가 아니라 **"그 개념이 MediaStore 에 있나"** 로 바뀐다.
MediaStore 에 있으면 저절로 공유되고, 없으면 방법이 없다.

## 2. 앨범 — 이미 공유된다

안드로이드에 "앨범" 이라는 것은 없다. 갤러리 앱들이 **폴더**(`RELATIVE_PATH`/`BUCKET_DISPLAY_NAME`)를
앨범이라고 부를 뿐이다. 삼성 갤러리도 마찬가지다.

우리 앱은 이미 같은 기준을 쓴다.

- 앨범 목록: `albumsFrom()` 이 `relativePath` 로 묶는다 (`core/domain/model/Album.kt`)
- 앨범으로 이동: `RELATIVE_PATH` 를 바꿔 **파일을 실제로 옮긴다** (`MediaStoreRepository.move`)

따라서 **우리 앱에서 옮기면 삼성 갤러리에도 그대로 보인다.** 반대도 같다. 추가 작업이 없다.

공유되지 **않는** 것은 삼성이 자기 DB 에만 두는 것들이다 — 앨범 정렬 순서, 커버 사진,
숨긴 앨범, 앨범을 묶은 그룹. 이건 우리가 읽을 수도 쓸 수도 없다.

## 3. 즐겨찾기 — 측정이 필요하다

`MediaStore.MediaColumns.IS_FAVORITE` 는 **플랫폼 컬럼**이다(API 30+). 우리 앱은 이미 이걸 쓴다.

- 읽기: `MediaFilter.Favorites` → `IS_FAVORITE = 1` (`MediaStoreRepository.kt:240`)
- 쓰기: `requestFavorite` → `createFavoriteRequest` 동의 후 변경

**측정 결과: 삼성도 같은 컬럼을 쓴다.** 양방향으로 이미 되고 있었다(2026-09-23, SS-01~03).

- 삼성에서 오래 쌓인 즐겨찾기 67건이 `is_favorite=1` 로 그대로 조회됐다(2022~2026년에 걸쳐 있어 우리 앱이 만든 것이 아니다)
- 우리 앱의 "즐겨찾기만 보기" 가 정확히 67개를 보여준다
- 우리 앱에서 ♥ 를 하나 더 지정하니 MediaStore 가 68이 되고, **삼성 갤러리의 즐겨찾기 앨범도 68**로 늘었다

**코드 변경 없이 이미 동작한다.** 사용자가 모르고 있었을 뿐이다.

### 측정에 쓴 방법

```bash
# 1) 삼성 갤러리에서 사진 하나를 즐겨찾기(♥)로 지정한 뒤
adb shell "content query --uri content://media/external/images/media \
  --projection _id:_display_name:is_favorite --where \"is_favorite=1\"" | head

# 2) 반대 방향 — 우리 앱에서 즐겨찾기 지정 후 삼성 갤러리에 뜨는지 눈으로 확인
```

1번에서 삼성이 찍은 사진이 나오면 **양방향으로 이미 된다.**
빈 결과면 삼성은 자기 DB 를 쓰는 것이고, 그때는 할 수 있는 일이 없다.

함께 볼 것:

```bash
adb shell dumpsys package com.sec.android.gallery3d | grep -i -A3 provider
```

측정 결과 노출된 provider 는 **Bixby 카드용(`GalleryCardProvider`)뿐**이었다.
데이터 접근 경로가 아니다 — MediaStore 외에 길이 없다는 §1 이 실측으로 확인됐다.

## 4. 그룹 — 안 된다

"그룹" 이 삼성 갤러리의 무엇을 가리키는지에 따라 셋인데, 셋 다 같은 결론이다.

| 후보 | 정체 | MediaStore 에 있나 |
|---|---|---|
| 그룹으로 보기 | 연속 촬영·비슷한 사진을 한 장처럼 쌓아 보여주는 것 | 없음 |
| 사람/얼굴 그룹 | 얼굴 인식 결과 묶음 | 없음 |
| 앨범 그룹 | 앨범 여러 개를 상위로 묶는 UI | 없음 |

전부 삼성이 자기 DB(`com.sec.android.gallery3d`)에 두는 파생 데이터다.
읽을 API 도, 쓸 API 도 없다.

**우리 쪽 대안**은 이미 있다 — 이 앱의 **카테고리**가 사용자가 직접 묶는 그룹이고,
**자동 태그**가 기계가 묶는 그룹이다. 다만 둘 다 우리 앱 안에서만 보인다.
삼성 갤러리에 내보내려면 **카테고리 이름의 폴더를 만들어 파일을 옮기는 것**이 유일한 길인데,
그건 "공유" 가 아니라 **파일을 실제로 이동시키는 것**이라 성격이 다르다(되돌리기도 어렵다).
원하면 별도로 검토한다.

## 4.1 다시 확인 (2026-09-27) — 다른 앱에선 그룹이 보인다는데?

사용자 질문: "다른 앱에서 보니까 삼성 갤러리에서 만든 그룹이 보이더라. 우리 앱도 되지 않나?"
9-23 에는 삼성 갤러리 패키지의 provider 만 봤다. 이번엔 **그룹이 실제로 어디에 있고, 누가 읽을 수 있는지** 를
S23+(One UI, Android 16, 삼성 갤러리 15.8.00.84, SecMediaProvider 16.1.30.0)에서 끝까지 따라갔다.

### 결론

**우리 앱은 못 읽는다. 그룹을 보여 주는 "다른 앱" 은 삼성(또는 Google)이 서명한 시스템 앱이다.**
그 앱들은 일반 앱이 받을 수 없는 권한을 갖고 있다. Play 스토어로 설치하는 앱은 같은 길이 없다.

### 그룹은 어디에 있나

| 무엇 | 주소 | 읽기 권한 | 보호 수준 |
|---|---|---|---|
| **앨범 그룹**(앨범 여러 개를 묶은 것) | `content://com.sec.android.gallery3d.provider2/album_group` | `com.sec.android.gallery3d.provider2.READ` | **signature\|privileged** |
| 삼성 미디어 DB(얼굴 그룹 `faces_group`, 연속 촬영·비슷한 사진 `group_contents`·`burst_group_id`, `cluster_*`) | `content://secmedia/...` (`com.samsung.android.providers.media`) | `com.samsung.android.providers.media.READ` | **signature\|privileged** |

`signature|privileged` 는 **삼성과 같은 키로 서명했거나, 시스템 파티션에 미리 깔린 허용 목록 앱** 만 받는다.
사용자가 허용하는 런타임 권한이 아니라서 우리 앱이 요청해도 설치 시점에 조용히 거절된다.

앨범 그룹 주소는 삼성 갤러리 APK 안의 문자열(`content://com.sec.android.gallery3d.provider2/album_group`,
`AlbumGroupView`, `getAlbumGroupList`)에서, 보호 수준은 두 APK 의 매니페스트와 `dumpsys package permission` 에서 확인했다.

### 직접 읽어 봤다

일반 앱과 같은 비시스템 사용자(shell, uid 2000)로 조회하면 둘 다 막힌다.

```text
content://com.sec.android.gallery3d.provider2/album_group
  → SecurityException: Permission Denial ... requires com.sec.android.gallery3d.provider2.READ
content://secmedia/gallery
  → SecurityException: Permission Denial ... requires com.samsung.android.providers.media.READ
```

권한 없이 열려 있는 `com.sec.android.gallery3d.provider`(LocalProvider)도 있지만, 기기에 **등록돼 있지 않다**
("Could not find provider"). 공유용 `ShareProvider`(READ_EXTERNAL_STORAGE)는 공유할 파일을 넘기는 용도라 그룹과 무관하다.

### 그럼 "다른 앱" 은 누구인가

이 기기에서 두 권한 중 하나라도 가진 앱은 30개이고, **전부 삼성·Google 시스템 앱**이다.
그룹을 화면에 보여 줄 만한 것은 이쪽이다.

- 사진 액자 위젯(`com.samsung.android.widget.pictureframe`) — 둘 다 보유
- 내 파일(`com.sec.android.app.myfiles`) — 둘 다 보유
- 보안 폴더(`com.samsung.knox.securefolder`) — 둘 다 보유
- 홈 화면(`com.sec.android.app.launcher`), 카메라(`com.sec.android.app.camera`), 사진 편집(`com.samsung.app.newtrim`)

Galaxy Store 로 업데이트돼 "사용자 앱" 으로 분류되는 것(SmartThings·시계·음성 녹음)도 삼성 서명이다.
Google 포토(`com.google.android.apps.photos`)는 목록에 **없다** — Google 포토도 삼성 그룹은 못 읽는다.

**사용자가 본 앱이 위 목록에 없다면** 그 앱 이름으로 다시 확인한다. 권한 목록은 이렇게 뽑는다.

```bash
adb shell dumpsys package | awk '/^  Package \[/{pkg=$2}
  /com.sec.android.gallery3d.provider2.READ: granted=true/{print pkg}' | sort -u
```

### 우리 쪽에서 할 수 있는 것

| 방법 | 무엇이 되나 | 한계 |
|---|---|---|
| **삼성 그룹 읽기** | — | 길이 없다(위) |
| **우리 앱 안의 앨범 그룹** | 폴더(앨범) 여러 개를 이름 하나로 묶어 보기. 카테고리가 사진 단위라면 이건 폴더 단위다 | 삼성 그룹과 **따로** 만든다. 두 앱이 서로의 그룹을 모른다 |
| **카테고리로 대신** | 이미 있다 — 사진을 직접 묶는다 | 폴더 단위로 묶는 게 아니라 사진마다 붙인다 |

앨범 그룹을 우리 앱에 만들지는 사용자가 정한다. 만든다면 `hidden_media`·`chosen_media` 처럼 앱 DB 에
`(그룹, relativePath)` 표 하나로 충분하고, 파일은 건드리지 않는다.

## 4.2 카톡에서는 보인다 — 그건 "그룹" 이 아니라 앨범(폴더)이다 (2026-09-28)

사용자: "카톡에서 사진 고를 때 삼성에서 만든 그룹이 폴더로 보이더라."

§4.1 의 권한 목록과 부딪히는 것처럼 보여 S23+ 로 다시 쟀다. **부딪히지 않는다.**

**1. 카톡은 우리와 같은 권한뿐이다.** 카카오톡(26.8.2, `/data/app` — 사용자가 깐 앱)은
`READ_MEDIA_IMAGES`·`READ_MEDIA_VISUAL_USER_SELECTED` 만 있고, 삼성 권한(§4.1 표)은 없다.
그러니 카톡이 보여 주는 것은 **MediaStore 에서 온 것**이고, 우리 앱도 똑같이 볼 수 있다.

**2. 삼성 갤러리에서 사용자가 만든 앨범은 실제 폴더다.** 삼성 앨범 탭 → 모두 보기의 이름을 MediaStore 와 맞춰 봤다.

| 삼성 갤러리 앨범 | MediaStore `relative_path` | 개수 |
|---|---|---|
| 중국 연태 2025 02 14 ~ 02 16 | `DCIM/중국 연태 2025 02 14 ~ 02 16/` | 378 |
| 행복이 | `DCIM/행복이/` | 6 |
| 임시참조 | `DCIM/임시참조/` | 2 |
| Trip | `DCIM/Trip/` | 1 |
| 페이북 · Somoim · EastarJet | `Pictures/<이름>/` | 21 · 4 · 1 |

삼성에서 "앨범 만들기" 를 하면 `DCIM/<이름>/` 폴더가 생기고 사진이 그리로 옮겨진다. 카톡의 사진 선택 화면은
이 폴더(`bucket_display_name`)로 묶어 보여 준다 — 사용자가 본 "삼성에서 만든 것" 은 이것이다.

**3. 진짜 "앨범 그룹"(앨범 여러 개를 묶는 것)은 이 기기에 없다.** 앨범 탭 전체를 훑었는데 그룹 모양(겹친 타일)이
하나도 없었다. 그룹은 폴더를 만들지 않고 삼성 DB(`provider2/album_group`)에만 있으므로, 카톡도 우리도 보지 못한다.
**다른 기기에서 그룹을 만든 것이라면** 그 기기의 카톡에서 그룹 이름이 보이는지, 아니면 그룹 안의 앨범들이 따로 보이는지로
가를 수 있다 — 따로 보이면 이 결론 그대로다.

### 우리 앱에는 무엇이 빠져 있나

앨범(폴더) 목록은 이미 만든다(`albumsFrom()`, `core/domain/model/Album.kt`). 하지만 **이동 대상 고르기와 자동 백업
앨범 고르기에만** 쓰고, 갤러리에 **앨범별로 보는 화면이 없다.** 카메라 탭은 `DCIM/카메라` 와 `DCIM/행복이` 를
날짜순으로 섞어 보여 주고, '다른 앱' 탭은 최상위 한 칸(`AppFolders.folderOf`)으로만 묶는다.

그래서 "카톡처럼 삼성 앨범을 골라 보기" 는 **바로 만들 수 있다.** 새 권한도, 삼성 연동도 필요 없다.

| 방법 | 모양 | 비고 |
|---|---|---|
| **앨범 탭 추가** | 탭 하나 더(고른 사진·전체·카메라·스크린샷·다른 앱·**앨범**) → 폴더 격자 → 누르면 그 폴더 사진 | 탭이 여섯이 되면 S23+ 에서도 라벨이 좁다(5개에서 이미 여백을 줄였다) — 스크롤 탭으로 바꿔야 할 수 있다 |
| **⋮ 메뉴 "앨범"** | 숨김·휴지통처럼 별도 화면 | 탭 줄을 건드리지 않는다 |

어느 쪽이든 `albumsFrom(visible)` 을 그대로 쓰고, 숨긴 사진만 있는 폴더는 목록에서 뺀다(지금 이동 대상과 같은 규칙).

## 5. 권장

**할 일이 없다.** 즐겨찾기와 앨범은 이미 공유되고 있고, 그룹은 길이 없다.

남은 것은 **사용자에게 알리는 것**뿐이다 — 삼성에서 ♥ 한 사진이 이 앱에도 뜨고
그 반대도 된다는 사실, 그리고 앨범이 곧 폴더라 서로 그대로 보인다는 사실.
설정이나 도움말에 한 줄 적어두면 충분하다.

측정 항목은 `manual-tests/14-samsung-interop.md`.
