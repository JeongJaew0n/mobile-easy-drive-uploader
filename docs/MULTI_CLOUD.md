# 다중 클라우드 연동 — 저장소 제공자 추상화와 S3 호환(Naver Cloud·KT Cloud·AWS·MinIO)

작성 2026-09-09. 지금 앱은 Google Drive 전용이다(`DriveRepository`, `DriveUploader`, 큐·원장의 `driveFileId`). 여기에 **Naver Cloud, KT Cloud, 그 밖의 클라우드**와 **NAS**(`NAS_STORAGE.md`)를 붙일 수 있도록 저장소를 추상화하고, 첫 구현으로 **S3 호환 오브젝트 스토리지**를 넣는다.

## 1. 대상 서비스와 프로토콜 — 왜 S3 호환인가

| 서비스 | 개인용 앱이 쓸 수 있는 공개 API | 결정 |
|---|---|---|
| Google Drive | Drive REST v3(OAuth) | 기존 유지 |
| **Naver Cloud Platform Object Storage** | **S3 호환 API**(`kr.object.ncloudstorage.com`, Access/Secret Key, SigV4) | S3 제공자로 지원 |
| Naver MYBOX(개인 클라우드) | 공개 API 없음 | **불가**. 문서에 명시 |
| **KT Cloud Object Storage** | **S3 호환 API**(ktcloud S3 endpoint, Access/Secret Key) | S3 제공자로 지원 |
| AWS S3 / Cloudflare R2 / Backblaze B2 / Wasabi / MinIO(자가 호스팅) | S3 호환 | 같은 제공자에서 endpoint·region 만 다름 |
| Dropbox / OneDrive / Box | 각자 OAuth REST | **후보**(§8). 제공자 인터페이스만 맞추면 추가 가능 |
| NAS(Synology·QNAP·Nextcloud·일반 서버·Windows 공유) | WebDAV, SMB(smbj) | `NAS_STORAGE.md` |

즉 S3 호환 하나로 Naver·KT·AWS·R2·MinIO 를 모두 덮는다. "다른 클라우드"는 이 제공자에 endpoint 프리셋으로 추가하는 것부터 시작한다.

## 2. 구조 — `RemoteStorage` 제공자

```
feature/gallery ─┐                    ┌─ GoogleDriveStorage (기존 DriveRestRepository 를 감싼다)
feature/drive ───┼─ RemoteStorage ────┼─ S3Storage            (SigV4, ListObjectsV2, Copy+Delete, 멀티파트)
UploadWorker ────┘   (accountId 로 선택) └─ WebDavStorage        (PROPFIND/MKCOL/MOVE/DELETE/PUT — NAS_STORAGE.md)
                     ▲
              RemoteAccountRepository (Room remote_account) + SecretStore (Keystore AES-GCM)
```

```kotlin
// core/data/remote/RemoteStorage.kt
interface RemoteStorage {
    val account: RemoteAccount
    val capabilities: Set<Capability>              // TRASH, RESUMABLE_UPLOAD, RENAME, MOVE, QUOTA, WEB_LINK
    suspend fun about(): RemoteAccountInfo          // 표시 이름·용량
    suspend fun listChildren(parentId: String, pageToken: String? = null, foldersOnly: Boolean = false): RemotePage
    suspend fun createFolder(name: String, parentId: String): RemoteFolder
    suspend fun rename(entryId: String, name: String): RemoteEntry
    suspend fun move(entryId: String, fromParentId: String, toParentId: String): RemoteEntry
    /** TRASH 능력이 있으면 휴지통, 없으면 영구 삭제(UI 가 확인을 띄운다) */
    suspend fun delete(entryId: String)
    suspend fun restore(entryId: String)            // TRASH 능력 있을 때만
    fun uploader(): RemoteUploader
}
```

- `RemoteEntry/RemoteFolder/RemotePage` 는 기존 `DriveEntry/DriveFolder/DrivePage` 를 **이름만 바꾼 것**(typealias 로 시작해 점진 교체). `entryId` 의 의미는 제공자마다 다르다: Drive 는 파일 ID, S3 는 오브젝트 키(폴더는 `prefix/`), WebDAV 는 경로.
- `RemoteUploader` 는 지금 `DriveUploader` 의 세 단계(세션 시작 → 상태 조회 → 이어 올리기)를 인터페이스로 뽑는다. 재개를 지원하지 않는 제공자(WebDAV 단일 PUT)는 `queryStatus` 가 항상 `Expired` 를 돌려 워커가 처음부터 다시 올린다 — 워커 로직은 그대로.
- **Drive 는 어댑터**: `GoogleDriveStorage : RemoteStorage` 가 `DriveRestRepository`/`DriveUploader` 를 위임한다. 기존 코드·테스트를 건드리지 않는다.

## 3. 계정 저장

```kotlin
@Entity(tableName = "remote_account")                // Room v6, AutoMigration 5→6
data class RemoteAccountEntity(
    @PrimaryKey val id: String,                      // UUID
    val kind: String,                                // GOOGLE_DRIVE | S3 | WEBDAV | SMB
    val displayName: String,                         // "Naver Cloud (photos)" 등 사용자가 정함
    val endpoint: String,                            // S3: https://kr.object.ncloudstorage.com, WebDAV: https://nas.local:5006/photos
    val region: String?,                             // S3 서명용 (Naver: kr-standard, KT: kr-central-1 등)
    val bucketOrRoot: String?,                       // S3: 버킷, WebDAV: 루트 경로
    val username: String?,                           // S3: Access Key, WebDAV: 사용자
    val secretRef: String,                           // SecretStore 키 (비밀은 DB 에 두지 않는다)
    val createdAt: Long,
)
```

- **비밀(Secret Key·비밀번호)은 Android Keystore 로 감싼 AES-GCM 으로 암호화해 앱 전용 `SharedPreferences` 에 저장**(`SecretStore`). `security-crypto` 라이브러리는 유지 관리가 끊겨 쓰지 않고, 60줄짜리 헬퍼로 직접 한다. 백업 제외(`android:allowBackup` 의 대상에서 제외 규칙).
- Google Drive 계정은 기존 DataStore(`accountEmail`)에 있다. 단계적으로 `remote_account` 에 `kind=GOOGLE_DRIVE` 행 하나를 **미러**해 목록에 같이 보이게 한다(토큰은 계속 Play 서비스가 관리).
- 기본 업로드 대상: `UserPreferences.uploadAccountId`(null = Google Drive) + 기존 `uploadFolderId/Name`. 자동 백업도 같은 값을 쓴다.

## 4. S3 호환 제공자

- **서명**: AWS Signature V4 를 직접 구현한다(`S3Signer`: canonical request → string to sign → HMAC-SHA256 체인, `x-amz-content-sha256: UNSIGNED-PAYLOAD` 로 스트리밍 PUT 허용). AWS SDK for Kotlin/Java 는 APK 크기·의존성이 커서 쓰지 않는다. 서명은 AWS 공개 테스트 벡터로 단위 테스트.
- **path-style 주소**(`endpoint/bucket/key`)를 기본으로 — Naver·KT·MinIO 가 모두 지원하고 버킷 이름에 점이 있어도 TLS 문제가 없다. virtual-host 는 옵션.
- **목록**: `GET /bucket?list-type=2&prefix=<folder/>&delimiter=/&continuation-token=…` → `CommonPrefixes` 가 폴더, `Contents` 가 파일(자기 자신 `prefix/` 는 제외). XML 은 `XmlPullParser`(플랫폼 내장)로 파싱. 페이지 토큰 = continuation token.
- **폴더 생성**: 길이 0 오브젝트 `prefix/name/` PUT.
- **이름 변경·이동**: `PUT /bucket/newKey` + `x-amz-copy-source: /bucket/oldKey` → `DELETE oldKey`. 폴더는 접두어 아래 오브젝트를 나열해 하나씩 복사·삭제(수백 개면 느림 → 진행 표시, 1단계에서는 파일만 허용하고 폴더 이동은 `MOVE` 능력에서 제외).
- **삭제**: 휴지통 없음 → `Capability.TRASH` 없음 → UI 는 확인 다이얼로그 후 `DELETE`. 폴더는 접두어 전체 삭제(`DeleteObjects` 최대 1000개 배치).
- **업로드**: 5MB 이상은 **멀티파트**(`CreateMultipartUpload` → `UploadPart` 8MB × n → `Complete`). `uploadId` 를 큐의 `sessionUri` 자리에 저장하고, 재개 시 `ListParts` 로 받은 파트를 확인해 이어 올린다 → `RESUMABLE_UPLOAD` 능력. 5MB 미만은 단일 PUT.
- **용량**: S3 는 버킷 용량 API 가 없다 → `about()` 은 이름·endpoint 만.
- **endpoint 프리셋**(추가 화면 드롭다운): Naver Cloud(`https://kr.object.ncloudstorage.com`, `kr-standard`), KT Cloud(`https://ss1.cloud.kt.com:1000` ※ 콘솔에 표시된 S3 endpoint 로 입력, `kr-central-1`), AWS(`https://s3.<region>.amazonaws.com`), Cloudflare R2(`https://<account>.r2.cloudflarestorage.com`, `auto`), MinIO(직접 입력). 프리셋은 endpoint·region 기본값만 채우고 사용자가 고칠 수 있다.

## 5. 화면

- 설정 → **"연결된 저장소"** 섹션: Google Drive 카드(기존) 아래에 계정 목록 + "저장소 추가". 각 행: 종류 아이콘·이름·endpoint, 탭하면 탐색(`RemoteBrowserKey(accountId, folderId)`), ⋮ → 이름 변경·연결 해제(확인).
- **저장소 추가** 화면: 종류 선택(S3 호환 / WebDAV / SMB) → 폼(프리셋, endpoint 또는 host, region 또는 도메인, 버킷/공유 이름, Access Key/사용자, Secret/비밀번호, 표시 이름) → **"연결 테스트"**(루트 폴더 목록 1회: S3 `ListObjectsV2`, WebDAV `PROPFIND depth 1`, SMB `list`) 성공 시 저장.
- 탐색 화면: 기존 `DriveBrowser` 를 `RemoteBrowser` 로 일반화 — ViewModel 이 `RemoteStorage` 를 `accountId` 로 받는다. 능력에 따라 메뉴가 달라진다(TRASH 없으면 "삭제" + 확인, MOVE 없으면 이동 숨김). "이 폴더를 업로드 폴더로 지정"은 `uploadAccountId` 도 함께 저장.
- 갤러리 업로드 배지·"백업 안 됨" 필터: 원장에 `accountId` 를 추가하고 **현재 업로드 대상 계정 기준**으로 판단(다른 계정에 올린 건 그 계정을 골랐을 때 배지). 1단계에서는 원장에 컬럼만 추가하고 판단은 전체 계정 합집합 유지.

## 6. 업로드 파이프라인 변경

- `upload_tasks`·`uploaded_media` 에 `accountId TEXT`(null = Google Drive) 추가(v6, 기본값 null → 기존 행은 Drive).
- `UploadWorker` 는 태스크의 `accountId` 로 `RemoteStorage.uploader()` 를 얻는다. 세션 URI·상태 조회·이어 올리기 흐름은 `RemoteUploader` 인터페이스로 동일. 큐는 계정 순서와 무관하게 순차.
- `EnqueueUploadsUseCase`/`AutoBackupUseCase` 는 `uploadAccountId` 를 태스크에 기록.
- 알림 문구의 "Drive" 는 계정 표시 이름으로.

## 7. 단계

| 단계 | 내용 | 검증 |
|---|---|---|
| M1 | `RemoteStorage`/`RemoteUploader`/모델, `GoogleDriveStorage` 어댑터, `remote_account`(v6) + `SecretStore`, `RemoteAccountRepository`, `StorageRegistry`(accountId → 제공자) | Room 테스트, SecretStore 왕복 테스트 |
| M2 | `S3Signer` + `S3Storage`(list/createFolder/rename·move(파일)/delete/단일 PUT) | 서명 테스트 벡터, MockWebServer 로 요청 형태 검증 |
| M3 | WebDAV(`NAS_STORAGE.md` W1) | MockWebServer |
| M4 | 설정 "연결된 저장소" + 추가 화면(연결 테스트) + `RemoteBrowser` 일반화 | 실기기(지시 시) |
| M5 | 큐·원장 `accountId`, 워커 제공자 선택, S3 멀티파트 재개 | 워커 테스트 |
| M6 | 갤러리 배지·필터의 계정 기준, 자동 백업 대상 계정 | |

## 8. 후보

- Dropbox/OneDrive(OAuth PKCE + 각 REST) — `RemoteStorage` 구현 하나씩.
- 계정별 폴더 매핑(카테고리 → 폴더, `CATEGORIES.md` §10).
- 여러 계정에 동시 백업(태스크 복제).

## 9. 진행 기록

- 2026-09-09 M1(`ee33045`): `RemoteStorage`/`RemoteUploader`, `GoogleDriveStorage`, `remote_account`(Room v6)·`KeystoreSecretStore`·`StorageRegistry`(Hilt `@IntoMap` 팩토리).
- 2026-09-09 M2(`92b4b37`): `S3Signer`(AWS 테스트 벡터 통과)·`S3Storage`(목록·폴더·복사+삭제 이름 변경/이동·삭제·단일 PUT). 폴더 이름 변경/이동은 `Capability.FOLDER_MUTATION` 없음으로 표현.
- 2026-09-09 M3(`0a2c34d`): `WebDavStorage`.
- 2026-09-09 M4·M5: 설정 "연결된 저장소" 섹션(추가·탐색·연결 해제), `AddRemoteAccount` 폼(프리셋·연결 테스트·Keystore 저장), 탐색 화면을 `StorageRegistry`·능력 기반으로 일반화(`DriveBrowserKey.accountId`, 휴지통 없는 저장소는 확인 후 영구 삭제), `uploadAccountId` 업로드 대상, 큐·원장 `accountId`, `UploadWorker` 가 계정별 `RemoteUploader` 사용. Drive 없이 다른 저장소만 연결해도 업로드 가능(`canUpload`).
- 2026-09-09 S3 멀티파트 재개(8MB 파트, ListParts 로 이어 올리기) 구현, 탐색 화면 부제에 계정 이름.
- 2026-09-09 S3 폴더 이름 변경/이동(접두어 아래 오브젝트 복사 후 삭제, `FOLDER_MUTATION`), 자동 백업·알림이 Drive 전제를 벗어남(`canUpload`, 문구 중립화).
- 2026-09-09 M6: 갤러리 배지·"백업 안 됨"·상세보기 업로드 아이콘·자동 백업 중복 판단이 **현재 업로드 대상 계정** 기준(`observeUploadedIds(accountId)`, `uploadedAmong(ids, accountId)`; 중복 정리는 여전히 전체 합집합). 영구 실패 시 `RemoteUploader.abort()` 로 S3 미완료 멀티파트 삭제.
- 2026-09-09 WebDAV 자체 서명 인증서 지문 고정(Room v7 `certSha256`).
- 2026-09-09 SMB(W2) 제공자 추가(`NAS_STORAGE.md` §5) — 네 번째 `RemoteAccountKind`. 재개 업로드를 지원하는 첫 비-Drive 제공자.
- 2026-09-09 업로드 목록 행 부제에 계정 이름(`계정 · 상태`) 표시(`UploadQueueUiState.accountNames`; 해제된 계정은 "연결 해제된 저장소"). 계정 추가 폼에서 종류를 바꾸면 버킷/공유 이름을 비운다.
- 2026-09-09 S3 폴더 이름 변경·이동·삭제 진행 표시: `ReportsMutationProgress`(선택 인터페이스, `StateFlow<MutationProgress?>`) 를 `S3Storage` 가 구현 — 접두어 아래 키를 먼저 모아 전체 개수를 알고 오브젝트마다 갱신, 끝나면 null. 탐색 화면 하단 진행바가 "n / total 개 처리 중" 으로 바뀐다(다른 제공자는 불확정 진행바 그대로).
- 2026-09-09 WebDAV Digest 인증(`NAS_STORAGE.md` §2).
- 2026-09-09 `Capability.SEARCH`(Drive 만)·`Capability.DOWNLOAD`(네 제공자) 추가 — `DRIVE_FILE_CRUD.md` §8·§9.
- 2026-09-09 설정 "연결된 저장소" 행에 용량(`about()`, QUOTA 능력 — WebDAV `quota-used/available-bytes`) 표시. 계정 목록이 바뀔 때 한 번씩 읽고 실패는 조용히 건너뛴다.
- 2026-09-09 갤러리 선택 상단바 ⋮ "다른 저장소로 업로드"(저장소가 둘 이상일 때) — 이번 배치만 고른 계정으로(`EnqueueUploadsUseCase.toAccount`; 기본 대상과 같으면 설정 폴더, 아니면 루트), 설정의 기본 대상은 바뀌지 않는다. 기본 대상에 ✓ 표시.
- 남은 것: Dropbox/OneDrive.
