# 기술 스택

> 2026-09-07 확정. 갤러리(사진·영상)를 Google Drive로 쉽게 옮기고, 갤러리를 자유롭게 CRUD 하는 **Android 전용** 앱.

## 결정 배경

처음엔 Android + iOS 동시 지원을 위해 Flutter를 검토했으나, **Android 네이티브(Kotlin)** 로 전환.

| 이유 | 설명 |
|---|---|
| 갤러리 기능 다양성이 앱의 핵심 | 휴지통·즐겨찾기·파일명 변경·폴더 이동·EXIF 편집·중복 탐지·영상 압축 등은 MediaStore/Media3를 직접 써야 함. Flutter 플러그인 범위를 넘어서 결국 Kotlin 코드가 필요 |
| iOS는 컨셉 자체가 제한됨 | PhotoKit은 파일명 변경 불가, 폴더 없음, 삭제마다 시스템 다이얼로그 강제, 백그라운드 제한 엄격. iOS 버전은 기능이 반쯤 빠진 앱이 됨 |
| 백그라운드 업로드 완전 제어 | WorkManager + Foreground Service를 제약 없이 설계 가능 |
| 로컬 환경 | JDK 21 + Android SDK가 이미 설치되어 있어 바로 시작 가능 |

iOS는 수요가 확인되면 "업로드 전용 축소판"으로 별도 판단.

## 플랫폼 / 언어 / 빌드

| 항목 | 선택 | 비고 |
|---|---|---|
| 언어 | **Kotlin 2.x** | K2 컴파일러 |
| UI | **Jetpack Compose** (Compose BOM) + Material 3 | XML 레이아웃 미사용 |
| minSdk / compileSdk / targetSdk | **29 (Android 10)** / **37** / 36 | compileSdk 37은 최신 AndroidX(Compose 1.12, core 1.19, OkHttp 5.5)가 요구. targetSdk는 새 런타임 동작 검토 후 올림. minSdk 29는 Scoped Storage 이후로 MediaStore 동작이 일관되기 때문이며, `createDeleteRequest` 등은 30+ 전용이라 29는 레거시 분기 필요 → 사용자 분포 보고 30으로 올릴 수 있음 |
| 빌드 | Gradle Kotlin DSL + **Version Catalog**(`libs.versions.toml`), AGP 8.x, KSP | |
| 모듈 | 단일 `:app` 모듈로 시작, 필요 시 `:core:*` / `:feature:*` 분리 | 초기에 과도한 모듈화 지양 |

## 아키텍처

- **MVVM + 단방향 데이터 흐름(UDF)**: Compose UI → ViewModel(`StateFlow<UiState>`) → Repository → DataSource(MediaStore / Drive / Room)
- 패키지는 **feature-first**: `feature/gallery`, `feature/upload`, `feature/auth`, `feature/settings` + `core/{data,domain,ui,common}`
- 도메인 모델은 순수 Kotlin(Android 의존 없음)으로 유지해 단위 테스트 용이하게

| 항목 | 선택 |
|---|---|
| DI | **Hilt** (+ `hilt-work`로 WorkManager 연동) |
| 비동기 | Kotlin Coroutines + Flow |
| 내비게이션 | **Navigation 3** (`androidx.navigation3`) — 타입 세이프 백스택 |
| 상태 저장 | ViewModel + `SavedStateHandle` |

## 핵심 기능별 라이브러리

### 갤러리 CRUD

| 기능 | 구현 |
|---|---|
| 조회 (사진·영상, 앨범/버킷별, 날짜 정렬) | `ContentResolver` + `MediaStore.Images/Video/Files` 쿼리, `ContentObserver`로 변경 감지 |
| 대량 목록 | 메모리 리스트 + `LazyVerticalGrid` (Paging 3 는 수십만 장 규모가 실측되면 도입 — 근거는 ARCHITECTURE.md) |
| 썸네일·이미지 로딩 | **Coil 3** + 커스텀 Fetcher(`ContentResolver.loadThumbnail` 시스템 썸네일 캐시), 폴백은 `coil-video` |
| 삭제 / 휴지통 / 복원 | `MediaStore.createDeleteRequest` / `createTrashRequest` (API 30+). API 29 는 `RecoverableSecurityException` 경로로 삭제·수정만 지원 |
| 확인 없이 편집 (선택) | `MANAGE_MEDIA` (API 31+) — 설정의 "미디어 관리 앱" 으로 허용하면 시스템 확인 다이얼로그 생략 |
| 즐겨찾기 | `MediaStore.createFavoriteRequest` (API 30+) |
| 파일명 변경, 폴더(앨범) 이동 | `createWriteRequest` + `DISPLAY_NAME` / `RELATIVE_PATH` 업데이트 |
| EXIF 조회·편집 | `androidx.exifinterface` |
| 권한 | `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` (13+), `READ_MEDIA_VISUAL_USER_SELECTED` (14+ 부분 접근), `READ_EXTERNAL_STORAGE` (10~12), `POST_NOTIFICATIONS` (13+) |
| 영상 재생 | **Media3 ExoPlayer** |
| (확장) 영상 압축·트리밍 | Media3 Transformer |
| (확장) 중복·유사 사진 탐지 | 퍼셉추얼 해시 자체 구현 또는 ML Kit |

### Google 인증 / Drive

| 기능 | 구현 | 비고 |
|---|---|---|
| 로그인 + Drive 권한 | `play-services-auth` **AuthorizationClient** 하나로 계정 선택·scope 동의 처리 | 서버가 없어 ID 토큰이 필요 없으므로 Credential Manager 는 쓰지 않음. 계정 정보(이메일·저장공간)는 Drive `about` API 로 조회. Android OAuth 클라이언트(패키지명+SHA-1)만 등록하면 되고 앱에 클라이언트 ID 를 심지 않음 |
| OAuth scope | `https://www.googleapis.com/auth/drive.file` | 앱이 만든 파일만 접근(non-sensitive → Google 검증 불필요). 대신 사용자가 Drive 에서 직접 만든 기존 폴더는 보이지 않으므로 앱 루트 폴더("Easy Gallery") 아래에서 폴더를 만들어 고르게 함. 전체 Drive 접근이 필요해지면 `drive` scope + Google 검증 |
| Drive API 호출 | **Retrofit + OkHttp**로 Drive REST v3 직접 호출, **kotlinx.serialization** | 공식 Java 클라이언트(`google-api-services-drive`)는 Guava 등 의존성이 무겁고 Android 최적화가 약해 배제 |
| 업로드 | **Resumable upload** (`uploadType=resumable`) | 세션 URL 발급 → 청크 PUT → 중단 시 `Content-Range: bytes */total`로 오프셋 조회 후 재개 |

### 백그라운드 업로드

| 항목 | 구현 |
|---|---|
| 스케줄링 | **WorkManager** `CoroutineWorker`, 업로드 항목별 또는 배치 단위 |
| 장시간 작업 | `setForeground()` + 진행률 알림 (Foreground Service type `dataSync`) |
| 제약 조건 | 네트워크(Wi-Fi 전용 옵션), 충전 중, 배터리 부족 아님 — 사용자 설정으로 노출 |
| 재시도 | `Result.retry()` + 지수 백오프, 세션 URL 재사용 |
| 상태 관리 | Room에 업로드 큐(대기·진행·일시정지·완료·실패), 세션 URL, Drive fileId 저장 |
| 완료 후 처리 | 옵션에 따라 원본 삭제/휴지통 이동 (사용자 동의 다이얼로그 경유) |

### 데이터 / 저장

| 항목 | 선택 |
|---|---|
| 로컬 DB | **Room** (업로드 큐·이력, Drive 폴더 매핑 캐시) |
| 설정 | **DataStore** (Preferences) |
| 토큰 | AuthorizationClient가 관리. 별도 저장 필요 시 `EncryptedSharedPreferences` 대신 **Android Keystore** 기반 자체 암호화 (security-crypto는 deprecated) |

## 개발 도구 / 품질

| 항목 | 선택 |
|---|---|
| 정적 분석 | Android Lint + **detekt** (formatting 플러그인 포함) |
| 단위 테스트 | JUnit 4, **MockK**, **Turbine** (Flow), kotlinx-coroutines-test |
| Android 테스트 | Robolectric, Compose UI Test, Room 인메모리 |
| 로깅 | Timber |
| 크래시 리포트 (배포 후) | Firebase Crashlytics (선택) |
| CI | GitHub Actions — `detekt`, `lint`, `testDebugUnitTest`, `assembleDebug` |
| IDE | **Android Studio** (권장) 또는 VS Code |

## 로컬 환경 (macOS)

설치됨: Homebrew, Temurin JDK 21, Android SDK (`/opt/homebrew/share/android-commandlinetools` — platforms 36/37, build-tools 36/37, platform-tools), VS Code

설정/설치 필요:

```bash
# ~/.zshrc
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"

# 에뮬레이터 (실기기 사용 시 생략 가능)
sdkmanager "emulator" "system-images;android-36;google_apis_playstore;arm64-v8a"
avdmanager create avd -n pixel_api36 -k "system-images;android-36;google_apis_playstore;arm64-v8a" -d pixel_8

# IDE
brew install --cask android-studio
```

Gradle은 프로젝트의 Gradle Wrapper(`./gradlew`)로 실행하므로 별도 설치 불필요.

## Google Cloud 사전 준비

1. GCP 프로젝트 생성 → **Google Drive API** 활성화
2. OAuth 동의 화면 구성 (테스트 사용자 등록)
3. OAuth 클라이언트 ID (Android) 생성 — 패키지명 + **디버그/릴리스 SHA-1** 각각 등록
4. `drive.file` scope는 non-sensitive → 앱 검증 없이 사용 가능

## 개발 순서

1. 프로젝트 스캐폴딩 (Compose, Hilt, Version Catalog, detekt, CI) ✅ (2026-09-07)
2. 갤러리 조회 MVP — 권한 흐름, 타임라인 그리드, Coil 썸네일 ✅ (2026-09-07)
3. Google 로그인 + Drive 폴더 선택 + 다중 선택 업로드(포그라운드) ✅ (2026-09-07, GCP OAuth 클라이언트 등록 후 기기 검증 예정)
4. WorkManager 기반 백그라운드 업로드·재개·알림 ✅ (2026-09-07)
5. 갤러리 CRUD 확장 — 삭제/휴지통/즐겨찾기/이름 변경/이동 ✅ (2026-09-08)
6. 고급 기능 — 중복 탐지, 영상 압축, 자동 백업 규칙
