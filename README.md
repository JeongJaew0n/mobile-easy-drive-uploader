# Easy Gallery

핸드폰 갤러리(사진·영상)를 Google Drive로 쉽게 옮기고, 갤러리를 자유롭게 CRUD 하는 **Android 전용** 앱.

Kotlin · Jetpack Compose · Hilt · Navigation 3 · Room · WorkManager · Retrofit(Drive REST v3)

## 문서

- [기술 스택 및 결정 근거](docs/TECH_STACK.md)
- [코드 구조](docs/ARCHITECTURE.md)
- [애니메이션 개선 분석](docs/ANIMATION_IMPROVEMENT.md)

## 시작하기

```bash
# Android SDK 경로 (local.properties 의 sdk.dir 과 동일하게)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :app:assembleDebug        # 빌드
./gradlew :app:testDebugUnitTest    # 단위 테스트
./gradlew detekt                    # 정적 분석
```

`local.properties` 는 커밋되지 않으므로 처음 클론하면 직접 만든다:

```properties
sdk.dir=/path/to/android/sdk
```

## 요구 사항

- JDK 17+
- Android SDK Platform 37, Build-Tools 37
- minSdk 29 (Android 10)
