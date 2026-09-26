# 진행

## 원장을 계정별로

- [x] 스키마 12 — 키 `(mediaId, destination)`, `destination` 인덱스
- [x] 수동 마이그레이션 `MIGRATION_11_12` — Drive 행은 `drive:`(주인 미정), 다른 저장소는 `remote:<accountId>`
- [x] `UploadLedgerRepository` 가 `accountId` → `destination` 변환(연결 이메일을 읽어서)
- [x] 관찰 Flow 가 연결 계정이 바뀌면 다시 흐른다(`flatMapLatest`)
- [x] 옛 기록은 처음 보이는 연결 계정이 가져간다(`claimUnclaimed`, 프로세스당 1회)

## 공유 받은 폴더

- [x] `DriveRepository.listSharedFolders` — `sharedWithMe = true`
- [x] 보기 폴더 피커 최상위에 "공유 문서함", 목록 자리는 고를 수 없게(`unpickableIds`)
- [x] `ViewFolderPicker` 로 분리 — Route 복잡도 한계

## 검증

- [x] 단위: `Migration11To12Test`(5) — v11 DB 를 스키마 파일로 만들고 **앱과 같은 경로로** 열어 Room 의 스키마 대조까지
- [x] 단위: `UploadedMediaDaoTest`(8), `UploadLedgerRepositoryTest`(8) — A↔B 전환·로그아웃·두 계정에 같은 사진
- [x] detekt / testDebugUnitTest / lintDebug
- [x] 기기 S23+: 10 → 11 → 12, 2,342건 보존, 옛 기록 귀속, 새 업로드 기록 (LED-01~04)
- [x] 기기 S23+: 공유 문서함 → 공유 폴더 추가 → 안이 보임 (SHR-01~06)
- [ ] 기기 Flip 4: 백업 5,985개가 그대로인지 (LED-07) — 잠금 해제 대기
- [ ] 계정 전환 실기기 확인 (LED-05·06) — 다른 계정에 앱 권한이 생기므로 사용자 확인 뒤

## 테스트하다 고친 것

| | 증상 | 원인·조치 |
|---|---|---|
| 1 | `MigrationTestHelper` 가 스키마 파일을 못 찾는다 | Robolectric 은 앱 변형의 자산만 읽는다(`mergeDebugAssets`). 디버그 APK 에 스키마를 싣는 대신 테스트가 `schemas/11.json` 으로 v11 DB 를 직접 만들고 Room 으로 연다 — 실제 기기와 같은 경로라 더 낫다 |
| 2 | Route 가 CyclomaticComplexity 15 | 공유 문서함 분기가 더해져서. `ViewFolderPicker.kt` 로 떼어냈다 |
