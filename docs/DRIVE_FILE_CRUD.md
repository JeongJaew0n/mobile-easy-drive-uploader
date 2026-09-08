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

- Drive → 기기 다운로드(가져오기): `alt=media` 스트리밍을 `MediaStore` 로 저장하는 WorkManager 작업, 진행 알림, 원장에 `driveFileId` 가 있으면 "이미 기기에 있음" 표시.

## 7. 다중 선택 (2026-09-09 추가)

- 행을 **길게 누르면** 선택 모드(`DriveBrowserUiState.selectedIds`). 선택 중에는 탭이 토글, 행 앞이 체크박스, ⋮ 숨김, 뒤로 가기가 선택 해제. 상단바는 `DriveSelectionTopBar`("n개 선택" · 전체 선택 · 이동(MOVE 능력) · 삭제).
- **일괄 처리는 순차** — Drive REST 에 batch 엔드포인트가 있지만(multipart/mixed) 다른 제공자(S3·WebDAV·SMB)에는 없어 `RemoteStorage` 표면을 그대로 쓴다. `mutateBatch`: 선택 항목을 낙관적으로 빼고 하나씩 호출, 진행은 `mutationProgress`(n / total) 로, **실패한 항목만 목록에 되살리고** `BatchFailed(count)` 를 덧붙인다(전부 되돌리지 않는다 — 이미 옮겨진 것을 되돌릴 방법이 없다).
- 휴지통 있는 저장소: 확인 없이 휴지통 → 스낵바 "n개를 휴지통으로 옮겼습니다 · 실행 취소"(`restoreAll` 순차 복원). 없는 저장소: "n개 항목 삭제" 확인 다이얼로그 후 영구 삭제.
- 이동 대상이 선택된 폴더 자신이면 거부(하위 폴더로의 이동은 서버가 거부하고 그 항목만 실패로 남는다).
- 테스트: `DriveBrowserViewModelTest` 일괄 휴지통(성공 1·실패 1), 자기 자신으로 이동 거부·전체 선택.

## 8. 검색 (2026-09-09 추가)

- `Capability.SEARCH`(Drive 만) → 상단바 검색 아이콘 → `DriveSearchTopBar`(텍스트 필드가 제목 자리, 자동 포커스). 입력마다 `search(query)`, ViewModel 이 350ms 디바운스 후 `RemoteStorage.search` — Drive 는 `q = name contains '<이스케이프>' and trashed = false`, 정렬 `folder,name_natural`, 페이징은 폴더 목록과 같은 `fetchPage`. 빈 문자열은 안내 문구, 결과 없음은 "일치하는 파일이 없습니다".
- 검색은 **Drive 전체**(현재 폴더 한정 아님) — Drive 의 `contains` 는 접두어 토큰 매칭이라 "IMG_2026" 같은 앞부분 검색에 강하고 중간 문자열은 놓칠 수 있다(Drive 제약).
- 검색 결과에는 부모 폴더 정보가 없어(`fields` 에 parents 를 넣어도 다중 부모·공유 항목이 있어 `removeParents` 가 애매) **이동은 숨긴다**(행 ⋮·다중 선택 상단바 모두). 이름 변경·휴지통·열기는 그대로. 하단 "업로드 폴더로 지정"도 숨김.
- 뒤로 가기는 검색만 종료하고 원래 폴더를 다시 읽는다. S3·WebDAV·SMB 는 아이콘이 나오지 않는다(접두어 목록만 있음 — 폴더 내 필터는 후보).
- 테스트: `DriveRestRepositoryTest` 검색 쿼리(공백 trim·따옴표 이스케이프), `DriveBrowserViewModelTest` 디바운스·결과·종료 복귀.
