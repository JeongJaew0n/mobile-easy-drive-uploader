# 1553건이 모두 403 으로 실패했다 — 이유는 화면에 없었다

2026-09-26, Galaxy Z Flip 4(`SM-F721N`).

## 증상

앱을 켜면 배너에 **"1553개 항목 업로드에 실패했습니다"**. 업로드 목록에 들어가면 줄마다 이렇게 적혀 있다.

```
20230417_185137.jpg
업로드 실패 (403): {
  "error": {…
```

무엇이 잘못됐는지 알 수 없다. 두 줄짜리 자리에 JSON 이 들어가 **여는 중괄호에서 잘렸다.**

## 원인 — 두 가지가 겹쳤다

### 1. 진짜 원인은 앱 밖에 있었다

DB 를 꺼내 보니 답이 그 안에 있었다.

```bash
adb -s <기기> shell run-as com.jjw.easygallery.debug cat databases/easy_gallery.db > /tmp/eg.db
sqlite3 /tmp/eg.db "SELECT errorMessage, COUNT(*) FROM upload_tasks
                    WHERE errorMessage IS NOT NULL GROUP BY errorMessage;"
```

```
업로드 세션 생성 실패 (403)                                    | 294
업로드 실패 (403): {"error":{"code":403,
  "message":"The user's Drive storage quota has been exceeded.",
  "errors":[{"reason":"storageQuotaExceeded", ...}]}}          | 1259
```

**Google Drive 용량이 가득 찼다.** 앱 버그가 아니다. 그런데 앱은 그 말을 전하지 못했다.

### 2. 오류를 전달하는 방식이 셋 다 틀렸다

| | 무엇이 문제였나 |
|---|---|
| 본문을 통째로 붙였다 | `"$what 실패 ($code): $body"` — JSON 이 그대로 메시지가 됐고 목록에서 잘렸다 |
| 세션 생성 경로는 본문을 읽지도 않았다 | 294건이 `업로드 세션 생성 실패 (403)` 로만 남아 이유를 알 길이 없었다 |
| 소용없는 실패를 1553번 반복했다 | 용량이 찬 상태에서 한 건씩 올려보고 한 건씩 실패했다. 서버에 1553번 요청이 갔다 |

## 고친 것

**1. 본문을 파싱해서 사람이 읽을 것과 기계가 읽을 것을 가른다** (`RemoteErrorBody.kt`).
Google API 오류 JSON 에서 `errors[0].reason`(기계용 코드)과 `error.message`(한 줄)를 뽑는다.
JSON 이 아니면 **첫 줄만** 남긴다. 원본은 로그에만 남긴다.

**2. 사유 코드를 DB 에 남기고 화면이 우리 문장을 고른다.**
`upload_tasks.errorReason` 컬럼(스키마 11, AutoMigration). 화면은 코드를 보고
`Drive 저장 공간이 가득 찼습니다` 처럼 바꾸고, 모르는 코드일 때만 서버 원문을 보여준다.
목록의 실패 줄은 2줄 → 4줄로 늘렸다.

**3. 소용없는 실패는 한 건에서 멈춘다.**
`RemoteStorageException.isHopeless`(지금은 `storageQuotaExceeded`). 하나 나오면 남은 대기 항목을
**같은 이유로 한꺼번에 접고**(`failAllPending`) 워커를 세운 뒤 알림으로 이유를 알린다.
대기로 남겨두면 화면에는 "업로드 중" 으로 보이고, 계속 시도하면 같은 실패가 쌓인다.

**4. 배너도 이유를 말한다.** 실패가 전부 같은 사유일 때만
`1553개 항목 업로드에 실패했습니다 — Drive 저장 공간이 가득 찼습니다` 로 붙인다.
사유가 섞였으면 붙이지 않는다 — 배너가 거짓말을 하면 안 된다.

## 곁가지 — 스키마 파일이 덮어써질 뻔했다

엔티티에 컬럼을 넣고 `version` 을 올리기 **전에** 한 번 컴파일하면, Room 이 **기존 버전의
`schemas/N.json` 을 새 컬럼으로 갱신해 버린다.** 그대로 두면 `AutoMigration(10→11)` 이
"차이 없음" 으로 계산돼 `ALTER TABLE` 이 생성되지 않고, 기존 설치가 조용히 깨진다.

`git checkout app/schemas/<...>/10.json` 으로 되돌리고 다시 생성한 뒤,
생성된 마이그레이션에 실제로 `ALTER TABLE` 이 들어갔는지 확인했다.

```bash
grep -n execSQL app/build/generated/ksp/debug/kotlin/**/AppDatabase_AutoMigration_10_11_Impl.kt
# → ALTER TABLE `upload_tasks` ADD COLUMN `errorReason` TEXT DEFAULT NULL
```

**컬럼을 더할 때는 `version` 을 먼저 올리고 컴파일한다.** 순서가 반대면 스키마 파일이 오염된다.

## 곁가지 2 — `run-as cat` 으로 DB 를 볼 때는 `-wal` 까지

Room 은 WAL 모드라 최근 쓰기가 `easy_gallery.db-wal` 에 있다. `.db` 하나만 꺼내 보면
**몇 분 전 상태**가 나온다. 이것 때문에 "접기가 0건 처리했다", "2건이 RUNNING 으로 남았다" 고
잘못 읽고 없는 버그를 쫓았다.

```bash
for x in "" "-wal" "-shm"; do
  adb -s <기기> shell run-as com.jjw.easygallery.debug cat "databases/easy_gallery.db$x" > "/tmp/q.db$x"
done
sqlite3 /tmp/q.db "SELECT state, errorReason, COUNT(*) FROM upload_tasks GROUP BY state, errorReason;"
```

## 기기 확인 (2026-09-26, Z Flip 4)

| | 결과 |
|---|---|
| 목록의 실패 줄 | `Drive 저장 공간이 가득 찼습니다` — JSON 도 잘림도 없다 |
| 갤러리 배너 | `1553개 항목 업로드에 실패했습니다 — Drive 저장 공간이 가득 찼습니다` |
| 일괄 접기 | 개별 실패 4건 뒤 `folded 1549 unfinished item(s)` 한 번. 1553건 전부 FAILED + 사유 |
| 남은 RUNNING | 0 |
| 서버 요청 | 1553회 → **4회** |
| 스키마 마이그레이션 | 기존 데이터 그대로 10 → 11, 크래시 없음 |

## 남은 것

`errorReason` 은 Google API 코드를 그대로 쓴다. S3·WebDAV 는 이 코드 체계가 없어
`errorMessage` 의 첫 줄로 떨어진다 — 그쪽 제공자별 매핑은 필요해지면 그때 넣는다.
