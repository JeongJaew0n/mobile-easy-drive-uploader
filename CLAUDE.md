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

## 규칙

- 패키지: `com.jjw.easygallery`. feature-first (`feature/<name>`), 공용은 `core/*`.
- UI 는 Compose 만. 화면은 `XxxRoute`(ViewModel 연결) + `XxxScreen`(순수 UI, Preview·테스트 대상) 으로 나눈다.
- ViewModel 은 `@HiltViewModel`, 상태는 `StateFlow<UiState>` 단일 노출. UiState 는 sealed interface.
- Compose 에서 ViewModel 은 `androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel()` 사용 (`hilt-navigation-compose` 의 것은 deprecated, Nav2 전용).
- 내비게이션은 Navigation 3. 화면 키는 `core/navigation/NavKeys.kt` 의 `AppNavKey` 에 `@Serializable` 로 추가.
- 의존성 버전은 `gradle/libs.versions.toml` 에서만 관리. 새 라이브러리는 카탈로그에 먼저 등록.
- Kotlin 은 AGP 내장(built-in Kotlin). `org.jetbrains.kotlin.android` 플러그인을 추가하지 않는다. kapt 금지, KSP 사용.
- 백그라운드 작업은 WorkManager + `@HiltWorker`. Application 이 `HiltWorkerFactory` 를 제공하므로 매니페스트의 기본 초기화 제거를 유지한다.
- Drive API 는 Retrofit 으로 REST v3 직접 호출. 공식 Java 클라이언트(`google-api-services-drive`) 추가 금지.
- 문자열은 `res/values/strings.xml` (한국어 기본). 하드코딩 금지.
- 줄 길이 120, 트레일링 콤마 사용 (detekt formatting 이 검사).
