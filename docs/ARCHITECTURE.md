# 코드 구조

단일 `:app` 모듈, feature-first 패키지. 규모가 커지면 `core/*` → `:core:*` 모듈로 분리한다.

```
app/src/main/java/com/jjw/easygallery/
├── EasyGalleryApp.kt          # @HiltAndroidApp, WorkManager Configuration.Provider, Timber
├── MainActivity.kt            # @AndroidEntryPoint, 단일 Activity
├── core/
│   ├── common/di/             # Dispatcher qualifier, DispatchersModule
│   ├── data/auth/             # AuthRepository/TokenProvider, GoogleAuthRepository(AuthorizationClient)
│   ├── data/drive/            # DriveApi(Retrofit), DTO, AuthInterceptor/TokenAuthenticator, DriveRestRepository
│   ├── data/duplicates/       # MediaHasher(SHA-256), DuplicateRepository(크기 충돌만 해시·캐시·그룹), DuplicateScanWorker/Scheduler
│   ├── data/media/            # MediaRepository(조회+편집+EXIF) / MediaStoreRepository
│   │                          # MediaActions(MediaAction, MediaActionRunner), MediaActionController(동의 흐름 상태)
│   ├── data/prefs/            # UserPreferencesRepository (DataStore: 계정, 업로드 폴더)
│   ├── data/upload/           # DriveUploader(세션 시작/상태 조회/이어 올리기), ContentUriRequestBody,
│   │   │                      # UploadQueueRepository(큐), UploadLedgerRepository(영구 원장), VideoCompressor(Media3 Transformer)
│   │   ├── db/                # Room v4: AppDatabase, UploadTaskEntity/Dao(+width/height), UploadedMediaEntity/Dao, MediaHashEntity/Dao (schemas/, AutoMigration 1→…→4)
│   │   └── work/              # UploadWorker, UploadScheduler, UploadNotifications, AutoBackupWorker, AutoBackupScheduler
│   ├── domain/model/          # MediaItem, DriveFolder, DriveAccount, DuplicateGroup(findDuplicateGroups)
│   ├── domain/usecase/        # SignInUseCase, GetUploadFolderUseCase, EnqueueUploadsUseCase, ManageUploadQueueUseCase, AutoBackupUseCase
│   ├── navigation/            # AppNavKey(@Serializable NavKey), AppNavigation(NavDisplay)
│   └── ui/
│       ├── image/             # Coil Fetcher (MediaStore 썸네일)
│       ├── media/             # MediaActionEffect (동의 실행 + 결과 스낵바, 3개 화면 공용)
│       ├── motion/            # MotionSpecs 토큰 + LocalMotion (시스템 애니메이션 끄기 존중)
│       └── theme/             # Material 3 테마 (LocalMotion 제공)
└── feature/
    ├── gallery/               # GalleryRoute(권한·이벤트 배선) / GalleryScreen(Scaffold 조립) / GalleryTopBars / GalleryBanners
    │                          # GalleryGrid(+DragSelect), GalleryActions(하단 바·다이얼로그·메뉴), DateRangeDialog, GalleryViewModel, MediaPermission
    ├── viewer/                # MediaViewerRoute / MediaViewerScreen(페이저·회전) / ImagePage(+ZoomState) / VideoPage(재생·탐색·음량·배속) / ViewerChrome(상·하단 바·정보 패널)
    ├── trash/                 # 휴지통: 복원·완전 삭제·비우기 (GalleryGrid 재사용)
    ├── settings/              # 계정 연결/해제, 저장공간, 업로드 폴더·목록 진입, Wi-Fi/충전 제약 토글
    ├── drive/                 # Google Drive 탐색: 폴더·파일 목록(페이징), 새 폴더, 파일 열기, 업로드 폴더 지정 (DriveBrowserKey 중첩 push)
    ├── uploads/               # 업로드 목록: 상태·진행률, 실패 재시도, 완료 정리, 전체 취소
    ├── autobackup/            # 자동 백업 설정: 스위치, 앨범 선택, 영상 포함, 지금 검사, 기존 항목 백업
    └── duplicates/            # 완전 중복: 검사 진행률, 그룹 카드, 유지/제거 선택, 휴지통 이동
```

## 갤러리 데이터 흐름

```
ContentObserver(MediaStore.Files) ─debounce 300ms─┐
flowOf(Unit) (최초 1회) ───────────────────────────┴─▶ mapLatest { queryAll() } ─▶ List<MediaItem>
                                                                                      │
GalleryViewModel: permissionStatus.flatMapLatest ─▶ groupByDate() ─▶ GalleryUiState.Content(sections)
```

- **MediaStoreRepository**: `MediaStore.Files` 컬렉션을 `MEDIA_TYPE IN (IMAGE, VIDEO)` 로 한 번에 조회. `DATE_TAKEN` 이 0인 행은 `DATE_ADDED*1000` 으로 대체 후 메모리 정렬. 쿼리는 `ensureActive()` 로 취소 가능.
- **파생 상태 분리**: `GalleryViewModel` 은 목록에서만 나오는 값(섹션·앨범·id 맵)을 `Catalog` 로 한 번 계산하고, 선택 변경은 그 위에 O(k) 로만 결합한다. 선택 토글마다 6천 장을 재그룹하던 비용을 없앤 것 — 애니메이션 첫 프레임이 끊기지 않게 하는 기반.
- **전체 로드 (Paging 미사용)**: 수천~수만 장은 항목당 수백 바이트라 메모리 리스트로 충분하고(6천 장 ≈ 수 MB), 날짜 헤더·다중 선택·"이 날 전체 선택" 같은 기능이 훨씬 단순해진다. 수십만 장 규모 이슈가 실측되면 Paging 3 도입을 재검토한다.
- **권한**: `MediaPermission` 이 SDK 별 권한 집합과 상태(Full/Partial/Denied)를 계산. `GalleryRoute` 가 `LifecycleResumeEffect` 마다 상태를 ViewModel 에 알려 설정 앱에서 돌아온 경우도 반영. ViewModel 은 상태를 모르는 동안(`null`) 쿼리하지 않는다.
- **썸네일**: `ImageRequest.Builder.mediaStoreThumbnail()` 로 표시한 `content://media/...` 요청만 `MediaStoreThumbnailFetcher` 가 가로채 `ContentResolver.loadThumbnail` (시스템 썸네일 캐시) 사용. 실패 시 원본 스트림으로 폴백해 Coil 기본 디코더/`VideoFrameDecoder` 가 처리. 표시가 없는 요청(상세보기 원본)은 Coil 기본 경로로 원본을 디코딩한다 — 시스템 썸네일은 요청 크기와 무관하게 작아서 상세보기에 쓰면 화질이 깨진다(`VIEWER_STABILITY.md`).
- **그리드**: `LazyVerticalGrid(Adaptive 100dp)`, 날짜 헤더는 `GridItemSpan(maxLineSpan)`. key 는 항목 id.
- **선택**: 길게 눌러 시작 후 드래그하면 범위 선택(`Modifier.dragSelect` — `awaitLongPressOrCancellation` 뒤 이후 이벤트를 Initial 패스에서 소비해 썸네일 `clickable` 이 UP 을 탭으로 처리하지 않게 한다; 기본 `detectDragGesturesAfterLongPress` 는 움직임 없는 UP 을 소비하지 않아 롱프레스만 하면 선택이 풀렸다), 날짜 헤더 오른쪽 원형 버튼으로 그날 전체 선택/해제(`Set<Long>.toggleSection`). 둘 다 `onSelectionChange(Set<Long>)` 하나로 모여 ViewModel 의 `setSelection` 을 호출한다.

## 레이어 흐름 (UDF)

```
Compose Screen  ──events──▶  ViewModel  ──calls──▶  Repository  ──▶  DataSource
      ▲                          │                                (MediaStore / Room / Drive REST)
      └────── StateFlow<UiState> ┘
```

- **Route**: `hiltViewModel()` 로 ViewModel 을 얻어 상태를 수집하고 Screen 에 넘긴다. 내비게이션 콜백만 받는다.
- **Screen**: 상태와 콜백만 받는 순수 Composable. Preview 와 UI 테스트 대상.
- **ViewModel**: Repository Flow 를 `stateIn` 으로 `StateFlow<UiState>` 로 노출. `SharingStarted.WhileSubscribed(5s)`.
- **Repository**: 인터페이스는 `core/data`, 구현은 Hilt `@Binds` 로 바인딩. 테스트에서는 인터페이스를 mock.

## 모션

- 모든 애니메이션 스펙은 `core/ui/motion/MotionSpecs` 토큰(`quick 150ms / standard 250ms / settle 스프링 / progress 200ms`)과 Enter/Exit 프리셋(`enterFromBottom`, `enterExpand`, `enterScale` …)만 쓴다. 리터럴 `tween(300)` 금지(detekt `MagicNumber` 로도 막힘).
- `EasyGalleryTheme` 이 시스템 `ANIMATOR_DURATION_SCALE == 0` 이면 `reduceMotion = true` 인 스펙을 `LocalMotion` 으로 내려 모든 전환이 즉시(`snap`) 실행된다.
- 원칙: `graphicsLayer(alpha/scale/translation)` 로만 움직이고 `size/padding` 애니메이션은 피한다. 연속·무한 애니메이션(shimmer, 매 프레임 보간) 금지 — 배경과 근거는 `ANIMATION_IMPROVEMENT.md`.
- 적용 위치: 화면 전환(`NavDisplay` fade-through + 예측 뒤로가기), 선택 모드 상·하단 바, 썸네일 선택 축소(`graphicsLayer` scale)·체크 표시, 그리드 `animateItem`(필터 전환 직후 1회는 `animateItemChanges=false` 로 생략), 배너 expand/shrink, 상세보기 컨트롤 slide/scale, 업로드 진행 바 200ms 이즈.

## 내비게이션 (Navigation 3)

- 백스택: `rememberNavBackStack(GalleryKey)` — `NavKey` 를 구현한 `@Serializable` 키만 사용.
- 전환: `NavDisplay` 의 `transitionSpec`(새 화면 fade+scale 0.96→1) / `popTransitionSpec` / `predictivePopTransitionSpec` 을 전역으로 지정. 매니페스트 `enableOnBackInvokedCallback=true` 로 Android 14+ 예측 뒤로가기 활성.
- `NavDisplay` 에 `rememberSaveableStateHolderNavEntryDecorator()` + `rememberViewModelStoreNavEntryDecorator()` 를 걸어 엔트리별 ViewModel 스코프를 보장.
- 이동은 `backStack.add(Key)`, 뒤로는 `backStack.removeLastOrNull()`.

## 인증 / Drive / 업로드 흐름

```
SettingsViewModel ─▶ SignInUseCase ─▶ AuthRepository.beginSignIn()
                                         │ hasResolution → NeedsConsent(PendingIntent) → UI 가 StartIntentSenderForResult
                                         │ 완료 → 토큰 캐시(45분) → DriveRepository.getAccount() → prefs.setAccount
GalleryViewModel.uploadSelected ─▶ EnqueueUploadsUseCase ─▶ Room upload_tasks(PENDING) + UploadScheduler.schedule()
```

- **순환 의존 차단**: OkHttp `AuthInterceptor` 는 `TokenProvider` 만 알고, `GoogleAuthRepository` 가 이를 구현. Drive 계층은 Auth 를 모른다.
- **토큰**: `AuthorizationClient.authorize()` 는 동의가 있으면 UI 없이 새 토큰을 준다. 45분 캐시 + 401 시 `TokenAuthenticator` 가 1회 재발급·재시도.
- **예외**: `AuthException`(IOException) 계열 — `NotSignedIn`/`AuthorizationRequired`/`SignInCancelled`. 갤러리는 이를 받으면 "로그인 필요" 스낵바 → 설정으로 유도.
- **업로드는 큐에 넣기만**: UI 는 Room 에 행을 추가하고 워커를 예약한 뒤 즉시 반환. 진행 상황은 `UploadQueueRepository.observeSummary()` 로 관찰.
- **Drive 탐색**: `files.list` 를 `'<parent>' in parents and trashed = false`, `orderBy=folder,name_natural` 로 100개씩 페이징(리스트 끝 5개 전에 다음 페이지). 파일 탭은 `webViewLink` 를 ACTION_VIEW 로 열어 Drive 앱/브라우저에 위임. 기본 업로드 폴더는 여전히 앱 루트 "Easy Gallery"(appProperties `easyGalleryRoot=true`) 이며, 사용자가 Drive 탐색에서 임의 폴더를 업로드 폴더로 지정할 수 있다.

## 자동 백업

```
새 사진 저장 ─▶ WorkManager 콘텐츠 URI 트리거(Images/Video, 30s 모아서·최대 5분) ─▶ AutoBackupWorker
                6시간 주기 폴백 ──────────────────────────────────────────────────▶      │
AutoBackupUseCase.scanAndEnqueue():                                                       ▼
   prefs(켜짐·로그인·앨범) 확인 → MediaStore DATE_ADDED ≥ since AND RELATIVE_PATH IN (앨범) 조회
   → 원장(uploaded_media)·큐(upload_tasks, 상태 무관)에 있는 것 제외 → enqueue → UploadScheduler.schedule()
   → since = max(DATE_ADDED) 로 전진, 마지막 실행 시각 저장
워커는 끝나면 트리거를 다시 건다(1회성). 스위치 OFF → 두 워크 취소·since 초기화.
```

- **기준 시점**: 켠 시각(초). 켜기 전 사진은 대상이 아니며, 원하면 "기존 항목 모두 백업"(개수 확인 다이얼로그 → `backfill()`)으로 명시적으로 넣는다.
- **영구 원장** `uploaded_media`: `UploadWorker` 가 완료 시 기록. 큐는 새 배치마다 완료 행을 지우므로 "이미 올라감" 판단은 이 표로만 한다. 갤러리는 `observeUploadedIds()` 를 `Catalog` 에 결합해 썸네일 배지·백업 수·"백업 안 된 항목만" 필터를 만들고, 상세보기는 업로드 아이콘 상태에 쓴다. 원장은 업로드 완료 시에만 바뀌므로 선택 토글과 무관.
- `>=` 조회 + 원장·큐 중복 제거 조합이라 같은 초에 여러 장이 들어와도 놓치지 않고, 실패해 큐에 남은 항목을 매 스캔마다 다시 넣지도 않는다.
- 스캔은 로컬 쿼리라 네트워크 제약이 없다. 실제 업로드 제약(Wi-Fi·충전)은 `UploadScheduler` 가 그대로 적용.
- `ContentObserver` 는 프로세스가 살아 있을 때만 동작하므로 감지에는 쓰지 않는다.

## 완전 중복 탐지

- **유사 사진은 다루지 않는다.** 퍼셉추얼 해시는 연속 촬영·다른 순간의 사진을 묶어 다른 사진이 지워질 위험이 있다. 바이트 단위 SHA-256 이 같은 파일만 "중복".
- 비용 절감: `sizeBytes` 가 겹치는 파일만 해시 후보(대부분 크기가 유일). 결과는 `media_hash` 에 캐시하고 크기·`DATE_MODIFIED` 가 같으면 재계산하지 않는다. 사라진 항목의 해시는 스캔 때 정리.
- 스캔은 `DuplicateScanWorker`(유니크·KEEP, 배터리 부족 아님)가 돌려 화면을 떠나도 이어지며, 진행률은 `setProgress` + 20개 이상이면 포그라운드 알림. 화면은 `getWorkInfosForUniqueWorkFlow` 로 관찰.
- 그룹은 `findDuplicateGroups(items, hashById, uploadedIds)` 순수 함수: 유지 항목 = 백업된 것 → 가장 먼저 저장된 것 → 최소 id. 그룹 정렬은 낭비 용량 큰 순.
- 정리는 **휴지통(API 30+)** 으로만, 사용자가 선택을 확인하고 시스템 동의를 거친다. 자동 삭제·완전 삭제 없음.

## 갤러리 편집 (MediaStore CRUD)

```
ViewModel.perform(MediaAction) ─▶ MediaActionRunner.run()
   Delete/Trash/Favorite → repo.createXxxRequest → NeedsConsent(IntentSender)
   Rename/Move            → repo.requestWrite → NeedsConsent  (API 29: Done(0) 후 바로 update)
UI: StartIntentSenderForResult 실행 → RESULT_OK → ViewModel.onConsentResult(true)
   ─▶ MediaActionRunner.afterConsent(pendingAction)
        Rename/Move → writeGranted=true 로 재실행 → resolver.update(DISPLAY_NAME / RELATIVE_PATH)
        그 외 API 30+ → 시스템이 이미 처리 → Done(items.size)
        그 외 API 29  → 같은 요청 재시도 (RecoverableSecurityException 경로)
```

- `MediaActionRunner` 는 UI 프레임워크와 무관한 순수 로직이라 mock 저장소로 단위 테스트한다.
- `MediaActionController`(ViewModel 마다 새 인스턴스) 가 `pendingAction`·`isMutating`·이벤트를 들고 있고, UI 쪽은 `MediaActionEffect` 가 동의 실행과 결과 스낵바를 담당한다 → 갤러리·휴지통·상세보기가 같은 부품을 쓴다.
- 동의 다이얼로그가 떠 있는 동안 `isMutating` 으로 액션 버튼을 잠근다.
- 조회 필터: `MediaFilter.All / Favorites(IS_FAVORITE=1) / Trashed(QUERY_ARG_MATCH_TRASHED=MATCH_ONLY)`. API 29 는 All 만.
- **기간 필터**(`DateRange`, 양 끝 날짜 포함)는 MediaStore 를 다시 조회하지 않고 메모리 리스트에서 거른다(`filterByDate`) — 전체 로드 구조라 즉시 반영되고 즐겨찾기 필터와도 조합된다. 경계는 로컬 타임존 기준 `[시작일 00:00, 종료일+1 00:00)`. Material `DateRangePicker` 는 UTC 자정 밀리초를 주므로 `DateRangeDialog` 에서 로컬 날짜로 변환한다. 상세보기로 넘어갈 때 `MediaViewerKey` 에 `startEpochDay/endEpochDay` 로 실어 스와이프 범위를 갤러리와 일치시킨다.
- 앨범 이동 대상은 현재 목록의 `RELATIVE_PATH` 집합(`albumsFrom`) + 새 앨범(`Pictures/<이름>/`).
- 이름 변경 시 확장자를 생략하면 원본 확장자를 유지(`normalizeDisplayName`).
- `MANAGE_MEDIA`(API 31+) 를 사용자가 시스템 설정에서 허용하면 createXxxRequest 가 다이얼로그 없이 즉시 OK 로 돌아온다 — 코드 경로는 동일.

## 상세보기 (feature/viewer)

- 그리드에서 항목을 탭하면 `MediaViewerKey(mediaId, favoritesOnly)` 로 진입. 목록을 통째로 넘기지 않고 **갤러리와 같은 필터로 다시 관찰**해 좌우 스와이프 범위를 맞춘다(`mediaId` 로 인덱스를 찾음).
- `HorizontalPager` + 공용 `ZoomState`(`Animatable`): 핀치 중엔 즉시 반영, 손을 떼면 경계 보정 스프링, 두 번 탭은 탭 지점 중심 확대(`focalZoomOffset`/`clampOffset` 순수 함수). 확대 중에는 `userScrollEnabled = false` 로 페이저와 팬 제스처가 충돌하지 않게 한다(확대 중엔 다른 페이지가 보이지 않으므로 상태 하나를 공유해도 정확). 원본 디코딩은 `size(4096)` 상한.
- 아래로 끌어 닫기 `Modifier.swipeToDismiss`(확대 중 비활성). 진입/복귀 전환은 `AppNavigation` 의 전역 `transitionSpec` 이 목적지 키를 보고 분기(상세보기 fade + scale 0.92, 히어로가 있으면 페이드만). 그리드 썸네일과 `thumb-<id>` 메모리 캐시 키를 공유해 원본 로드 전까지 썸네일을 보여준다.
- **히어로 오버레이**(`HeroOverlay`): 그리드가 `onPlaced` 로 잡은 썸네일 윈도우 좌표·원본 정보를 `MediaViewerKey.hero` 로 넘기면, 라우트가 페이저 위에 캐시 썸네일을 시작 사각형→`fittedRect`(원본 비율) 까지 250ms 확대해 그린다. 첫 진입 1회, 역방향은 없음. `SharedTransitionLayout` 의 상시 이중 측정 비용을 피하기 위한 선택 — 근거는 `ANIMATION_IMPROVEMENT.md` §3.3.
- 영상은 항목별 `ExoPlayer` + media3 `ContentFrame(surfaceType = TEXTURE_VIEW, contentScale = Fit, shutter = 그리드 썸네일)`. `PlayerSurface` 를 직접 `fillMaxSize` 로 쓰면 영상이 화면 비율로 늘어나고, SurfaceView 는 페이저 이동·스와이프 닫기의 `graphicsLayer` 변형을 따르지 않는다. 컨트롤은 Scaffold `innerPadding` 의 bottom 만큼 띄워 하단 바 위에 놓고, 하단 바는 `safeDrawing` 인셋을 소비한다(edge-to-edge). 페이지를 벗어나면 `pause()`, 화면을 나가면 `release()`, 앱이 백그라운드로 가면 `LifecycleResumeEffect` 로 `pause()`(소리가 계속 나는 것 방지). 컨트롤(재생 버튼·탐색 바)은 상·하단 바와 같은 `chromeVisible` 로 묶여 재생 중 3초 뒤 자동으로 숨고, 화면을 탭하면 다시 나온다. 탐색 바는 드래그 중 `scrubFraction` 을 우선 표시하고 손을 떼면 `seekTo`. 컨트롤 행에 음량 슬라이더(+음소거 토글, `player.volume`), 배속 메뉴(0.25~2배, `setPlaybackSpeed`), 가로 보기 토글이 있고 세 값은 화면 수준 `rememberSaveable` 이라 항목을 넘겨도 유지된다. 가로 보기는 `OrientationLockEffect` 가 `activity.requestedOrientation` 을 바꾸고, 해제·종료 시 잠그기 전 사용자 방향을 잠깐 명시한 뒤 1초 후 `UNSPECIFIED` 로 되돌린다(곧바로 `UNSPECIFIED` 를 주면 자동 회전이 꺼진 기기에서 시스템이 잠금 방향을 가로로 갈아 끼움) — 회전으로 재생 위치가 날아가지 않도록 `MainActivity` 에 `configChanges` 를 선언해 액티비티 재생성을 막았다. media3 의 `UnstableApi` 는 Java 마커라 `@androidx.annotation.OptIn` 이 필요하다(kotlin.OptIn 은 lint 가 인정하지 않음).
- 정보 패널은 `MediaRepository.readDetails()` 로 EXIF(카메라·조리개·ISO·초점거리·좌표)를 읽는다. 위치가 지워지지 않은 원본은 `MediaStore.setRequireOriginal` + `ACCESS_MEDIA_LOCATION` 권한이 필요하며, 실패하면 빈 값으로 대체한다. 영상은 EXIF 를 읽지 않는다.
- 현재 항목을 삭제하면 같은 인덱스(다음 항목)를 이어서 보여주고, 목록이 비면 화면을 닫는다.

## 백그라운드 업로드 (WorkManager)

```
UploadWorker (유니크 워크 "upload-queue", KEEP)
  loop: nextUnfinished()  ── PENDING/RUNNING 중 가장 오래된 것
    ├─ folderId 없으면 GetUploadFolderUseCase 로 해석·저장
    ├─ sessionUri 있으면 queryStatus(Content-Range: bytes */total)
    │     308 + Range → 그 다음 바이트부터 이어 올림 / 200 → 완료 처리 / 404·410 → 새 세션
    ├─ upload(offset..end) 스트리밍, 1초마다 DB bytesUploaded + 포그라운드 알림 갱신
    └─ 결과 분기
         AuthException            → 항목 PENDING 유지, 알림 "다시 연결", Result.failure()
         FileNotFound / 4xx       → FAILED (영구)
         IOException / 5xx / 세션 만료 → attemptCount+1, PENDING, Result.retry() (지수 백오프 30s~, 5회 후 FAILED)
  큐가 비면 요약 알림 → Result.success()
```

- **제약 조건**: `UserPreferences.uploadWifiOnly`(기본 true → UNMETERED) / `uploadChargingOnly`, 배터리 부족 아님. 설정이 바뀌면 `schedule(replace = true)` — 진행 중 항목은 세션 상태 조회로 이어 올리므로 손실 없음.
- **프로세스 종료 복구**: RUNNING 도 `nextUnfinished()` 대상. `MainActivity` 시작 시 `ensureScheduled()` 로 남은 큐가 있으면 워커 재예약.
- **알림**: 채널 `upload`, 진행(1001, ongoing, FGS dataSync) / 요약·로그인 필요(1002). 13+ 는 업로드 버튼을 누를 때 `POST_NOTIFICATIONS` 를 묻고 결과와 무관하게 큐에 넣는다.
- **영상 압축(선택)**: `VideoCompressor.compress()` 가 프리셋(짧은 변 1080/720, H.264)과 원본 해상도(큐 행의 width/height)를 보고 필요할 때만 `cache/transcode/<id>_<shortSide>.mp4` 를 만든다. 같은 이름의 캐시가 있으면 재사용해 재시도·세션 재개에서도 바이트가 같다. Transformer 는 Looper 가 필요해 Main 에서 생성(인코딩은 내부 스레드), 진행률은 `getProgress` 폴링 → "영상 압축 중" 알림. 완료·영구 실패 시 캐시 삭제, 인코더 실패는 `CompressionException` 으로 영구 실패. 기기 원본은 건드리지 않는다.
- **중복 방지**: 같은 mediaId 가 PENDING/RUNNING 이면 건너뜀. 새 배치를 넣을 때 지난 COMPLETED 행은 정리해 진행률 분모를 현재 배치로 맞춘다.

## 테스트

| 종류 | 위치 | 도구 |
|---|---|---|
| 단위 | `app/src/test` | JUnit4, MockK, Turbine, coroutines-test, MockWebServer3(Drive REST·resumable), Robolectric(sdk=35 — 36+ 이미지는 Java 21 필요) — Room 인메모리 DAO, `TestListenableWorkerBuilder` 로 워커 상태 전이 |
| 계측 | `app/src/androidTest` | Compose UI Test, Hilt testing (`HiltTestRunner`) |
