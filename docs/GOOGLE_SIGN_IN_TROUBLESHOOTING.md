# Google 로그인 실패 — 원인 후보와 확인 절차

작성 2026-09-09. 사용자 보고: 설정 → "Google 계정 연결" 이 동작하지 않는다.
이 시점에 기기 로그를 읽을 수 없어(USB 분리, Wi-Fi ADB 무응답) **코드 경로 분석으로 실패 지점별 증상을 정리**하고, 폰에서 한 번 시도한 뒤 로그로 확정하는 절차를 남긴다. 기기 조작은 사용자 지시가 있을 때만 한다.

## 1. 현재 로그인 흐름

```
설정 화면 [Google 계정 연결]
 → SettingsViewModel.signIn()
 → SignInUseCase.begin()
 → GoogleAuthRepository.beginSignIn()
     AuthorizationClient.authorize(AuthorizationRequest{ scope = …/auth/drive })   ← ① Play 서비스 호출
     ├ hasResolution == false → 토큰 캐시 → SignInStep.Completed
     └ hasResolution == true  → SignInStep.NeedsConsent(pendingIntent)
 → SettingsScreen 이 pendingIntent 실행 (계정 선택 + 동의 화면)                 ← ② 시스템 UI / 웹 동의
 → onConsentResult(resultCode, data)
     ├ resultCode != RESULT_OK → "로그인이 취소되었습니다"
     └ RESULT_OK → completeSignIn(data) → getAuthorizationResultFromIntent      ← ③ 결과 파싱
 → SignInUseCase.finish() → Drive `about` API 로 이메일·저장공간 조회            ← ④ Drive REST 첫 호출
 → prefs.setAccount(...) → 스낵바 "연결되었습니다"
```

관련 파일: `core/data/auth/GoogleAuthRepository.kt`, `core/domain/usecase/SignInUseCase.kt`, `feature/settings/SettingsViewModel.kt`(`runBusy` 의 예외 처리), `feature/settings/SettingsScreen.kt`(스낵바).
라이브러리: `play-services-auth 22.0.0` 의 `Identity.getAuthorizationClient`. 앱에 클라이언트 ID 를 심지 않고 **패키지명 + 서명 SHA-1** 로 GCP 의 Android OAuth 클라이언트와 매칭된다.

## 2. 이 빌드가 GCP 에 요구하는 값 (2026-09-09 확인)

| 항목 | 값 | 확인 방법 |
|---|---|---|
| 디버그 APK 패키지명 | **`com.jjw.easygallery.debug`** (`applicationIdSuffix = ".debug"`) | `app/build.gradle.kts` |
| 디버그 APK 서명 SHA-1 | `A1:10:A5:5F:91:AC:1F:A8:65:0A:5F:26:B8:A7:31:21:5F:E0:14:38` | `apksigner verify --print-certs app-debug.apk` |
| 릴리스 APK | 패키지 `com.jjw.easygallery`, 현재는 keystore.properties 가 없어 **같은 디버그 키**로 서명됨 | 릴리스로 로그인하려면 이 패키지명으로 클라이언트 하나 더 |
| scope | `https://www.googleapis.com/auth/drive` (restricted) | `GoogleAuthRepository.DRIVE_SCOPE` |

GCP 콘솔에서 필요한 것: ① Drive API 사용 설정 ② OAuth 동의 화면(외부·**테스트** 상태, 테스트 사용자에 로그인할 계정 추가, scope 에 `…/auth/drive`) ③ Android OAuth 클라이언트(위 패키지명 + SHA-1, **대문자·콜론 그대로**).

## 3. 실패 지점별 증상 — 폰에서 보이는 것으로 원인을 좁힌다

| 폰에서 보이는 것 | 실패 지점 | 가장 유력한 원인 | 로그에 남는 것 |
|---|---|---|---|
| 버튼을 눌러도 계정 선택 화면이 안 뜨고 스낵바에 **`10:`** (또는 `10: DEVELOPER_ERROR`) | ① `authorize()` 가 `ApiException(10)` | Android OAuth 클라이언트 미등록, 또는 패키지명(`.debug` 누락)·SHA-1 불일치 | `SettingsViewModel: settings action failed … ApiException: 10:` |
| 스낵바 **`8:`** / `17:` / `16:` | ① | 8 INTERNAL_ERROR(Play 서비스 상태), 17 API_NOT_CONNECTED, 16 CANCELED | 같은 위치, 코드 다름 |
| 계정 선택은 뜨는데 다음 웹 화면이 **"액세스 차단됨 / 이 앱은 Google 의 확인을 받지 않았습니다"** (Error 403: access_denied) | ② | 동의 화면이 테스트 상태인데 **로그인 계정이 테스트 사용자에 없음**, 또는 scope `…/auth/drive` 가 동의 화면에 등록되지 않음 | 앱 로그 없음. 창을 닫으면 RESULT_CANCELED → "로그인이 취소되었습니다" 로만 보임 |
| 동의까지 마쳤는데 "로그인이 취소되었습니다" | ③ `getAuthorizationResultFromIntent` 가 `ApiException` | 결과 파싱 실패. **현재 코드가 모든 ApiException 을 '취소'로 바꿔 버려 원인이 가려진다**(§5) | `GoogleAuthRepository: authorization result parse failed: status=N` |
| 동의까지 마쳤는데 스낵바에 **`HTTP 403`** 류 메시지 | ④ Drive `about` | Drive API 미활성화(`accessNotConfigured`), 또는 토큰 scope 부족 | `settings action failed … HttpException 403` |
| 스낵바 **`HTTP 401`** | ④ | 토큰이 즉시 무효 — 드물게 Play 서비스 캐시 문제. 계정 재선택으로 해결되는지 확인 | `TokenAuthenticator` 재시도 후 401 |
| 아무 반응 없음(스낵바도 없음) | UI | `runBusy` 가 `isBusy` 면 무시한다 — 이전 호출이 안 끝났거나 `SettingsEvent` 수집이 끊김 | `Timber` 로그 없음 |

## 4. 확정 절차 (사용자가 폰에서 1회 시도, 로그는 읽기만)

```bash
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
adb -s R3CTC0CSZ1R logcat -c                       # USB 연결 상태에서
# 폰: 설정 → Google 계정 연결 → 나타나는 화면을 그대로 진행/닫기
adb -s R3CTC0CSZ1R logcat -d | grep -E "GoogleAuthRepository|SettingsViewModel|SignInUseCase|ApiException|GoogleApiManager|gms\.auth|HttpException"
```

- `ApiException: 10` → §3 첫 행. GCP 콘솔의 Android 클라이언트에서 패키지명이 `com.jjw.easygallery.debug` 인지, SHA-1 이 §2 와 글자 단위로 같은지 본다. 고친 뒤 **Play 서비스 캐시 때문에 최대 수 분~재부팅** 이 필요할 수 있다.
- 웹 화면에서 "액세스 차단됨" → GCP 콘솔 → OAuth 동의 화면 → 테스트 사용자에 계정 추가. 동의 화면 상태가 "프로덕션"이면 restricted scope 는 검증 전까지 막힌다 → 테스트 상태로 되돌린다.
- `HttpException 403` → API 및 서비스 → 라이브러리 → Google Drive API "사용".
- 로그에 `authorization result parse failed: status=…` 만 있고 스낵바는 "취소" → §5 의 코드 수정을 먼저 하고 다시 본다.

## 5. 코드 쪽 진단 약점 (수정 제안, 승인 후 작업)

1. `GoogleAuthRepository.completeSignIn` 이 **모든 `ApiException` 을 `SignInCancelledException` 으로 바꾼다.** 취소(status 16 / `RESULT_CANCELED`)는 이미 `onConsentResult` 에서 걸러지므로 여기 도달하는 ApiException 은 진짜 오류다 → 상태 코드를 담은 `AuthFailedException(statusCode)` 으로 바꾸고 스낵바에 코드와 짧은 안내("GCP 등록을 확인하세요")를 띄운다.
2. `beginSignIn` 의 `authorize()` 예외는 `runBusy` 가 `e.message` 로만 보여 준다(`"10: "`). `ApiException` 을 잡아 `statusCode` → 사람이 읽을 문구(10 DEVELOPER_ERROR: 패키지명/SHA-1 등록 확인, 8/17: Play 서비스 상태, 7: 네트워크)로 매핑한다.
3. 로그인 시도 자체를 `Timber.i` 로 남긴다(요청 scope, 결과 `hasResolution`, 걸린 시간). 지금은 실패 시에만 로그가 있어 "아무 반응 없음" 을 구분할 수 없다.
4. `manual-tests/02-google-drive.md` DRV-01 에 §2 의 SHA-1 확인 명령을 적어 둔다(이미 값은 있음).

## 6. 확인된 사실 / 아직 모르는 것

- 확인: 디버그 APK 의 패키지명·SHA-1(§2), 코드 경로(§1), 각 실패 지점의 표시 방식(§3). 릴리스 APK 도 현재 디버그 키로 서명됨.
- 미확인: 실제 실패 지점. 기기 로그가 없어 §3 중 어느 행인지 모른다. 로그인 시도가 GCP 등록 **전**이었다면 §3 첫 행(`10`)이 가장 유력하다.

## 7. 진행 기록

- **2026-09-09 00:42 (무선 ADB, 사용자 지시로 시도)** 설정 → "Google 계정 연결" 탭 → Play 서비스 `AuthorizationActivity` 의 **계정 선택 화면이 정상적으로 뜸**. 따라서 §3 의 ①(`authorize()` → `ApiException 10`) 은 아니다 — Android OAuth 클라이언트(패키지·SHA-1)는 매칭된다. 남은 후보는 ②(동의 웹 화면 "액세스 차단됨" = 테스트 사용자 미등록/scope 미등록) · ③(결과 파싱) · ④(Drive API 미활성화 403). 계정 선택·동의는 사용자가 직접 진행하기로 하고 여기서 멈춤. 이어서 §4 의 로그 명령으로 확정 예정.
