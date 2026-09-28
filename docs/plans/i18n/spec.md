# 다국어(한국어·영어·일본어) — 계획

2026-09-28. 요구: "다국어 설계하고 구현해. 영어, 한국어, 일본어 정도만." (기기 테스트는 나중에)

## 결정

| 무엇 | 결정 | 이유 |
|---|---|---|
| 언어 | 한국어(`values/`, 기본) · 영어(`values-en/`) · 일본어(`values-ja/`) | 사용자 지정. 기본을 한국어로 두는 것은 `CLAUDE.md` 규칙("한국어 기본") 그대로 |
| 그 밖의 언어 기기 | 한국어로 보인다(기본 리소스) | 기본을 영어로 바꾸려면 규칙을 바꿔야 한다 — 필요해지면 그때 |
| 앱 안에서 언어 고르기 | 설정 → **언어**: 시스템 기본 / 한국어 / English / 日本語 | Android 13+ 의 앱별 언어(`LocaleManager`). AppCompat 을 새로 넣지 않는다 — 앱이 `ComponentActivity` 라 12 이하에서는 AppCompat 도 동작하지 않는다 |
| Android 12 이하(minSdk 29~32) | 언어 항목을 보이지 않는다. 기기 언어를 따른다 | 위와 같다 |
| 시스템 설정의 "앱 언어" 목록 | AGP `generateLocaleConfig` 로 `values-*` 에서 자동 생성 | 언어를 더할 때 목록을 따로 고치지 않는다 |
| 날짜 | 이미 로캘 형식(`ofLocalizedDate`)을 쓴다. 고정 형식 `yyyy.MM.dd`(기간 막대)는 로캘 SHORT 로 바꾼다 | |

## 예외 메시지 — 가장 큰 일

화면이 `e.message` 를 그대로 스낵바에 띄우는 곳이 25곳쯤이고, 예외는 던질 때 **한국어 문장**을 만든다
("목록 조회 실패 (403): …"). 문자열만 번역해서는 오류가 계속 한국어로 나온다.

1. **`UiText`**(`core/common/text`): 문자열 리소스 ID + 인자. 인자에 `UiText` 를 넣을 수 있다(작업 이름 "목록 조회" 같은 것).
2. 사용자에게 보일 수 있는 예외는 **`LocalizedError`** 를 구현해 `uiText` 를 싣는다 — 인증·원격 저장소·업로드·TLS·
   영상 압축·다른 계정 업로드 등.
3. **`Throwable.displayMessage(resources)`**(`core/ui/text`): `LocalizedError` 면 그 문장, 아니면 `localizedMessage`.
   화면(Route·Screen)이 이걸로 띄운다. ViewModel 은 문자열이 아니라 **예외를 이벤트에 싣는다** — 문장은 화면의 언어로 만든다.
4. 원격 오류는 작업 이름을 문자열 ID 로 받는다: `requireSuccess(R.string.op_list)`.
5. 업로드 목록에 남는 실패 문장(`upload_tasks.errorMessage`)은 **실패한 순간의 언어**로 저장된다. 목록은 원래
   `errorReason` 코드로 문장을 고르고, 이 값은 코드가 없을 때의 대비책이라 받아들인다.
6. 개발자만 볼 메시지(`requireNotNull`, "비트맵 변환 실패" 등)는 영어로 바꾼다. 로그(`Timber`)는 그대로 둔다.

## 번역

- 문자열 약 600개(복수형 21개 포함)를 영어·일본어로 옮긴다. 복수형: 영어 `one`/`other`, 일본어 `other`
- 제품·브랜드 이름(Easy Gallery, Google Drive, S3, WebDAV, SMB, SFTP)은 번역하지 않는다
- 한국어 조사를 넣은 형식 문자열("%1$s 로 올리기") 은 언어마다 어순을 맞춘다

## 지키게 하는 장치

- Lint `MissingTranslation`(오류) — 새 문자열에 번역이 없으면 빌드가 실패한다
- 단위 테스트: 세 언어의 키 집합과 **형식 인자(`%1$s`·`%2$d`)** 가 같은지. 인자가 어긋나면 런타임에 앱이 죽는다

## 확인

- 단위 테스트: 위 리소스 검사, `displayMessage`
- 기기(나중에): `docs/manual-tests/16-i18n.md`
