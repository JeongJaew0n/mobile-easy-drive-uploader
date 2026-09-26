# 진행

## 코드 — 끝

- [x] `DriveApi.createPermission`, `DriveRepository.shareForReading`
- [x] `RemoteAccount.guestDriveId/guestEmailOf` — `google:<이메일>`
- [x] 원장: `google:<B>` → `drive:<B>` (B 가 나중에 주 계정이 돼도 이어진다)
- [x] `GuestTokenProvider` — A 와 따로, `setAccount(B)` 로 그때그때
- [x] `GuestDriveFactory` — 기존 Drive 코드 + B 토큰. A 의 OkHttp 에서 갈라 연결 풀을 같이 쓴다
- [x] `StorageRegistry` 가 `google:` 접두어를 B 로 보낸다
- [x] `AuthRepository.beginGuestPick/completeGuestPick` — 선택 창 강제, **A 의 토큰 캐시는 건드리지 않는다**
- [x] `StartGuestUploadUseCase` — 폴더 → 공유 → 보기 폴더 → 큐(`EnqueueUploadsUseCase.toFolder`)
- [x] 워커: B 의 인증 실패는 그 항목만 실패(A 를 세우지 않는다), 끝나면 알림 + `guestCleanupEmail`
- [x] 갤러리: 선택 ⋮ 항목, 선택 창 런처, 끝난 뒤 배너

## 검증

- [x] 단위: `StartGuestUploadUseCaseTest`(8), `UploadLedgerRepositoryTest` +3, `RemoteAccountGuestIdTest`(2), `UploadWorkerTest` +3
- [x] detekt / testDebugUnitTest / lintDebug
- [x] 기기 S23+: 메뉴, 선택 창 강제, A 거절, 취소 (GST-01~04)
- [ ] 기기: 실제 B 로 끝까지 (GST-05~11) — 어느 계정으로 할지 사용자 확인 뒤

## 테스트하다 고친 것

| | 증상 | 원인·조치 |
|---|---|---|
| 1 | 선택 창을 닫으면 "Google 인증 실패 (16)" | Play 서비스가 닫힘을 결과 인텐트에 status 16 으로 담아 준다. 저장소가 `CANCELED` 를 취소로 거르고, Route 도 `RESULT_OK` 가 아니면 부르지 않는다 |
| 2 | Route 복잡도 16 | 이벤트 처리를 `GalleryEvents.kt` 로 떼어냈다 |
