# 상세보기 안정화 — 비율·화질·재생바

작성 2026-09-08. 실기기(Galaxy S23+, Android 16) 수동 테스트 중 상세보기·동영상·넘기기에서 비율, 화질, 재생바가 깨지는 현상이 보고됐다.
이 문서는 **작업 전에** 원인을 코드로 확정하고 수정 범위를 못 박기 위한 것이다. 구현은 이 문서의 §3 순서대로 진행하고, 검증은 §4 의 항목을 `manual-tests/04-viewer.md` 에 추가해 상태를 갱신한다.

## 1. 증상

| # | 증상 | 어디서 |
|---|---|---|
| S1 | 사진이 화면 크기에 비해 흐릿하다. 확대하면 더 심하다 | 상세보기 사진, 넘긴 뒤 |
| S2 | 영상이 화면 비율대로 늘어난다(가로 영상이 세로로 찌그러짐, 가로 고정 시 반대) | 영상 페이지, 가로 보기 |
| S3 | 재생바(탐색 바·시간 표시)가 하단 바(ⓘ·휴지통) 및 시스템 내비게이션 바와 겹쳐 가려진다 | 영상 컨트롤 |
| S4 | 상세보기 하단 바 자체가 시스템 내비게이션 바 위에 그려진다 | 모든 항목 |
| S5 | 넘길 때 영상 표면이 페이지와 따로 움직이거나, 아래로 끌어 닫을 때 영상만 축소되지 않는다 | 넘기기 / 스와이프 닫기 |
| S6 | 넘겨서 들어온 영상이 첫 프레임 전까지 검게 비어 있다 | 넘기기 |
| S7 | 회전 정보(EXIF 90°/270°)가 있는 사진에서 히어로 끝 사각형·정보 패널 해상도가 가로세로 뒤바뀐다 | 히어로 진입, 정보 패널 |

## 2. 원인 (코드 근거)

### S1 — 상세보기 "원본" 요청이 시스템 썸네일로 처리된다
`core/ui/image/MediaStoreThumbnailFetcher.Factory` 는 **authority 가 `media` 인 모든 content URI** 를 가로챈다. `ImagePage` 의 `ImageRequest.size(4096)` 요청도 여기로 들어가 `ContentResolver.loadThumbnail(uri, Size(4096, 4096))` 이 호출되고, MediaProvider 는 요청 크기와 무관하게 **자기가 캐시한 썸네일**(화면 짧은 변의 절반 수준)을 돌려준다. 그 결과 1080×2340 화면에 500px 대 비트맵이 늘어나 그려진다.
→ Fetcher 는 **썸네일로 표시된 요청만** 처리해야 한다. 나머지는 Coil 기본 `ContentUriFetcher` + `ImageDecoder` 가 원본을 요청 크기까지만 샘플링해 디코딩(EXIF 회전 포함)하도록 돌려준다.

### S2 — `PlayerSurface` 는 비율을 맞추지 않는다
media3 `PlayerSurface` 는 주어진 Modifier 크기의 표면만 만든다. `Modifier.fillMaxSize()` 이면 영상이 표면 크기로 **늘어난다**. 비율은 `PresentationState.videoSizeDp` 를 읽어 `resizeWithContentScale` 로 표면 크기를 맞추거나, 이를 묶은 `ContentFrame(player, surfaceType, contentScale, shutter)` 를 써야 한다(media3 1.11 에 존재, `javap` 로 확인).

### S3·S4 — edge-to-edge 인셋 미적용
`MainActivity.enableEdgeToEdge()` 상태에서
- `ViewerBottomBar` 는 일반 `Column` 이라 내비게이션 바 인셋을 소비하지 않는다(`TopAppBar` 는 기본 `windowInsets` 로 상태 바를 처리하므로 상단은 정상).
- 영상 컨트롤(설정 행·탐색 바)은 페이저 **콘텐츠 안** `Alignment.BottomCenter` 에 있어 Scaffold 의 `innerPadding` 을 모른다 → 하단 바 뒤로 깔리고, 그 하단 바는 다시 시스템 바와 겹친다. 스크린샷(22:07)에서 "0:00 / 0:05" 텍스트가 시스템 내비게이션 버튼과 같은 y 에 있었다.

### S5·S6 — SurfaceView 와 검은 표면
`PlayerSurface` 기본 표면은 `SurfaceView`. 별도 윈도우 레이어라 `HorizontalPager` 의 이동, `swipeToDismiss` 의 `graphicsLayer` scale/alpha, `contentHidden` alpha 가 **적용되지 않는다**. 첫 프레임 전에는 검은 표면만 보인다.

### S7 — MediaStore `WIDTH/HEIGHT` 는 회전 전 픽셀 크기
`MediaStoreRepository` 는 `ORIENTATION` 을 읽지 않는다. 90°/270° 사진은 `width/height` 가 뒤바뀐 채 `HeroOverlay.fittedRect` 와 정보 패널에 들어간다.

## 3. 수정 방침 (이 순서로 구현)

| # | 변경 | 파일 | 근거/비용 |
|---|---|---|---|
| F1 | Coil `Extras` 키 `mediaStoreThumbnail` 추가. `ImageRequest.Builder.mediaStoreThumbnail()` 확장을 만들고 Factory 는 이 키가 있을 때만 Fetcher 를 만든다. 그리드·히어로·중복 화면 썸네일 요청에 키 부여. 상세보기 요청은 키 없음 → 원본 디코딩 | `core/ui/image/*`, `GalleryGrid`, `HeroOverlay`, `DuplicatesScreen` | 원본 디코딩은 `size(4096)` 상한으로 12MP 기준 ≈48MB 하드웨어 비트맵 1장. 페이저는 현재 페이지만 로드하므로 최대 2~3장 |
| F2 | 영상은 `ContentFrame(surfaceType = TEXTURE_VIEW, contentScale = Fit, shutter = 썸네일)` 로 교체. `keepContentOnReset = false` | `VideoPage` | TextureView 는 합성 비용이 약간 더 들지만(HDR 불가) 페이저·스와이프 변형이 정확히 적용된다. 갤러리 뷰어의 표준 선택 |
| F3 | `ViewerBottomBar` 에 `safeDrawing(Bottom+Horizontal)` 인셋 패딩. `MediaViewerScreen` 이 `innerPadding` 의 bottom 을 `VideoPage(controlsBottomPadding)` 로 넘겨 컨트롤이 하단 바 **위**에 놓이게 함 | `ViewerChrome`, `MediaViewerScreen`, `VideoPage` | 페이저 자체는 그대로 전체 화면(사진이 바 뒤까지 깔림) |
| F4 | `ORIENTATION` 컬럼 읽어 90/270 이면 width/height 교환 | `MediaStoreRepository` | 영상 압축(짧은 변 기준)은 min/max 만 쓰므로 영향 없음 |
| F5 | 문서 갱신: `ARCHITECTURE.md` 상세보기 절, `manual-tests/04-viewer.md` 에 §4 항목 추가 | docs | |

하지 않는 것: 인접 페이지 프리로드(`beyondViewportPageCount = 1`) — 영상 페이지가 프리로드되면 ExoPlayer 가 동시에 2~3개 준비돼 전력·메모리 비용이 크다. 사진만 프리로드하려면 페이지 종류별 분기가 필요하므로 별도 판단으로 미룬다.

## 4. 검증 항목 (→ `manual-tests/04-viewer.md`)

| ID | 시나리오 | 기대 |
|---|---|---|
| VW-20 | 사진 상세보기에서 글자가 있는 사진을 2.5배 확대 | 그리드 썸네일보다 뚜렷하게 선명(원본 디코딩). 로그에 `loadThumbnail` 없이 원본 디코딩 |
| VW-21 | 가로(16:9) 영상을 세로 화면에서 재생 | 위아래 레터박스, 찌그러짐 없음. 가로 보기 토글 시 좌우 레터박스 없이 꽉 참 |
| VW-22 | 하단 바(ⓘ·휴지통) | 시스템 내비게이션 바 위에 떠 있고 겹치지 않음(3버튼·제스처 모두) |
| VW-23 | 영상 컨트롤(설정 행·탐색 바) | 하단 바 위에 겹침 없이 쌓임. "0:00 / 총시간" 이 가려지지 않음 |
| VW-24 | 영상 페이지로 넘기는 도중 / 아래로 끌어 닫기 | 영상 표면이 페이지와 함께 움직이고, 닫을 때 함께 축소·투명 |
| VW-25 | 영상 페이지 진입 직후 | 검은 화면 대신 썸네일이 보이고 첫 프레임으로 교체 |
| VW-26 | EXIF 회전 90° 사진(세로로 찍은 사진) 히어로 진입·정보 패널 | 히어로 끝 사각형이 세로 비율, 해상도 표기가 세로×가로 순 |

adb 로 확인 가능한 것: VW-21(가로 영상 스크린샷의 레터박스), VW-22/23(스크린샷 y 좌표 비교), VW-25(진입 직후 스크린샷). VW-20 은 스크린샷 선명도 비교, VW-24·26 은 폰에서 직접.

## 5. 결과 (2026-09-08, Galaxy S23+ / Android 16, 디버그 빌드)

| 항목 | 결과 |
|---|---|
| VW-20 화질 | ✅ 1배·2.5배 모두 선명. 상세보기 진입 동안 `loadThumbnail` 로그 0건 |
| VW-21 영상 비율 | ✅ 16:9 영상 위아래 레터박스, 세로 영상 가로 고정 시 좌우 레터박스 |
| VW-22/23 인셋·컨트롤 | ✅ 하단 바가 내비게이션 바 위, 영상 컨트롤이 그 위에 겹침 없이 쌓임 |
| VW-25 셔터 | ✅ 진입 직후 썸네일 표시 |
| VW-24 표면 이동 | ✅ 페이지 스와이프·아래로 끌기 도중 스크린샷에서 영상 표면이 페이지와 함께 이동·축소 |
| 넘긴 뒤 페이지 화질 | ✅ 2번째 페이지도 원본 선명도 |
| VW-26 회전 사진 | ⬜ 폰에서 직접 확인 필요(2021년 세로 사진) |

테스트 중 추가로 발견해 같은 커밋에서 고친 것:
- **가로 보기 해제가 안 됨**: 해제 시 `UNSPECIFIED` 를 주면 자동 회전이 꺼진 기기에서 시스템이 `user_rotation` 을 1(가로)로 바꿔 상세보기를 나가도 가로로 남았다. `OrientationLockEffect` 가 잠그기 전 사용자 방향(`ACCELEROMETER_ROTATION`/`USER_ROTATION`)을 기억해 해제·종료 시 잠깐 명시한 뒤 1초 후 `UNSPECIFIED` 로 돌린다. 해제·뒤로가기 두 경로 모두 세로 복귀, `user_rotation=0` 유지 확인.
- **상세보기 진입 크래시**: `ACCESS_MEDIA_LOCATION` 을 매니페스트에만 선언하고 런타임 요청을 안 해 `setRequireOriginal` 경로가 `UnsupportedOperationException` 으로 죽었다. 읽기 권한과 함께 요청하고, 권한이 없으면 원본 요청을 생략하며 예외도 잡는다.
- **롱프레스만 하면 선택이 풀림**(갤러리): `detectDragGesturesAfterLongPress` 가 움직임 없는 UP 을 소비하지 않아 썸네일 `clickable` 의 onClick 이 실행됐다. `DragSelect` 가 롱프레스 뒤 이벤트를 Initial 패스에서 소비하도록 직접 구현.

## 6. 사진 열기 멈칫 — 상세보기가 목록을 다시 조회한다 (2026-09-08 추가)

**증상** 썸네일을 탭하면 히어로 연출이 끝난 뒤 잠깐 검은 화면이 머물고 사진이 나타난다.

**원인** `MediaViewerViewModel.load()` 가 `mediaRepository.observeMedia(filter)` 를 새로 구독하고, `observeMedia` 는 구독마다 `queryAll` 을 즉시 실행한다(캐시 없음). 6,119행 커서 읽기 + `MediaItem` 변환 + 날짜순 재정렬이 끝날 때까지 `isLoading = true` 라 화면이 비어 있다. 기기 로그에 사진을 연 시각마다 `MediaStore query(All): 6119 items` 가 찍혀 확인(23:14:23 / :31 / :33 / :38 / :42 / :44). 갤러리 ViewModel 은 이미 같은 목록을 들고 있으므로 두 화면이 같은 작업을 두 번 한다.

**방침**
| # | 변경 | 파일 | 근거/비용 |
|---|---|---|---|
| F6 | `observeMedia(filter)` 를 필터별로 `shareIn(replay = 1, WhileSubscribed(5s))` 해 앱 범위에서 한 번만 조회·관찰한다. 상세보기는 구독 즉시 갤러리가 받은 목록을 replay 로 받고, ContentObserver 도 하나만 등록된다 | `MediaStoreRepository` | replay 캐시(6천 개 참조 리스트 1개)만 추가. 구독자가 5초 이상 없으면 업스트림(옵저버)은 해제되고, 다시 구독하면 캐시를 먼저 준 뒤 `flowOf(Unit)` 로 즉시 재조회하므로 stale 상태가 오래가지 않는다 |
| F7 | 문서: `ARCHITECTURE.md` 의 "갤러리와 같은 필터로 다시 관찰" 문장을 공유 구독으로 고친다 | docs | |

하지 않는 것: 탭한 항목을 `MediaViewerKey` 에 통째로 실어 목록 전에 그리기 — F6 만으로 목록이 첫 프레임에 도착하므로(갤러리가 백스택에 살아 있는 동안 replay), `Uri` 직렬화 비용을 들일 이유가 없다. F6 적용 후에도 멈칫이 남으면 그때 재검토.

**검증** 사진을 3번 연속 열 때 `MediaStore query` 로그가 새로 찍히지 않아야 하고(`adb logcat | grep "MediaStore query"`), 탭 직후 약 300ms 스크린샷에 사진이 이미 보여야 한다. → `manual-tests/04-viewer.md` VW-28.

**결과(2026-09-08)** 앱 시작 시 조회 1회만 찍히고, 사진 3회 연속 열기 동안 `MediaStore query` 0건. 탭 350ms 뒤 스크린샷에 원본이 이미 표시됨(VW-28 ✅). `Skipped frames`/`Davey` 로그 없음.

## 7. 사진 열릴 때 '찰칵' 흰색 번쩍임 (2026-09-08 추가)

**증상** 썸네일을 탭하면 사진이 커지기 전에 화면이 잠깐 하얗게 튀고("찰칵") 열린다.

**원인**
- 히어로 진입의 Nav 전환이 `fadeIn(뷰어) togetherWith fadeOut(갤러리)` 다. 중간 프레임에서 두 화면이 모두 반투명해지고, 그 뒤에 있는 **액티비티 윈도우 배경**이 비친다.
- 윈도우 배경은 매니페스트 테마 `Theme.Material.Light.NoActionBar` 의 흰색(`#fafafa`, 로그 `setWindowBackground color=fffafafa`)이다. 앱 콘텐츠는 시스템 다크 모드를 따라 어두운데 윈도우는 항상 흰색이라, 전환·콜드 스타트마다 흰색이 드러난다.
- 결과: 썸네일 → (반투명 갤러리 + 반투명 검은 배경 + 흰 윈도우 = 밝은 회색 번쩍) → 사진.

**방침**
| # | 변경 | 파일 | 근거 |
|---|---|---|---|
| F8 | 히어로 진입 전환: 갤러리는 `ExitTransition.KeepUntilTransitionsFinished` 로 **불투명하게 남기고**, 뷰어만 `fadeIn(standard 250ms)`. 뷰어의 검은 배경이 서서히 덮이며(어두워짐) 히어로 이미지는 썸네일 자리에서 시작하므로 "사진이 제자리에서 확장되고 배경이 어두워지는" 연출이 된다. 전환 길이 = 히어로 길이 | `AppNavigation` | 반투명 겹침 구간 자체를 없앤다 |
| F9 | 뷰어에서 뒤로(팝·예측 뒤로): 갤러리는 `EnterTransition.None` 으로 즉시 깔고 뷰어만 축소·페이드 | `AppNavigation` | 역방향도 같은 원리 |
| F10 | 다크 모드용 `values-night/themes.xml` 추가, 윈도우 배경을 검정으로. 라이트 모드는 그대로 | `res/values-night` | 남은 fade-through(설정 등)와 콜드 스타트에서 흰색 번쩍 제거 |

**검증** 탭 후 약 80~120ms 시점 스크린샷에 흰색/밝은 회색 프레임이 없고, 갤러리가 어두워지는 위로 썸네일이 커지는 중간 상태가 보여야 한다(ANI-20/21).

**결과(2026-09-08)** 애니메이션 배율 5x 로 탭 후 250ms(실제 약 50ms 시점) 스크린샷: 갤러리가 어두워진 위로 썸네일이 셀 자리에서 확장 중, 상·하단 바 슬라이드 인, 흰색/밝은 프레임 없음(ANI-20/21 ✅). 다크 모드 윈도우 배경 검정 적용 확인.
