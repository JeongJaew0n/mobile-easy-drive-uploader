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
```

코드 변경 후에는 최소 `detekt` + `testDebugUnitTest` 를 통과시킨다.

## 수동 테스트 대기 목록

기기·계정·네트워크가 필요해 확인하지 못한 동작은 **같은 커밋에서** `docs/manual-tests/<영역>.md` 에 항목(ID·시나리오·기대 결과·`⬜`)을 추가한다.
테스트하면 그 줄의 상태만 `✅ 날짜` / `❌ 날짜 + 메모` 로 고친다. 별도 보고서를 만들지 않는다. 규칙은 `docs/manual-tests/README.md`.

## 규칙

- 패키지: `com.jjw.easygallery`. feature-first (`feature/<name>`), 공용은 `core/*`.
- UI 는 Compose 만. 화면은 `XxxRoute`(ViewModel 연결) + `XxxScreen`(순수 UI, Preview·테스트 대상) 으로 나눈다.
- ViewModel 은 `@HiltViewModel`, 상태는 `StateFlow<UiState>` 단일 노출. UiState 는 sealed interface.
- Compose 에서 ViewModel 은 `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()` 사용 (`hilt-navigation-compose` 의 것은 deprecated, Nav2 전용).
- 내비게이션은 Navigation 3. 화면 키는 `core/navigation/NavKeys.kt` 의 `AppNavKey` 에 `@Serializable` 로 추가.
- 의존성 버전은 `gradle/libs.versions.toml` 에서만 관리. 새 라이브러리는 카탈로그에 먼저 등록.
- Kotlin 은 AGP 내장(built-in Kotlin). `org.jetbrains.kotlin.android` 플러그인을 추가하지 않는다. kapt 금지, KSP 사용.
- 백그라운드 작업은 WorkManager + `@HiltWorker`. Application 이 `HiltWorkerFactory` 를 제공하므로 매니페스트의 기본 초기화 제거를 유지한다. 업로드는 UI 에서 직접 하지 않고 반드시 `EnqueueUploadsUseCase` 로 큐(Room)에 넣는다.
- MediaStore 편집은 `MediaRepository` 를 직접 부르지 말고 ViewModel 은 `MediaActionController`, 화면은 `MediaActionEffect` 를 써서 동의 흐름을 태운다. API 30+ 전용 호출은 `if (Build.VERSION.SDK_INT >= R)` 로 감싼다 (`check()`/헬퍼 함수는 lint NewApi 가 인식하지 못함).
- `MainActivity` 는 `configChanges` 로 회전 시 재생성되지 않는다(영상 재생 위치 유지). 화면 회전에 따라 리소스를 갈아끼우는 코드를 쓰지 말 것.
- Room 스키마 변경 시 `AppDatabase.version` 을 올리고 Migration 을 추가한다. `app/schemas/` 는 커밋 대상.
- Drive API 는 Retrofit 으로 REST v3 직접 호출. 공식 Java 클라이언트(`google-api-services-drive`) 추가 금지.
- 문자열은 `res/values/strings.xml` (한국어 기본). 하드코딩 금지. Composable 밖(LaunchedEffect 등)에서 문자열이 필요하면 `LocalResources.current` 를 캡처해 쓴다 — `LocalContext.current.getString` 은 lint 에러.
- Google 인증은 `AuthorizationClient` 만 사용(Credential Manager/GoogleSignIn 금지). Drive 는 `drive.file` scope 유지.
- Robolectric 테스트는 `robolectric.properties` 의 sdk=35 유지 (36+ 는 Java 21 필요, 테스트 JVM 은 17).
- 벡터 아이콘은 `res/drawable/ic_*.xml` 에 직접 추가 (`material-icons-extended` 미사용, `?attr/colorControlNormal` 같은 AppCompat 속성 금지).
- 줄 길이 120, 트레일링 콤마 사용 (detekt formatting 이 검사).
- 애니메이션은 `LocalMotion.current`(`core/ui/motion/MotionSpecs`) 의 스펙·프리셋만 사용. `tween(숫자)` 리터럴, `size/padding` 애니메이션, 무한·매 프레임 애니메이션 금지. 새 `if` 로 컴포저블을 넣고 빼는 자리는 `AnimatedVisibility`/`AnimatedContent` 를 기본으로 한다.
