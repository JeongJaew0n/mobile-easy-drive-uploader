# 업로드 원장을 계정별로 — 그리고 공유 받은 폴더 보기

2026-09-26 시작. 배경은 `docs/MULTI_GOOGLE_ACCOUNT.md` §4.1·§5.4.

## 1. 무엇이 문제인가

`uploaded_media` 는 "이 사진은 이미 올렸다" 의 근거다(갤러리 배지·백업 개수·업로드 중복 제외·
자동 백업). 여기에 두 가지 결함이 있다.

### 1.1 기본 키가 `mediaId` 하나다

```kotlin
@PrimaryKey val mediaId: Long          // + @Insert(onConflict = REPLACE)
```

**사진 한 장에 기록이 한 줄뿐이다.** 같은 사진을 Drive 에 올리고 S3 에 또 올리면 S3 기록이
Drive 기록을 **덮어쓴다.** 그러면 Drive 쪽에서 그 사진은 "안 올린 것" 이 되고, 다음 업로드 때
Drive 에 한 벌 더 생긴다. 다중 저장소(`docs/MULTI_CLOUD.md`)를 넣을 때부터 있던 결함이다.

### 1.2 Google 계정을 구분하지 않는다

Drive 는 계정이 하나라는 전제라 전부 `accountId = null` 로 적는다. A 로 올린 뒤 B 로 바꾸면
B 에는 한 장도 없는데 앱은 "백업 5,985개" 라고 하고, **업로드에서도 뺀다.**

## 2. 설계

### 2.1 "어디로 올렸나" 를 한 칸으로

```kotlin
@Entity(tableName = "uploaded_media", primaryKeys = ["mediaId", "destination"],
        indices = [Index("destination")])
data class UploadedMediaEntity(
    val mediaId: Long,
    val destination: String,   // 새 칸 — 키의 일부
    val driveFileId: String,
    val folderId: String?,
    val uploadedAt: Long,
    val accountId: String?,    // 남겨둔다(원래 뜻 그대로). 조회는 destination 으로 한다
)
```

| 올린 곳 | `destination` |
|---|---|
| Google Drive | `drive:<연결된 이메일>` |
| 다른 저장소 | `remote:<accountId>` |

키가 `(mediaId, destination)` 이 되면 한 사진이 여러 곳에 동시에 "올라감" 으로 남는다(§1.1 해결).
Drive 는 이메일까지 들어가므로 A 와 B 가 갈린다(§1.2 해결).

`accountId` 를 키에 넣지 않는 이유: Room 은 기본 키 칸이 NULL 이면 안 되는데, Drive 는
`accountId = null` 로 적는다. NULL 이 없는 새 칸을 만드는 편이 뜻도 분명하다.

### 2.2 호출하는 쪽은 바꾸지 않는다

`observeUploadedIds(accountId)`·`uploadedAmong(ids, accountId)`·`record(...)` 는 지금처럼
`accountId`(null = Drive)를 받는다. **원장이 스스로** 지금 연결된 이메일을 읽어 `destination` 으로
바꾼다. "Drive 기록은 지금 연결된 계정 기준" 이라는 규칙이 한 곳에만 있게 된다.

- 관찰(Flow)은 연결 계정이 바뀌면 **다시 흘러야** 한다 — 설정의 이메일을 `flatMapLatest` 로 따라간다.
- Drive 에 연결돼 있지 않으면 Drive 기록은 **빈 집합**이다. 어느 계정 것인지 모르므로 "백업됨" 이라고 할 수 없다.

### 2.3 옛 기록 — 누가 올렸는지 적혀 있지 않다

스키마 11 까지의 Drive 기록에는 이메일이 없다. 마이그레이션은 DataStore(연결 계정)를 읽을 수 없으므로
두 단계로 나눈다.

1. **마이그레이션(11 → 12, 수동)**: Drive 행은 `destination = 'drive:'`(주인 미정),
   다른 저장소 행은 `'remote:' || accountId` 로 옮긴다.
2. **처음 연결 계정을 알게 되는 순간** 주인 미정 행을 그 계정에 준다:
   `UPDATE OR REPLACE uploaded_media SET destination = 'drive:<email>' WHERE destination = 'drive:'`.
   이미 옮겼으면 0행이라 몇 번 불러도 같다. 프로세스당 한 번만 부른다.

**가정**: 옛 기록은 업그레이드 뒤 처음 보이는 연결 계정이 올린 것이다. 지금까지 앱은 계정 하나만
써 왔으므로 대개 맞다. 틀리는 경우는 "업그레이드 전에 계정을 바꾼 적이 있다" 뿐이고, 그때는
옛 기록이 새 계정에 붙는다 — 오늘과 같은 결과라 나빠지지는 않는다.

### 2.4 AutoMigration 이 아닌 이유

기본 키가 바뀌고, 새 칸의 값을 **기존 칸에서 계산**해야 한다(`CASE WHEN accountId IS NULL ...`).
AutoMigration 은 기본값밖에 못 넣는다. `CLAUDE.md` 의 "단순하면 AutoMigration" 에 해당하지 않는다.
테스트는 `MigrationTestHelper` 로 11 → 12 를 실제 데이터로 돌린다.

## 3. 공유 받은 폴더를 보기 전용 폴더로

지금 보기 폴더 피커는 `'root' in parents` 만 훑는다. **공유 받은 폴더는 내 루트에 없어서 목록에
안 뜬다.** 피커 최상위에 **"공유 문서함"** 을 하나 둔다.

```
내 드라이브
├── 공유 문서함 ▸        ← 새로. 탭하면 sharedWithMe = true 인 폴더들
├── 20250831_0901_상해
└── ...
```

- 공유 문서함 자체와 내 드라이브 최상위는 **고를 수 없다**(폴더가 아니라 목록이다).
- 공유 폴더 안으로 들어가면 평소처럼 `'<id>' in parents` — `drive.readonly` 로 읽힌다.
- 고른 폴더는 지금처럼 `ViewFolder(id, name)` 로 저장된다. 보는 쪽은 바꿀 것이 없다.
- 라벨("공유 문서함")은 UI 문자열이라 Route 가 만든다. ViewModel 은 조회만 한다.

## 4. 범위 밖

- 업로드 대기열이 계정을 붙들지 않는 것. 대기 중에 계정을 바꾸면 남은 것은 새 계정으로 간다(지금도 그렇다).
- 계정을 바꿔도 폴더 설정이 남게 하는 것(`MULTI_GOOGLE_ACCOUNT.md` §4.1-3).
- 일회성 브라우저 로그인(§5). 콘솔 설정 두 가지가 먼저다.
