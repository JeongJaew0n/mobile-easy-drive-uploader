# 자동 태그 — ML Kit 이미지 라벨링

작성 2026-09-13. 사진을 폰 안에서 분석해 "음식", "바다", "강아지" 같은 라벨을 자동으로 붙인다.
사용자가 직접 만드는 **카테고리와는 완전히 분리된 기능**이다(§2).

## 1. 범위

| 넣는 것 | 빼는 것 |
|---|---|
| ML Kit 이미지 라벨링(온디바이스, 기본 모델) | 글자 인식(OCR)·얼굴 감지·사물 감지 — 나중에 §9 |
| 사진만 | 영상 — 프레임 추출이 필요해 이번 범위 밖 |
| 전체 훑기 + 새 사진 자동 처리 | 사용자 학습·커스텀 모델 |
| 라벨별 사진 보기 화면 | 갤러리 필터·검색 통합 — §9 |
| 라벨 → 카테고리로 **복사**(사용자가 눌렀을 때만) | 카테고리 자동 수정 |

## 2. 왜 카테고리와 분리하나

사용자가 손으로 붙인 카테고리는 **의도**고, 기계가 붙인 라벨은 **추정**이다. 둘을 같은 표에 섞으면

- 재분석 때 사용자가 고친 것을 덮어쓸 위험이 있고,
- "자동으로 붙은 것만 지우기"가 불가능해지며,
- 카테고리 개수·색 팔레트 같은 기존 규칙(`CATEGORIES.md`)이 기계 라벨 수백 개에 밀린다.

그래서 별도 표(`auto_tag`)에 쌓고, 카테고리로 옮기고 싶을 때만 사용자가 **명시적으로 복사**한다.
복사한 뒤에는 일반 카테고리라 자동 태그가 다시 건드리지 않는다.

## 3. 라이브러리

| 항목 | 선택 | 근거 |
|---|---|---|
| 모델 배포 | `com.google.android.gms:play-services-mlkit-image-labeling` 16.0.8 | 앱에 모델을 넣는 `com.google.mlkit:image-labeling`(17.0.9)은 APK 가 몇 MB 늘어난다. 이 앱은 이미 Play 서비스(로그인)를 쓰므로 모델을 Play 서비스에 맡긴다 |
| 모델 | 기본 모델(약 400개 라벨) | 커스텀 모델은 학습 파이프라인이 필요하다. 먼저 기본으로 쓸모를 확인한다 |

- **네트워크 없음**: 추론은 폰 안에서 돈다. 단, Play 서비스 방식은 **모델을 처음 한 번 내려받는다**(Wi-Fi 권장). 내려받기 전에는 `MlKitException.UNAVAILABLE` 이 나므로 "모델 준비 중" 으로 안내하고 나중에 다시 시도한다.
- 카탈로그(`gradle/libs.versions.toml`)에 먼저 등록한다. R8 규칙은 Play 서비스 방식이라 추가가 필요 없을 것으로 보지만 `assembleRelease` 로 `missing_rules.txt` 부재를 확인한다.

## 4. 저장 (Room v8)

```kotlin
@Entity(
    tableName = "auto_tag",
    primaryKeys = ["mediaId", "label"],
    indices = [Index("label")],
)
data class AutoTagEntity(
    val mediaId: Long,
    /** ML Kit 이 돌려주는 영어 라벨 원문(예: "Food"). 표시는 §6 의 대응표로 */
    val label: String,
    /** 0.0~1.0 */
    val confidence: Float,
    val taggedAt: Long,
)

@Entity(tableName = "auto_tag_scan")
data class AutoTagScanEntity(
    @PrimaryKey val mediaId: Long,
    /** 이 둘이 그대로면 다시 분석하지 않는다(중복 검사 캐시와 같은 방식) */
    val sizeBytes: Long,
    val dateModifiedSeconds: Long,
    /** 라벨이 하나도 안 나온 사진도 기록해 매번 다시 돌리지 않는다 */
    val scannedAt: Long,
)
```

- 표 추가뿐이라 `AutoMigration(7 → 8)`. `app/schemas/` 커밋.
- `auto_tag` 는 사진이 지워져도 남을 수 있어, 중복 검사처럼 훑을 때 갤러리에 없는 `mediaId` 를 정리한다.
- 사진 하나에 라벨 여러 개가 붙는다(다중 라벨). 화면에서는 상위 몇 개만 보여준다.

## 5. 동작

### 5.1 분석 단위

```kotlin
class ImageLabeler @Inject constructor(private val context: Context) {
    suspend fun label(uri: Uri): List<AutoLabel>   // 신뢰도 내림차순
}
```

- **원본이 아니라 축소 이미지로 돌린다.** 모델 입력이 224px 안팎이라 원본(수천 픽셀)을 디코딩할 이유가 없다. `ImageDecoder` 로 512px 정도로 줄여 `InputImage.fromBitmap` 에 넘긴다. 메모리·속도 모두 이득이고 6119장을 훑어도 OOM 이 나지 않는다.
- `ACCESS_MEDIA_LOCATION` 은 쓰지 않는다. 위치 정보는 분류에 필요 없다.
- 라벨러 인스턴스는 **한 번 만들어 재사용**하고 훑기가 끝나면 `close()`. 사진마다 만들면 초기화 비용이 지배적이 된다.
- **비트맵은 반드시 `ARGB_8888`**. ML Kit 네이티브 분류기는 다른 포맷을 받으면 자바 예외가 아니라
  `JNI DETECTED ERROR ... Bitmap must have RGBA_8888 format` 으로 **프로세스를 죽인다**(실기기에서 1250장쯤 발생).
  `try/catch` 로 건너뛸 수 없으므로 디코딩 뒤 포맷을 확인하고 아니면 변환해서 넘긴다.

### 5.2 훑기

`AutoTagRepository.scan(onProgress)` — 중복 검사(`DuplicateRepository.scan`)와 같은 모양으로 맞춘다.

1. 갤러리에서 **사진만** 가져온다(`MediaType.IMAGE`).
2. `auto_tag_scan` 캐시와 크기·수정 시각을 대조해 **바뀐 것과 새것만** 고른다.
3. 하나씩 라벨링 → `confidence >= 0.6` 인 라벨만, 최대 5개까지 저장.
4. 갤러리에 없는 `mediaId` 의 태그·스캔 기록을 지운다.
5. 진행률을 `(완료, 전체)` 로 올린다.

실패(디코딩 불가·권한 없음·모델 미준비)는 그 사진만 건너뛰고 개수를 센다. 모델 미준비가 한 번이라도 나오면 훑기를 멈추고 "모델을 내려받는 중" 으로 알린다 — 계속 돌아도 전부 실패한다.

### 5.3 워커

`AutoTagWorker`(`@HiltWorker`) + `AutoTagScheduler`. `DuplicateScanWorker` 와 같은 구조다.

- 유니크 워크 `auto-tag-scan`, `ExistingWorkPolicy.KEEP`.
- 제약: `setRequiresBatteryNotLow(true)`, `setRequiresCharging` 은 **걸지 않는다**(사용자가 직접 누르는 동작이라 즉시 돌아야 한다). 대신 전체 훑기는 충전 중을 권장한다고 화면에 적는다.
- 20장 이상이면 포그라운드 알림(중복 검사의 `FOREGROUND_THRESHOLD` 와 같은 값, 같은 채널).
- 진행률은 `setProgress` → 화면이 `getWorkInfosForUniqueWorkFlow` 로 본다.

### 5.4 새 사진 — 매일 정해진 시각

자동 백업(`AutoBackupUseCase`)이 이미 "새로 추가된 항목"을 주기적으로 훑는다. 같은 트리거에 얹지 않고 **별도 워커**를 둔다 — 자동 백업을 끈 사용자도 자동 태그는 쓸 수 있어야 한다.

- **사용자가 시각을 고른다. 기본 새벽 4시.** `UserPreferences.autoTagMinuteOfDay`(0~1439)에 저장.
- **충전을 요구하지 않는다.** 제약은 배터리 여유(`setRequiresBatteryNotLow`)만. 대개 몇 장이라 부담이 작다.
- WorkManager 의 `PeriodicWorkRequest` 는 "매일 몇 시"를 지정할 수 없다(주기만 준다). 그래서 **`OneTimeWorkRequest` + 다음 해당 시각까지의 `initialDelay`** 로 잡고, 실행이 끝나면 워커가 **다음 날 것을 다시 예약**한다(자기 재예약). 유니크 워크 `auto-tag-daily`, `ExistingWorkPolicy.REPLACE`.
  - 정확한 알람(`AlarmManager.setExactAndAllowWhileIdle`)을 쓰지 않는 이유: `SCHEDULE_EXACT_ALARM` 권한이 필요하고 Doze 를 깨워 배터리를 먹는다. 사진 태깅은 몇 분 늦어도 상관없다.
  - 대신 **정확한 시각은 보장되지 않는다**. Doze·기기 꺼짐으로 밀리면 WorkManager 가 조건이 맞을 때 실행한다. 화면에 "대략 그 시각"이라고 적는다.
- 시각을 바꾸거나 스위치를 켜면 다시 예약하고, 끄면 `cancelUniqueWork` 한다.
- 마지막 훑기 이후 추가·수정된 사진만 대상이라(§5.2 의 캐시 판정) 대개 몇 장이다.

## 6. 라벨 표시 이름

ML Kit 은 영어 라벨을 준다(`Food`, `Beach`, `Dog`…). 한국어로 보여주려면 대응표가 필요하다.

- `res/values/strings.xml` 에 `auto_tag_label_food` 같은 식으로 넣고, 라벨 원문 → 리소스 ID 를 코드의 `Map` 으로 잇는다.
- **기본 모델의 라벨 목록은 고정**이므로 대응표도 고정이다. 표에 없는 라벨은 영어 원문을 그대로 보여준다(모델이 업데이트돼 새 라벨이 나와도 깨지지 않는다).
- 400개를 다 번역하지 않는다. 실제로 사진에 자주 붙는 것부터 채우고, 나머지는 원문으로 둔다.

## 7. 화면

**설정 → 자동 태그**(`AutoTagKey`, 중복 사진 항목 아래).

- 상단: 켜기/끄기 스위치(새 사진 자동 분석), **분석 시각 행**(탭하면 Material3 `TimePicker`, 기본 04:00), "지금 전체 분석" 버튼, 진행률, 마지막 분석 시각.
  - 시각 행은 스위치가 켜져 있을 때만 활성. 문구에 "이 시각쯤 자동으로 분석합니다(기기 상태에 따라 늦어질 수 있습니다)" 를 붙인다.
- 본문: 라벨 목록. 라벨 이름 + 사진 개수, 개수 내림차순. 탭하면 그 라벨의 사진 격자.
- 라벨 행 ⋮ → **"카테고리로 만들기"**: 같은 이름의 카테고리를 만들고(이미 있으면 그 카테고리에) 그 라벨이 붙은 사진을 전부 넣는다. 한 번 복사하면 끝이고 이후 동기화는 없다 — §2 의 이유.
- 라벨 행 ⋮ → **"이 라벨 숨기기"**: 쓸모없는 라벨(예: `Person`, `Sky` 처럼 너무 흔한 것)을 목록에서 감춘다. 태그 자체는 지우지 않는다.
- 하단: "자동 태그 전부 지우기" — `auto_tag`·`auto_tag_scan` 을 비운다. 카테고리는 건드리지 않는다.

갤러리·상세보기는 **이번 범위에서 건드리지 않는다**(§1). 자동 태그는 자기 화면 안에서만 보인다.

## 8. 검증

- **단위**: 라벨 필터링(신뢰도 임계·상위 N), 캐시 무효화 판정(크기·수정 시각), 갤러리에서 사라진 항목 정리, 라벨 → 표시 이름 대응(표에 없으면 원문). `ImageLabeler` 는 인터페이스로 두고 가짜 구현으로 저장소를 테스트한다 — ML Kit 자체는 실기기에서만 돈다.
- **계측/실기기**: `docs/manual-tests/12-auto-tagging.md` 에 항목을 만든다. 모델 첫 내려받기, 6119장 전체 훑기 시간·배터리, 진행률·알림, 라벨 정확도 눈으로 확인, 카테고리로 만들기, 전부 지우기, 사진 삭제 후 정리.
- **릴리스**: `assembleRelease` 후 `missing_rules.txt` 부재 확인.

## 9. 이후 후보 (이번 범위 밖)

- 글자 인식(`text-recognition-korean`)으로 영수증·서류·스크린샷 구분.
- 얼굴 감지로 인물 사진 구분(신원 식별은 하지 않는다).
- 영상: 대표 프레임 한 장을 뽑아 같은 라벨러에 넣기.
- 갤러리 필터·검색에 자동 태그 넣기.
- 커스텀 모델(MediaPipe Model Maker)로 사용자 정의 분류.

## 10. 진행 기록

- 2026-09-13 설계 작성.
- 2026-09-13 구현: Room v8(`auto_tag`·`auto_tag_scan`), `ImageLabeler`(ML Kit, 512px 축소 + 소프트웨어 비트맵),
  `AutoTagRepository.scan`(캐시 판정·정리·모델 미준비 시 중단), `AutoTagWorker`/`AutoTagScheduler`(즉시 실행 + 매일 지정 시각 자기 재예약),
  설정 진입점과 전용 화면(스위치·시각 선택·진행률·라벨 목록·카테고리로 만들기·숨기기·전부 지우기), 라벨 한국어 대응표 40개.
  단위 테스트는 `AutoTagSchedulerTest`(시각 계산 경계). 실기기 항목은 `manual-tests/12-auto-tagging.md`.
