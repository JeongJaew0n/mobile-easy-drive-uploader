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
- [x] 기기 S23+: B = nhnpopt0 로 끝까지 (GST-05~11, 13) — 올리기·공유·A 에서 보기·다시 올리면 건너뛰기·배너·계정 설정
- [x] 누구의 폴더인지(§6) — 루트 부제, 폴더 안 부제, 공유 폴더 소유자 (OWN-01~05)
- [ ] Flip 4 (GST-14) — nhnpopt0 이 기기에 없어 보류

## 테스트하다 고친 것

| | 증상 | 원인·조치 |
|---|---|---|
| 1 | 선택 창을 닫으면 "Google 인증 실패 (16)" | Play 서비스가 닫힘을 결과 인텐트에 status 16 으로 담아 준다. 저장소가 `CANCELED` 를 취소로 거르고, Route 도 `RESULT_OK` 가 아니면 부르지 않는다 |
| 2 | Route 복잡도 16 | 이벤트 처리를 `GalleryEvents.kt` 로 떼어냈다 |
| 3 | A 의 "Easy Gallery" 와 B 의 "Easy Gallery" 를 가를 수 없다(사용자) | 이름에 이메일을 붙였는데 목록에서 잘렸다. 소유자를 따로 받아 부제로 보인다(spec §6) |
| 4 | **B 의 폴더가 A 의 앱 폴더로 잡혔다** | `ensureAppRootFolder` 가 표식만 보고 찾았다. A 가 읽기 권한을 켜면 B 가 공유한 폴더(같은 표식)도 보인다. `'me' in owners` 를 더했다. 업로드 폴더가 지정돼 있지 않았다면 A 의 업로드가 B 의 폴더로 향했을 버그 |
