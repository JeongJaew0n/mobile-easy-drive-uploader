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
- [ ] 기기 확인 — `docs/manual-tests/02-google-drive.md` DRV-31~45 (15건)

## 사람이 해야 하는 일 — **먼저 해야 기기 확인이 가능하다**

- [ ] GCP OAuth 동의 화면에 `https://www.googleapis.com/auth/drive.readonly` 등록
      (등록 전에는 "볼 수 있는 폴더 추가" 가 authorize 실패로 떨어진다)
