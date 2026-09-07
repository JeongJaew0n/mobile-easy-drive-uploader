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
│   ├── data/media/            # MediaRepository(조회+편집) / MediaStoreRepository / MediaActions(MediaAction, MediaActionRunner)
│   ├── data/prefs/            # UserPreferencesRepository (DataStore: 계정, 업로드 폴더)
│   ├── data/upload/           # DriveUploader(세션 시작/상태 조회/이어 올리기), ContentUriRequestBody, UploadQueueRepository
│   │   ├── db/                # Room: AppDatabase, UploadTaskEntity, UploadTaskDao (schemas/ 에 내보냄)
│   │   └── work/              # UploadWorker(@HiltWorker), UploadScheduler, UploadNotifications
│   ├── domain/model/          # MediaItem, DriveFolder, DriveAccount
│   ├── domain/usecase/        # SignInUseCase, GetUploadFolderUseCase, EnqueueUploadsUseCase, ManageUploadQueueUseCase
│   ├── navigation/            # AppNavKey(@Serializable NavKey), AppNavigation(NavDisplay)
│   └── ui/
│       ├── image/             # Coil Fetcher (MediaStore 썸네일)
│       └── theme/             # Material 3 테마
└── feature/
    ├── gallery/               # GalleryRoute/Screen/Grid, GalleryActions(하단 바·다이얼로그·메뉴), GalleryViewModel, MediaPermission
    ├── trash/                 # 휴지통: 복원·완전 삭제·비우기 (GalleryGrid 재사용)
    ├── settings/              # 계정 연결/해제, 저장공간, 업로드 폴더·목록 진입, Wi-Fi/충전 제약 토글
    ├── drive/                 # Google Drive 탐색: 폴더·파일 목록(페이징), 새 폴더, 파일 열기, 업로드 폴더 지정 (DriveBrowserKey 중첩 push)
    └── uploads/               # 업로드 목록: 상태·진행률, 실패 재시도, 완료 정리, 전체 취소
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
GalleryViewModel.uploadSelected ─▶ EnqueueUploadsUseCase ─▶ Room upload_tasks(PENDING) + UploadScheduler.schedule()
```

- **순환 의존 차단**: OkHttp `AuthInterceptor` 는 `TokenProvider` 만 알고, `GoogleAuthRepository` 가 이를 구현. Drive 계층은 Auth 를 모른다.
- **토큰**: `AuthorizationClient.authorize()` 는 동의가 있으면 UI 없이 새 토큰을 준다. 45분 캐시 + 401 시 `TokenAuthenticator` 가 1회 재발급·재시도.
- **예외**: `AuthException`(IOException) 계열 — `NotSignedIn`/`AuthorizationRequired`/`SignInCancelled`. 갤러리는 이를 받으면 "로그인 필요" 스낵바 → 설정으로 유도.
- **업로드는 큐에 넣기만**: UI 는 Room 에 행을 추가하고 워커를 예약한 뒤 즉시 반환. 진행 상황은 `UploadQueueRepository.observeSummary()` 로 관찰.
- **Drive 탐색**: `files.list` 를 `'<parent>' in parents and trashed = false`, `orderBy=folder,name_natural` 로 100개씩 페이징(리스트 끝 5개 전에 다음 페이지). 파일 탭은 `webViewLink` 를 ACTION_VIEW 로 열어 Drive 앱/브라우저에 위임. 기본 업로드 폴더는 여전히 앱 루트 "Easy Gallery"(appProperties `easyGalleryRoot=true`) 이며, 사용자가 Drive 탐색에서 임의 폴더를 업로드 폴더로 지정할 수 있다.

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

- `MediaActionRunner` 는 UI 프레임워크와 무관한 순수 로직이라 mock 저장소로 단위 테스트한다. 갤러리·휴지통 ViewModel 이 같은 러너를 공유.
- 동의 다이얼로그가 떠 있는 동안 `isMutating` 으로 액션 버튼을 잠근다.
- 조회 필터: `MediaFilter.All / Favorites(IS_FAVORITE=1) / Trashed(QUERY_ARG_MATCH_TRASHED=MATCH_ONLY)`. API 29 는 All 만.
- 앨범 이동 대상은 현재 목록의 `RELATIVE_PATH` 집합(`albumsFrom`) + 새 앨범(`Pictures/<이름>/`).
- 이름 변경 시 확장자를 생략하면 원본 확장자를 유지(`normalizeDisplayName`).
- `MANAGE_MEDIA`(API 31+) 를 사용자가 시스템 설정에서 허용하면 createXxxRequest 가 다이얼로그 없이 즉시 OK 로 돌아온다 — 코드 경로는 동일.

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
- **중복 방지**: 같은 mediaId 가 PENDING/RUNNING 이면 건너뜀. 새 배치를 넣을 때 지난 COMPLETED 행은 정리해 진행률 분모를 현재 배치로 맞춘다.

## 테스트

| 종류 | 위치 | 도구 |
|---|---|---|
| 단위 | `app/src/test` | JUnit4, MockK, Turbine, coroutines-test, MockWebServer3(Drive REST·resumable), Robolectric(sdk=35 — 36+ 이미지는 Java 21 필요) — Room 인메모리 DAO, `TestListenableWorkerBuilder` 로 워커 상태 전이 |
| 계측 | `app/src/androidTest` | Compose UI Test, Hilt testing (`HiltTestRunner`) |
