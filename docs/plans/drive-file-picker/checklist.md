# 체크리스트

설계: `../../DRIVE_FILE_SCOPE.md` (측정 결과와 그에 따른 화면 설계)

## 1단계 — 피커 (측정 완료, 2026-09-24)
- [x] `beginFolderPick()` / `completeFolderPick()` — `PICKER_OAUTH_TRIGGER`
- [x] 결과 형식 확인 — `tokenResponseParams` 의 `picked_file_ids` (콤마 구분)
- [x] 폴더 직접 선택은 **안 된다**는 것 확인 (DRV-P3)
- [x] 고른 파일의 부모에 쓸 수 있다는 것 확인 (DRV-P5)
- [ ] 측정용 `probePickedParent` 걷어내기

## 2단계 — 지정 폴더를 기억한다
- [ ] `PickedFolder(id, alias)` — DataStore 에 직렬화
- [ ] 추가: 피커 → `parents[0]` → 별칭 입력 대화상자
- [ ] 이미 있는 폴더를 또 고르면 별칭만 갱신
- [ ] 지정 해제

## 3단계 — Drive 화면
- [ ] `GoogleDriveStorage.listChildren(rootId)` → 지정 폴더 + 기본 폴더
- [ ] 기본 폴더 `Easy Gallery` 를 없으면 만든다
- [ ] 루트에 "Drive 에서 폴더 고르기" 버튼 + 왜 파일을 고르게 하는지 한 줄 설명
- [ ] 폴더 안 빈 상태 문구 — "이 앱으로 올린 파일만 보입니다"
- [ ] 지정 폴더는 이름 변경·삭제를 막는다(권한이 없다)

## 4단계 — 업로드에 표식
- [ ] 올릴 때 `appProperties = {"egTarget": alias}`
- [ ] 재설치 복구: 표식 있는 파일의 `parents` + `egTarget` 으로 목록 되살리기

## 5단계 — 권한 정리
- [ ] `setOptOutIncludingGrantedScopes(true)` — 로그인·피커 모두 (SS-10)
- [ ] `AuthorizationRequiredException` 에 `PendingIntent` 를 실어 재동의로 잇는다 (SS-11)
- [ ] 설정 화면 안내 문구 — "Drive 전체 접근 권한을 요청합니다" 는 이제 거짓

## 6단계 — 검증
- [ ] detekt + testDebugUnitTest
- [ ] 기기: 폴더 지정 → 그 폴더로 업로드 → Drive 앱에서 확인
- [ ] 확인 못 한 것은 `docs/manual-tests/02-google-drive.md` 에
