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
│   ├── data/media/            # MediaRepository (interface) / MediaStoreRepository / MediaModule
│   ├── data/prefs/            # UserPreferencesRepository (DataStore: 계정, 업로드 폴더)
│   ├── data/upload/           # DriveUploader (resumable), ContentUriRequestBody
│   ├── domain/model/          # MediaItem, DriveFolder, DriveAccount
│   ├── domain/usecase/        # SignInUseCase, GetUploadFolderUseCase, UploadMediaUseCase
│   ├── navigation/            # AppNavKey(@Serializable NavKey), AppNavigation(NavDisplay)
│   └── ui/
│       ├── image/             # Coil Fetcher (MediaStore 썸네일)
│       └── theme/             # Material 3 테마
└── feature/
    ├── gallery/               # GalleryRoute/Screen/Grid(선택·업로드 진행), GalleryViewModel, MediaPermission, GallerySection
    ├── settings/              # 계정 연결/해제, 저장공간, 업로드 폴더 진입
    └── folderpicker/          # Drive 폴더 탐색·생성·선택 (FolderPickerKey 를 중첩 push)
```

## 갤러리 데이터 흐름

```
ContentObserver(MediaStore.Files) ─debounce 300ms─┐
flowOf(Unit) (최초 1회) ───────────────────────────┴─▶ mapLatest { queryAll() } ─▶ List<MediaItem>
                                                                                      │
GalleryViewModel: permissionStatus.flatMapLatest ─▶ groupByDate() ─▶ GalleryUiState.Content(sections)
```

- **MediaStoreRepository**: `MediaStore.Files` 컬렉션을 `MEDIA_TYPE IN (IMAGE, VIDEO)` 로 한 번에 조회. `DATE_TAKEN` 이 0인 행은 `DATE_ADDED*1000` 으로 대체 후 메모리 정렬. 쿼리는 `ensureActive()` 로 취소 가능.
- **전체 로드 (Paging 미사용)**: 수천~수만 장은 항목당 수백 바이트라 메모리 리스트로 충분하고(6천 장 ≈ 수 MB), 날짜 헤더·다중 선택·"이 날 전체 선택" 같은 기능이 훨씬 단순해진다. 수십만 장 규모 이슈가 실측되면 Paging 3 도입을 재검토한다.
- **권한**: `MediaPermission` 이 SDK 별 권한 집합과 상태(Full/Partial/Denied)를 계산. `GalleryRoute` 가 `LifecycleResumeEffect` 마다 상태를 ViewModel 에 알려 설정 앱에서 돌아온 경우도 반영. ViewModel 은 상태를 모르는 동안(`null`) 쿼리하지 않는다.
- **썸네일**: `MediaStoreThumbnailFetcher` 가 `content://media/...` URI 를 가로채 `ContentResolver.loadThumbnail` (시스템 썸네일 캐시) 사용. 실패 시 원본 스트림으로 폴백해 Coil 기본 디코더/`VideoFrameDecoder` 가 처리.
- **그리드**: `LazyVerticalGrid(Adaptive 100dp)`, 날짜 헤더는 `GridItemSpan(maxLineSpan)`. key 는 URI 문자열.

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

## 내비게이션 (Navigation 3)

- 백스택: `rememberNavBackStack(GalleryKey)` — `NavKey` 를 구현한 `@Serializable` 키만 사용.
- `NavDisplay` 에 `rememberSaveableStateHolderNavEntryDecorator()` + `rememberViewModelStoreNavEntryDecorator()` 를 걸어 엔트리별 ViewModel 스코프를 보장.
- 이동은 `backStack.add(Key)`, 뒤로는 `backStack.removeLastOrNull()`.

## 인증 / Drive / 업로드 흐름

```
SettingsViewModel ─▶ SignInUseCase ─▶ AuthRepository.beginSignIn()
                                         │ hasResolution → NeedsConsent(PendingIntent) → UI 가 StartIntentSenderForResult
                                         │ 완료 → 토큰 캐시(45분) → DriveRepository.getAccount() → prefs.setAccount
GalleryViewModel.uploadSelected ─▶ UploadMediaUseCase ─▶ GetUploadFolderUseCase(prefs → 없으면 ensureAppRootFolder)
                                                        └▶ DriveUploader: POST uploadType=resumable → Location
                                                                          PUT 세션 URI (ContentUriRequestBody 스트리밍, 진행률)
```

- **순환 의존 차단**: OkHttp `AuthInterceptor` 는 `TokenProvider` 만 알고, `GoogleAuthRepository` 가 이를 구현. Drive 계층은 Auth 를 모른다.
- **토큰**: `AuthorizationClient.authorize()` 는 동의가 있으면 UI 없이 새 토큰을 준다. 45분 캐시 + 401 시 `TokenAuthenticator` 가 1회 재발급·재시도.
- **예외**: `AuthException`(IOException) 계열 — `NotSignedIn`/`AuthorizationRequired`/`SignInCancelled`. 갤러리는 이를 받으면 "로그인 필요" 스낵바 → 설정으로 유도.
- **업로드 (현 단계)**: ViewModel 스코프에서 순차 실행(포그라운드). 세션 URI 저장·재개·백그라운드는 다음 단계(WorkManager) 에서.
- **폴더 선택**: `drive.file` scope 는 앱이 만든 파일만 보이므로 앱 루트 "Easy Gallery"(appProperties `easyGalleryRoot=true` 로 식별) 아래를 탐색·생성한다.

## 백그라운드 (예정)

- `feature/upload` 에 `@HiltWorker class UploadWorker : CoroutineWorker`.
- 장시간 업로드는 `setForeground()` (foregroundServiceType `dataSync`).
- 업로드 큐·세션 URL 은 Room (`core/data/upload`).

## 테스트

| 종류 | 위치 | 도구 |
|---|---|---|
| 단위 | `app/src/test` | JUnit4, MockK, Turbine, coroutines-test, MockWebServer3(Drive REST), Robolectric(sdk=35 — 36+ 이미지는 Java 21 필요) |
| 계측 | `app/src/androidTest` | Compose UI Test, Hilt testing (`HiltTestRunner`) |
