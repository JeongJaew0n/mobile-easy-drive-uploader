# Easy Gallery

Android 전용 앱. 갤러리(사진·영상)를 Google Drive로 옮기고 갤러리를 CRUD 한다.
기술 스택과 결정 근거: `docs/TECH_STACK.md`. 코드 구조: `docs/ARCHITECTURE.md`.

## 빌드 / 검증

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools   # local.properties 의 sdk.dir 과 동일
./gradlew :app:assembleDebug          # 디버그 APK
./gradlew :app:testDebugUnitTest      # 단위 테스트
./gradlew detekt                      # 정적 분석 (maxIssues: 0, 위반 시 실패)
./gradlew :app:lintDebug              # Android Lint
./gradlew :app:connectedDebugAndroidTest   # 계측 테스트 (기기/에뮬레이터 필요)
./gradlew :app:assembleRelease        # R8 릴리스 (keystore.properties 없으면 디버그 키 폴백) — 규칙은 docs/RELEASE.md
./gradlew :app:generateBaselineProfile                       # Baseline Profile 수집 (GMD 또는 연결 기기 API 33+)
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest   # Macrobenchmark (실기기 필요)
```

코드 변경 후에는 최소 `detekt` + `testDebugUnitTest` 를 통과시킨다.

## 수동 테스트 대기 목록

기기·계정·네트워크가 필요해 확인하지 못한 동작은 **같은 커밋에서** `docs/manual-tests/<영역>.md` 에 항목(ID·시나리오·기대 결과·`⬜`)을 추가한다.
테스트하면 그 줄의 상태만 `✅ 날짜` / `❌ 날짜 + 메모` 로 고친다. 별도 보고서를 만들지 않는다. 규칙은 `docs/manual-tests/README.md`.

## 규칙

- 패키지: `com.jjw.easygallery`. feature-first (`feature/<name>`), 공용은 `core/*`.
- UI 는 Compose 만. 화면은 `XxxRoute`(ViewModel 연결) + `XxxScreen`(순수 UI, Preview·테스트 대상) 으로 나누고, 한 파일이 ~350줄을 넘으면 상단바·배너·페이지 같은 조각 단위로 파일을 나눈다(갤러리·상세보기가 예시). 파일 간 공유는 `internal`.
- ViewModel 은 `@HiltViewModel`, 상태는 `StateFlow<UiState>` 단일 노출. UiState 는 sealed interface.
- Compose 에서 ViewModel 은 `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()` 사용 (`hilt-navigation-compose` 의 것은 deprecated, Nav2 전용).
- 내비게이션은 Navigation 3. 화면 키는 `core/navigation/NavKeys.kt` 의 `AppNavKey` 에 `@Serializable` 로 추가.
- 의존성 버전은 `gradle/libs.versions.toml` 에서만 관리. 새 라이브러리는 카탈로그에 먼저 등록.
- Kotlin 은 AGP 내장(built-in Kotlin). `org.jetbrains.kotlin.android` 플러그인을 추가하지 않는다. kapt 금지, KSP 사용.
- 백그라운드 작업은 WorkManager + `@HiltWorker`. Application 이 `HiltWorkerFactory` 를 제공하므로 매니페스트의 기본 초기화 제거를 유지한다. 업로드는 UI 에서 직접 하지 않고 반드시 `EnqueueUploadsUseCase` 로 큐(Room)에 넣는다.
- MediaStore 편집은 `MediaRepository` 를 직접 부르지 말고 ViewModel 은 `MediaActionController`, 화면은 `MediaActionEffect` 를 써서 동의 흐름을 태운다. API 30+ 전용 호출은 `if (Build.VERSION.SDK_INT >= R)` 로 감싼다 (`check()`/헬퍼 함수는 lint NewApi 가 인식하지 못함).
- `MainActivity` 는 `configChanges` 로 회전 시 재생성되지 않는다(영상 재생 위치 유지). 화면 회전에 따라 리소스를 갈아끼우는 코드를 쓰지 말 것.
- 직렬화·리플렉션에 의존하는 라이브러리를 추가하면 `app/proguard-rules.pro` 를 갱신하고 `assembleRelease` 후 `missing_rules.txt` 부재와 `mapping.txt` 의 `$$serializer` 유지를 확인한다.
- Room 스키마 변경 시 `AppDatabase.version` 을 올리고 Migration 을 추가한다(표 추가처럼 단순하면 `AutoMigration`). `app/schemas/` 는 커밋 대상.
- 업로드 완료는 `UploadLedgerRepository`(uploaded_media) 에 남는다. "이미 업로드됨" 판단은 큐가 아니라 원장으로.
- Drive API 는 Retrofit 으로 REST v3 직접 호출. 공식 Java 클라이언트(`google-api-services-drive`) 추가 금지.
- 문자열은 `res/values/strings.xml` (한국어 기본). 하드코딩 금지. Composable 밖(LaunchedEffect 등)에서 문자열이 필요하면 `LocalResources.current` 를 캡처해 쓴다 — `LocalContext.current.getString` 은 lint 에러.
- Google 인증은 `AuthorizationClient` 만 사용(Credential Manager/GoogleSignIn 금지). Drive 는 `drive.file` scope 유지.
- Robolectric 테스트는 `robolectric.properties` 의 sdk=35 유지 (36+ 는 Java 21 필요, 테스트 JVM 은 17).
- 벡터 아이콘은 `res/drawable/ic_*.xml` 에 직접 추가 (`material-icons-extended` 미사용, `?attr/colorControlNormal` 같은 AppCompat 속성 금지).
- 줄 길이 120, 트레일링 콤마 사용 (detekt formatting 이 검사).
- 애니메이션은 `LocalMotion.current`(`core/ui/motion/MotionSpecs`) 의 스펙·프리셋만 사용. `tween(숫자)` 리터럴, `size/padding` 애니메이션, 무한·매 프레임 애니메이션 금지. 새 `if` 로 컴포저블을 넣고 빼는 자리는 `AnimatedVisibility`/`AnimatedContent` 를 기본으로 한다.

## 프로젝트 규칙 (my-app-init, 2026-09-13 확정)

### git
- author: `JeongJaew0n <45487307+JeongJaew0n@users.noreply.github.com>` — `git config --local` 로 설정돼 있다. 커밋 전 `git config user.email` 로 확인한다.
- 커밋·푸시: **전자동**. 작업 단위가 끝나고 검증(detekt·testDebugUnitTest)이 통과하면 커밋하고 `origin/main` 에 푸시한다. 사용자가 2026-09-13 에 준 지속적 승인이며, 근거 없이 자동 푸시하는 것과 구분된다.
- 브랜치: **main 고정**. 브랜치를 만들지 않고 main 에 바로 쌓는다.
- **예외 — 리뷰는 자동 커밋·푸시하지 않는다.** 코드리뷰·리뷰 문서, 그리고 리뷰에서 나온 수정은 위 정책이 '전자동'이어도 사람이 읽고 판단한 뒤에 커밋한다. 리뷰는 사실이 아니라 의견이고, 틀린 의견이 먼저 기록에 박히면 되돌리기 어렵다.

### docs
- 여러 단계짜리 작업은 코드를 건드리기 전에 `docs/plans/<slug>/` 에 계획을 먼저 쓴다. 기능 단위 설계 문서(`docs/<FEATURE>.md`)는 지금처럼 계속 쓴다 — plans 는 "이번 작업을 어떻게 진행할지", 설계 문서는 "이 기능이 어떻게 동작하는지"다.
- 원인 찾는 데 시간이 걸린 오류는 `docs/troubleshootings/` 에 남긴다. 원인이 라이브러리·런타임·OS 에 있으면 `reusable/`, 이 프로젝트의 코드·설정에 있으면 `project-specific/`.
- 도메인 용어를 새로 만들거나 이름을 바꾸면 `docs/glossary/README.md` 를 먼저 고치고 코드를 그 이름에 맞춘다. 코드만 바꾸면 용어집이 거짓말이 된다.
- 기기·계정·네트워크가 필요해 확인하지 못한 동작은 `docs/manual-tests/` 에 남긴다(위 "수동 테스트 대기 목록" 규칙).

### 설계
- 기능 묶음 단위는 **feature** 다. 새 기능은 기존 feature 에 넣을지 새 feature 를 만들지 먼저 정하고 시작한다. 코드 디렉터리(`feature/<name>`)와 문서 이름도 이 말을 쓴다. 여러 feature 가 함께 쓰는 것은 `core/*` 로 내린다.
