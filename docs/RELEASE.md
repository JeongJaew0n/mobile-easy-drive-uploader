# 릴리스 빌드

> 2026-09-08 `assembleRelease`/`bundleRelease` 첫 통과. R8(minify + shrinkResources) 적용, 디버그 49MB → 릴리스 APK 5.5MB.

## 서명

`app/build.gradle.kts` 의 `signingConfigs.release` 는 저장소 루트의 **`keystore.properties`** 를 읽는다.
파일이 없으면 경고를 찍고 **디버그 키로 폴백**한다 — 로컬·CI 에서 릴리스 빌드가 항상 돌아가되, 그 결과물은 스토어에 올릴 수 없다.

```properties
# keystore.properties (커밋 금지 — .gitignore 에 있음)
storeFile=/Users/<me>/keys/easygallery-release.jks
storePassword=…
keyAlias=easygallery
keyPassword=…
```

키 생성 (한 번만, 저장소 밖에):

```bash
keytool -genkeypair -v -keystore ~/keys/easygallery-release.jks -alias easygallery \
  -keyalg RSA -keysize 4096 -validity 10000
```

- **Play App Signing 을 쓴다.** 위 키는 "업로드 키"가 되고, 실제 앱 서명 키는 Google 이 보관한다. 업로드 키를 잃어도 재설정할 수 있다.
- 키 파일과 비밀번호는 비밀번호 관리자에. 저장소·CI 로그에 남기지 않는다.
- 릴리스 키 SHA-1 은 GCP OAuth Android 클라이언트에 **패키지 `com.jjw.easygallery`(접미사 없음)** 로 따로 등록해야 로그인이 된다. 디버그 등록(`.debug` + 디버그 SHA-1)과 별개.

## 빌드

```bash
./gradlew :app:assembleRelease     # APK  → app/build/outputs/apk/release/app-release.apk
./gradlew :app:bundleRelease       # AAB  → app/build/outputs/bundle/release/app-release.aab (Play 업로드용)
./gradlew :app:lintRelease
```

산출물 옆 `app/build/outputs/mapping/release/mapping.txt` 는 크래시 스택 복원용 — Play Console 에 함께 올린다.

## R8 확인 절차 (규칙을 바꿨거나 직렬화·리플렉션 의존 라이브러리를 추가했을 때)

1. `mapping/release/missing_rules.txt` 가 **없어야** 한다(있으면 R8 이 규칙을 요구하는 것).
2. `mapping.txt` 에 다음이 원래 이름으로 남아 있어야 한다:
   - `com.jjw.easygallery.**$$serializer` (kotlinx.serialization — Drive DTO, Nav 키)
   - `UploadWorker`, `AppDatabase_Impl`, `EasyGalleryApp`, `MainActivity`
   - Retrofit 인터페이스(`DriveApi`)는 이름이 바뀌어도 되지만 `-keepattributes *Annotation*, Signature` 가 살아 있어야 한다.
3. `docs/manual-tests/06-release-build.md` 의 실기기 항목 수행 — 특히 로그인·업로드·화면 복원은 R8 문제가 런타임에만 드러난다.

## Baseline Profile

`:baselineprofile` 모듈이 콜드 스타트·갤러리 플링 경로를 수집한다. `./gradlew :app:generateBaselineProfile` 로 만든
`app/src/release/generated/baselineProfiles/baseline-prof.txt` 를 **커밋**하면 릴리스 빌드에 포함되고, `profileinstaller` 가 설치 시 ART 에 심는다.
프로파일은 코드가 크게 바뀔 때(화면 추가·주요 라이브러리 업그레이드) 다시 생성한다.

## 버전

`app/build.gradle.kts` 의 `versionCode`(정수, 매 업로드마다 증가) / `versionName`(표시용, `0.1.0`). 태그는 `v0.1.0` 형식.

## 스토어 공개 전 남은 일

- OAuth scope 가 `drive`(전체, restricted) 이므로 **Google OAuth 검증 심사** + 개인정보처리방침 URL 필요. 테스트 모드(테스트 사용자 ≤100명)에서는 불필요.
- 앱 아이콘(현재 템플릿 기본), 스토어 스크린샷, 설명.
- `targetSdk` 37 검토(현재 36).
