# 진행

## 코드 — 끝

- [x] `ViewFolder` 모델, DataStore 저장(`viewFolders`, `driveViewScopeGranted`)
- [x] `GoogleAuthRepository.requestedScopes()` — 옵트인 시 `drive.readonly` 를 함께 요청
- [x] `beginViewScopeConsent` / `completeViewScopeConsent` — `grantedScopes` 확인 후에만 플래그 ON
- [x] 계정 연결 해제 시 보기 폴더·플래그 함께 삭제
- [x] `DriveEntry.readOnly`, Drive 루트 목록에 보기 폴더 추가(맨 뒤)
- [x] `DriveBrowserKey.readOnly` 전파, `Capability` 에서 변경 계열 제거
- [x] 루트 하단 "볼 수 있는 폴더 추가" 버튼 → 동의 → `DriveFolderPickerSheet`
- [x] 행에 "보기 전용" 표시, ⋮ 에 "목록에서 제거"
- [x] 보기 전용 폴더에서 업로드 폴더 지정·새 폴더 버튼 숨김

## 검증

- [x] detekt / `testDebugUnitTest` / `lintDebug` 통과
- [x] 단위 테스트: `EntryMenuTest`, `GoogleDriveStorageRootTest`, 읽기 전용 능력 제거
- [x] 기기 확인 — `docs/manual-tests/02-google-drive.md` DRV-31~50 (18건 ✅, 2건 보류)
  - DRV-42(동의 화면에서 읽기 권한만 거부): 이미 허락한 뒤라 계정에서 액세스를 지워야 재현된다
  - DRV-45(계정 연결 해제): 다시 연결하려면 동의를 처음부터 받아야 해서 미뤘다

## 사람이 해야 하는 일 — **먼저 해야 기기 확인이 가능하다**

- [x] GCP OAuth 동의 화면에 `https://www.googleapis.com/auth/drive.readonly` 등록 (2026-09-26 완료)

## 기기 확인에서 나온 것 — 모두 고쳤다

| | 증상 | 원인 |
|---|---|---|
| DRV-37 | 보기 전용 폴더에 새 폴더 버튼이 남았다 | 버튼이 `isPickedRoot` 만 봤다 |
| DRV-46 | 검색 결과의 남의 파일에 이름 변경·삭제가 떴다 | readonly 로 검색 범위가 Drive 전체가 됐는데 메뉴는 그대로 |
| DRV-48 | 이미 목록에 있는 폴더를 추가하면 **앱이 죽었다** | 루트에 같은 id 가 두 번 → LazyColumn |
| DRV-49 | 보기 전용 빈 폴더가 "앱으로 올린 것만 보입니다" 라고 했다 | 읽기 권한에서는 거짓말 |

## 동영상

- [x] `media3-datasource-okhttp` + 인증 붙은 `DataSource.Factory`
- [x] 사진·영상을 한 자리에서 여는 `DrivePreview`
- [x] 기기 확인(DRV-50) — 스트리밍으로 재생, 계정 선택 없음
