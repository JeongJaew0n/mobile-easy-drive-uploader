# 체크리스트

설계: `../../DRIVE_FILE_SCOPE.md` (측정 결과와 그에 따른 화면 설계)

## 1단계 — 피커 (측정 완료, 2026-09-24)
- [x] `beginFolderPick()` / `completeFolderPick()` — `PICKER_OAUTH_TRIGGER`
- [x] 결과 형식 확인 — `tokenResponseParams` 의 `picked_file_ids` (콤마 구분)
- [x] 폴더 직접 선택은 **안 된다**는 것 확인 (DRV-P3)
- [x] 고른 파일의 부모에 쓸 수 있다는 것 확인 (DRV-P5)
- [x] 측정용 `probePickedParent` 걷어내기 (`parentOf` 로 정리)

## 2단계 — 지정 폴더를 기억한다
- [x] `PickedFolder(id, alias)` — DataStore 에 직렬화
- [x] 추가: 피커 → `parents[0]` → 별칭 입력 대화상자 (DRV-P7)
- [x] 이미 있는 폴더를 또 고르면 별칭만 갱신
- [x] 지정 해제

## 3단계 — Drive 화면
- [x] `GoogleDriveStorage.listChildren(rootId)` → 지정 폴더 + 기본 폴더 (DRV-P8)
- [x] 기본 폴더 `Easy Gallery` 를 없으면 만든다
- [x] 설정에 "Drive 폴더 지정" + 왜 파일을 고르게 하는지 설명 (루트가 아니라 설정에 뒀다 — 계정 설정과 함께 보는 편이 자연스럽다)
- [x] 폴더 안 빈 상태 문구 — "이 앱으로 올린 파일만 보입니다"
- [x] 지정 폴더는 이름 변경·삭제를 막는다 (DRV-P9, P10)

## 4단계 — 업로드에 표식
- [x] 올릴 때 `appProperties = {easyGallery: 1, egTarget: alias}`
- [ ] 재설치 복구: 표식 있는 파일의 `parents` + `egTarget` 으로 목록 되살리기

## 5단계 — 권한 정리
- [x] `setOptOutIncludingGrantedScopes(true)` — 로그인·피커 모두 (DRV-P12)
- [ ] `AuthorizationRequiredException` 에 `PendingIntent` 를 실어 재동의로 잇는다 (SS-11)
- [x] 설정 화면 안내 문구 갱신

## 6단계 — 검증
- [x] detekt + testDebugUnitTest
- [x] 기기: 폴더 지정 → 그 폴더로 업로드 → Drive 앱에서 확인 (DRV-P7~P12 전부 통과)
- [x] 결과는 `docs/manual-tests/02-google-drive.md` DRV-P1~P12

## 남은 것
- [ ] 재설치 복구 — 표식 있는 파일의 `parents` + `egTarget` 으로 지정 폴더 되살리기 (`DRIVE_FILE_SCOPE.md` §6)
- [x] SS-11 — 권한이 끊겼을 때 앱 안에서 재동의로 잇기 (기기 확인은 남음)
- [ ] 업로드 워커가 같은 예외를 만나면? UI 가 없어 재동의를 띄울 수 없다 — 알림으로 안내할지 정해야 한다
