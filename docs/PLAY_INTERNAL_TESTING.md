# Play 내부 테스트로 올리기

앱 안에서 자체 업데이트하는 대신 Play 에 맡기기로 했다. 판단 근거는 `docs/SELF_UPDATE.md`.

## 왜 내부 테스트인가

| 트랙 | 테스터 | 검토 | 배포까지 |
|---|---|---|---|
| **내부 테스트** | 최대 100명 | **없음** | 몇 분 |
| 비공개 | 목록당 2000명 | 있음 | 수일~수주 |
| 공개·프로덕션 | 제한 없음 | 있음 | 수일~수주 |

내부 테스트만 쓰면 **데이터 보안 양식이 면제**된다. 스토어 등록정보도 대부분 필요 없다.
업데이트 설치는 Play 가 맡으므로 앱 코드에 아무것도 넣지 않는다.

**프로덕션은 별개다.** 2023-11-13 이후 만든 **개인** 개발자 계정은 프로덕션에 내기 전에
**비공개 테스트를 12명 이상으로 14일 연속** 돌려야 한다. 내부 테스트는 그 요건을 채워주지 않는다.

## 함정 — 서명이 바뀌면 Drive 로그인이 깨진다

이게 이번 작업에서 가장 조심할 부분이다.

Google 인증은 앱에 클라이언트 ID 를 심지 않고 **패키지명 + 서명 SHA-1** 로 GCP 의 Android
OAuth 클라이언트와 맞춘다(`docs/GOOGLE_SIGN_IN_TROUBLESHOOTING.md`). 지금 GCP 에 등록된 것은
**디버그 키**의 지문이다.

```
com.jjw.easygallery.debug + A1:10:A5:5F:91:AC:1F:A8:65:0A:5F:26:B8:A7:31:21:5F:E0:14:38
```

Play 에 올리면 두 가지가 동시에 바뀐다.

1. **패키지명** — 디버그 접미사가 빠져 `com.jjw.easygallery`
2. **서명 키** — 릴리스 키, 그리고 Play App Signing 을 쓰면 **Google 이 다시 서명**한다

그래서 GCP 에 **새 Android 클라이언트를 하나 더** 만들어야 한다. 넣을 SHA-1 은 우리 릴리스
키의 것이 아니라 **Play Console 이 알려주는 "앱 서명 키 인증서"의 SHA-1** 이다 — 사용자
기기에 설치되는 APK 는 그 키로 서명돼 있다. 이걸 틀리면 테스터가 Drive 로그인에서
"앱 등록이 GCP 와 맞지 않는다" 를 만난다.

기존 디버그 클라이언트는 **지우지 말 것.** 개발 중에는 계속 그쪽을 쓴다.

## 함정 — 테스터도 GCP 테스트 사용자여야 한다

OAuth 동의 화면이 **테스트** 상태라 등록된 계정만 로그인된다. Play 내부 테스터로 넣는 것과
GCP 테스트 사용자로 넣는 것은 **별개**다. 둘 다 해야 그 사람이 Drive 에 연결할 수 있다.

`drive.file` 은 restricted scope 가 아니라서 Google 보안 심사(CASA)는 받지 않아도 된다
(`docs/DRIVE_FILE_SCOPE.md`). 테스트 상태로도 100명까지 쓸 수 있다.

## 순서

### 1. 릴리스 키 만들기 (한 번만, 저장소 밖에)

```bash
keytool -genkeypair -v -keystore ~/keys/easygallery-release.jks -alias easygallery \
  -keyalg RSA -keysize 4096 -validity 10000
```

비밀번호는 비밀번호 관리자에 넣는다. **이 키를 잃으면** Play App Signing 을 쓰는 한
업로드 키는 재설정할 수 있지만, 쓰지 않는다면 그 앱은 두 번 다시 업데이트하지 못한다.

그리고 저장소 루트에 `keystore.properties` (커밋되지 않는다 — `.gitignore` 에 있다):

```properties
storeFile=/Users/<me>/keys/easygallery-release.jks
storePassword=…
keyAlias=easygallery
keyPassword=…
```

### 2. AAB 빌드

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
./gradlew :app:bundleRelease
# app/build/outputs/bundle/release/app-release.aab
```

키가 제대로 잡혔는지 확인한다 — 빌드 로그에 디버그 키 폴백 경고가 없어야 한다.

### 3. Play Console

1. 앱 만들기 — 이름, 기본 언어, **앱**, **무료**
2. Play App Signing 약관 동의
3. **테스트 → 내부 테스트 → 새 버전 만들기** → AAB 업로드
4. 테스터 목록 만들기(이메일) → 저장 → **검토 후 출시**
5. 테스터에게 참여 링크 전달. 링크로 옵트인한 뒤 Play 스토어에서 설치

### 4. GCP 추가 등록 (이걸 빼먹으면 로그인이 안 된다)

1. Play Console → **설정 → 앱 서명** → "앱 서명 키 인증서"의 SHA-1 복사
2. GCP → 사용자 인증 정보 → **Android OAuth 클라이언트 추가**
   - 패키지명 `com.jjw.easygallery` (접미사 없음)
   - SHA-1: 위에서 복사한 것
3. OAuth 동의 화면 → 테스트 사용자에 **테스터 계정 전부** 추가

### 5. 확인

테스터 한 명(본인이어도 된다)이 Play 로 설치한 뒤 Drive 연결까지 되는지 본다.
여기서 실패하면 십중팔구 4번이다.

## 다음 버전을 낼 때

`versionCode` 를 올린다(현재 1). Play 는 같은 versionCode 를 두 번 받지 않는다.
`versionName` 은 사람이 읽는 값이라 자유롭게.

```kotlin
versionCode = 2
versionName = "0.2.0"
```

AAB 를 올리면 테스터에게 몇 분 안에 업데이트 알림이 간다.
