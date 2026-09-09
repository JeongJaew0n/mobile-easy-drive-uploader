# Google Drive 파일 목록 CRUD

작성 2026-09-09. 지금 Drive 탐색 화면은 목록(R)·새 폴더(C)·파일 열기·업로드 폴더 지정만 된다. 여기에 **이름 변경·이동(U)·휴지통(D, 실행 취소 가능)** 을 붙여 파일과 폴더 모두 다룰 수 있게 한다. 구현 전 설계.

## 1. 범위

| 동작 | 대상 | 방법 | 비고 |
|---|---|---|---|
| 생성 | 폴더 | 기존 `files.create`(mimeType folder) | 이미 있음 |
| 생성 | 파일 | 갤러리 → 업로드 큐(기존) | Drive 화면에서 직접 올리는 건 범위 밖 |
| 조회 | 폴더·파일 | 기존 `files.list`(페이징, 폴더 먼저) | 이미 있음 |
| **이름 변경** | 폴더·파일 | `PATCH files/{id}` `{ "name": … }` | 확장자는 사용자가 쓴 그대로(Drive 는 확장자에 의미를 두지 않음) |
| **이동** | 폴더·파일 | `PATCH files/{id}?addParents=X&removeParents=Y` | 폴더는 자기 자신·하위로 이동 불가(선택 UI 에서 제외) |
| **휴지통** | 폴더·파일 | `PATCH files/{id}` `{ "trashed": true }` | 30일 뒤 Drive 가 비움. 스낵바 "실행 취소" → `trashed: false` |
| 완전 삭제 | — | `DELETE files/{id}` | **이번 범위 밖**. 복구 불가 동작을 모바일 탐색 화면에 두지 않는다. Drive 휴지통에서 하도록 안내 |
| 다운로드(Drive → 기기) | 파일 | `GET files/{id}?alt=media` → MediaStore | **후보**(§6). 업로드의 역방향이라 별도 워커·문서 필요 |

## 2. REST 추가 (`DriveApi`)

```kotlin
@PATCH("drive/v3/files/{fileId}")
suspend fun updateFile(
    @Path("fileId") fileId: String,
    @Body patch: DriveFilePatch,                 // name / trashed 중 바꿀 것만 (null 은 직렬화 안 함)
    @Query("addParents") addParents: String? = null,
    @Query("removeParents") removeParents: String? = null,
    @Query("fields") fields: String = "id,name,mimeType,parents,trashed,modifiedTime,size,webViewLink",
): DriveFileDto
```

기존 `Json { explicitNulls = false; encodeDefaults = false }` 라 `DriveFilePatch(name = "x")` 는 `{"name":"x"}` 로만 나간다. 이동은 본문 없이 쿼리만 — Drive 는 빈 JSON 본문(`{}`)을 허용한다.

## 3. 저장소 (`DriveRepository`)

```kotlin
suspend fun rename(fileId: String, name: String): DriveEntry
suspend fun move(fileId: String, fromParentId: String, toParentId: String): DriveEntry
suspend fun setTrashed(fileId: String, trashed: Boolean)
suspend fun listChildren(parentId: String, pageToken: String? = null, foldersOnly: Boolean = false): DrivePage
```

`foldersOnly` 는 이동 대상 폴더 선택기가 쓴다(`mimeType = folder` 조건 추가). 나중에 다른 클라우드(`MULTI_CLOUD.md`)의 `StorageProvider` 가 같은 다섯 동작(list/createFolder/rename/move/trash)을 갖도록 이름을 맞춘다.

## 4. 화면 (`feature/drive`)

- 각 행 오른쪽에 ⋮ → 메뉴: **열기**(파일만) · **이름 변경** · **이동** · **휴지통으로 이동**.
- 이름 변경: 다이얼로그(현재 이름 채움, 빈 이름 불가, 같은 이름이면 무시).
- 이동: `DriveFolderPickerSheet` — 내 드라이브부터 폴더만 나열, 탭하면 들어가고 상단 경로(breadcrumb)로 되돌아온다. 하단 "여기로 이동". 옮기는 항목이 폴더면 자기 자신은 목록에서 빼고, 현재 폴더(= 원래 위치)면 버튼 비활성.
- 휴지통: 확인 없이 즉시(복구 가능하니까). 목록에서 빠지고 스낵바 "'a.jpg'를 휴지통으로 옮겼습니다 · 실행 취소". 실행 취소 → `setTrashed(false)` 후 원래 정렬 위치에 다시 삽입.
- 낙관적 갱신: 이름 변경·이동·휴지통은 API 호출과 동시에 목록을 바꾸고, 실패하면 되돌리고 오류 스낵바. `isMutating` 동안 다른 편집 비활성(기존 새 폴더와 같은 규칙).
- 애니메이션: 목록은 이미 `animateItem` — 빠지고 들어오는 항목이 미끄러진다. 새 컴포저블 추가 없음.

## 5. 검증

- 단위: `DriveRestRepositoryTest` 에 rename(PATCH 본문 `{"name":…}`), move(`addParents/removeParents` 쿼리, 빈 본문), setTrashed(`{"trashed":true}`), foldersOnly 쿼리. 새 `DriveBrowserViewModelTest`(가짜 저장소): 이름 변경 낙관적 반영·실패 복구, 휴지통 → 목록 제거 → 실행 취소 → 재삽입 정렬, 이동 → 목록 제거.
- 실기기(사용자 지시 시, GCP 등록 후): `manual-tests/02-google-drive.md` DRV-13~16.

## 6. 후보

- (2026-09-09 구현) 원장 기반 "이 기기에서 올림" 표시, S3·WebDAV·SMB 폴더 내 로컬 이름 필터 — §8.

## 6.1 당겨서 새로고침 (2026-09-09 추가)

- 목록을 아래로 당기면 `PullToRefreshBox`(M3) 가 `refresh()` 를 부른다. 목록이 있으면 `isRefreshing`(목록은 그대로, 상단 인디케이터), 비어 있으면 기존 전체 로딩. 상단바 새로고침 버튼은 그대로 둔다.

## 7. 다중 선택 (2026-09-09 추가)

- 행을 **길게 누르면** 선택 모드(`DriveBrowserUiState.selectedIds`). 선택 중에는 탭이 토글, 행 앞이 체크박스, ⋮ 숨김, 뒤로 가기가 선택 해제. 상단바는 `DriveSelectionTopBar`("n개 선택" · 전체 선택 · 이동(MOVE 능력) · 삭제).
- **일괄 처리는 순차** — Drive REST 에 batch 엔드포인트가 있지만(multipart/mixed) 다른 제공자(S3·WebDAV·SMB)에는 없어 `RemoteStorage` 표면을 그대로 쓴다. `mutateBatch`: 선택 항목을 낙관적으로 빼고 하나씩 호출, 진행은 `mutationProgress`(n / total) 로, **실패한 항목만 목록에 되살리고** `BatchFailed(count)` 를 덧붙인다(전부 되돌리지 않는다 — 이미 옮겨진 것을 되돌릴 방법이 없다).
- 휴지통 있는 저장소: 확인 없이 휴지통 → 스낵바 "n개를 휴지통으로 옮겼습니다 · 실행 취소"(`restoreAll` 순차 복원). 없는 저장소: "n개 항목 삭제" 확인 다이얼로그 후 영구 삭제.
- 이동 대상이 선택된 폴더 자신이면 거부(하위 폴더로의 이동은 서버가 거부하고 그 항목만 실패로 남는다).
- **버그 수정(2026-09-09, 2차 감사)**: (1) 배치가 도는 동안 당겨서 새로고침하면 목록이 서버본으로 갈린 뒤 실패 항목이 덧붙어 **같은 키가 두 번 들어가 목록이 크래시**했다 → 변경 중 새로고침을 막고, 재삽입은 중복을 걸러 넣는다(`plusMissing`). (2) 목록을 다시 읽으면 `selectedIds` 를 현재 항목으로 좁힌다(전에는 "5개 선택" 을 띄우고 셋만 지웠다). (3) 폴더를 **자기 하위로** 옮기는 것을 막는다(경로 기반 제공자, `isUnder`). (4) `createFolder` 도 다른 변경과 같은 잠금을 쓴다. (5) 일괄 작업 중에는 제공자의 오브젝트 단위 진행을 무시해 진행바가 두 척도를 오가지 않는다.
- 테스트: `DriveBrowserViewModelTest` 일괄 휴지통(성공 1·실패 1), 자기 자신으로 이동 거부·전체 선택, 배치 중 새로고침 중복 방지, 하위 폴더 이동 거부, 로컬 필터 중 삭제 후 재조회.

## 8. 검색 (2026-09-09 추가)

- `Capability.SEARCH`(Drive 만) → 상단바 검색 아이콘 → `DriveSearchTopBar`(텍스트 필드가 제목 자리, 자동 포커스). 입력마다 `search(query)`, ViewModel 이 350ms 디바운스 후 `RemoteStorage.search` — Drive 는 `q = name contains '<이스케이프>' and trashed = false`, 정렬 `folder,name_natural`, 페이징은 폴더 목록과 같은 `fetchPage`. 빈 문자열은 안내 문구, 결과 없음은 "일치하는 파일이 없습니다".
- 검색은 **Drive 전체**(현재 폴더 한정 아님) — Drive 의 `contains` 는 접두어 토큰 매칭이라 "IMG_2026" 같은 앞부분 검색에 강하고 중간 문자열은 놓칠 수 있다(Drive 제약).
- 검색 결과에는 부모 폴더 정보가 없어(`fields` 에 parents 를 넣어도 다중 부모·공유 항목이 있어 `removeParents` 가 애매) **이동은 숨긴다**(행 ⋮·다중 선택 상단바 모두). 이름 변경·휴지통·열기는 그대로. 하단 "업로드 폴더로 지정"도 숨김.
- 뒤로 가기는 검색만 종료한다. 로컬 필터 중에 지우거나 옮긴 게 있으면 보관본이 낡았으므로 그대로 되돌리지 않고 다시 읽는다(전에는 지운 항목이 되살아났다). S3·WebDAV·SMB 는 아이콘이 나오지 않는다(접두어 목록만 있음 — 폴더 내 필터는 후보).
- **SEARCH 가 없는 저장소(S3·WebDAV·SMB)** 도 같은 아이콘이 보이고, 현재 폴더 목록을 **로컬에서 이름으로 거른다**(대소문자 무시, 즉시). 원격 호출 없음, 힌트 "이 폴더에서 이름으로 찾기". 같은 폴더라 이동도 그대로 가능(`isRemoteSearchResult` 로 구분). 종료하면 보관한 목록으로 복귀(다시 읽지 않음).
- 행 세부에 **"이 기기에서 올림"** — 업로드 원장(`uploaded_media.driveFileId`, 계정별)에 있는 원격 ID 면 표시. 기기에서 지웠는지는 모르니 "기기에 있음"이라 하지 않았다.
- 테스트: `DriveRestRepositoryTest` 검색 쿼리(공백 trim·따옴표 이스케이프), `DriveBrowserViewModelTest` 디바운스·결과·종료 복귀, 로컬 필터·원장 표시.

## 9. 기기에 저장(다운로드) (2026-09-09 추가)

- `Capability.DOWNLOAD` + `RemoteStorage.openDownload(entryId): InputStream` — Drive `GET files/{id}?alt=media`(`@Streaming`), S3 `GET` 오브젝트(SigV4), WebDAV `GET`, SMB 는 연결을 잡은 채 `File.inputStream` 을 돌려주고 스트림을 닫을 때 파일·공유·세션·연결을 함께 닫는다. 네 제공자 모두 지원.
- 행 ⋮ "기기에 저장"(파일만), 다중 선택 상단바 ⬇(폴더는 건너뜀). 파일마다 `DownloadWorker`(WorkManager, 유니크 `download-<entryId>` KEEP, 네트워크 필요, 지수 백오프 15s, 3회) — 큐(Room)는 두지 않았다: 업로드처럼 수천 장을 한 번에 내리는 흐름이 아니고 WorkManager 가 재시도·순서를 맡는다.
- 파일 이름의 `/`·제어 문자는 `_` 로 바꾸고(비면 대체 이름), 제공자가 `application/octet-stream` 을 주면 확장자로 다시 찾는다 — 그러지 않으면 HEIC·DNG 가 Download 폴더로 들어가 갤러리에 보이지 않는다.
- 저장은 `MediaStoreSaver`: `IS_PENDING=1` 로 삽입 → 스트림 복사 → `IS_PENDING=0`. 이미지 `Pictures/Easy Gallery`, 영상 `Movies/Easy Gallery`, 그 외 `Download/Easy Gallery`. 실패하면 만든 항목을 지운다. 갤러리(MediaStore 관찰)에 자동으로 나타난다.
- 알림은 업로드 채널을 공유(포그라운드 ID 1004, 결과 1005). 진행률은 워커가 `StateFlow` 로 받아 별도 코루틴에서 `setForeground` — 저장기 콜백은 블로킹 I/O 스레드라 suspend 를 못 부른다.
- 재시도 판단: `RemoteStorageException` 은 5xx/코드 없음이면 재시도, 4xx 는 즉시 실패. `IOException` 은 재시도. 미지원 저장소(`UnsupportedOperationException`)는 재시도하지 않는다. Drive 는 Retrofit 이 비2xx 를 `HttpException` 으로 던지므로 저장소에서 상태 코드를 살려 감싼다(그러지 않으면 5xx·429 가 재시도 없이 영구 실패했다).
- **버그 수정(2026-09-09)**: 워커가 신속 작업(`setExpedited`)인데 `getForegroundInfo` 를 구현하지 않아, API 30 이하(minSdk 29)에서 WorkManager 가 시작 직전에 워커를 실패시켰다 — 구현을 추가했다. 알림 ID 도 파일 키로 흩었다(고정 ID 를 쓰면 동시에 여러 개를 받을 때 한 워커가 끝나며 다른 워커의 진행 알림까지 지운다).
- 테스트: `DriveRestRepositoryTest` `alt=media`, `DriveBrowserViewModelTest` 선택 다운로드(폴더 제외·선택 해제·이벤트). MediaStore 저장은 실기기 DRV-23.
