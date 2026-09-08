# 11 · 다중 클라우드 / NAS (RemoteStorage)

설계: `../MULTI_CLOUD.md`, `../NAS_STORAGE.md`. 2026-09-09 M1~M5 구현. 실제 계정(Naver Cloud·KT Cloud·NAS)이 필요해 사용자가 직접 확인한다.

| ID | 시나리오 | 기대 결과 | 상태 | 메모 |
|---|---|---|---|---|
| RS-01 | v5 설치 위에 업데이트 | 크래시 없음, `remote_account` 표 생성, 큐·원장 `accountId` 컬럼(null) | ⬜ | `AutoMigration(5→6)` |
| RS-02 | 설정 → 연결된 저장소 → 저장소 추가 → S3 호환, 프리셋 Naver | endpoint `https://kr.object.ncloudstorage.com`, 리전 `kr-standard` 자동 채움 | ⬜ | |
| RS-03 | Access/Secret Key·버킷 입력 → 연결 테스트 | "연결 성공". 키가 틀리면 "연결 실패: … (403)" | ⬜ | `ListObjectsV2` 1회 |
| RS-04 | 저장 → 목록에 행, 탭 → 탐색 | 버킷 루트의 접두어가 폴더로, 오브젝트가 파일로. 폴더 먼저·이름순 | ⬜ | S3 는 페이지 200개 |
| RS-05 | S3 탐색에서 새 폴더·이름 변경·이동·삭제(파일·폴더) | 폴더 이름 변경/이동은 아래 오브젝트를 전부 복사 후 삭제(개수에 비례해 느림, 진행 표시 없음). 삭제는 확인 다이얼로그 후 즉시(휴지통 없음) | ⬜ | |
| RS-06 | "이 폴더를 업로드 폴더로 지정" → 갤러리에서 사진 업로드 | 설정 "업로드 폴더" 행에 "계정 · 폴더", 해당 버킷 접두어에 오브젝트 생성, 배지 표시 | ⬜ | 단일 PUT, 재개 없음 |
| RS-07 | KT Cloud 프리셋 | endpoint 는 콘솔의 S3 API 주소로 고쳐 입력, 리전 `kr-central-1` | ⬜ | endpoint 프리셋은 기본값일 뿐 |
| RS-08 | Google 로그인 없이 S3 만 연결한 상태에서 업로드 | 로그인 요구 없이 큐 등록·업로드 | ⬜ | `UserPreferences.canUpload` |
| RS-09 | 계정 ⋮ → 연결 해제 | 목록에서 제거, 업로드 대상이었으면 "Google Drive · 기본"으로 복귀, 비밀 삭제 | ⬜ | |
| RS-10 | S3/SMB 계정으로 업로드 후 설정 → 업로드 목록 | 행 부제가 "계정 이름 · 상태". Drive 업로드 행은 계정 없이 상태만. 계정을 연결 해제하면 "연결 해제된 저장소 · 완료" | ⬜ | `accountNames` |
| RS-10 | 앱 재시작 후 | 계정·비밀 유지(Keystore 복호화) | ⬜ | |
| NAS-01 | 저장소 추가 → WebDAV, Synology `https://nas:5006/photos`, 사용자/비밀번호 → 연결 테스트 | "연결 성공". 401 이면 "연결 실패: … (401)" | ⬜ | `PROPFIND` Depth 0 |
| NAS-02 | 탐색 | 폴더·파일, 한글 폴더 이름 정상, 크기·수정일 | ⬜ | |
| NAS-03 | 새 폴더(MKCOL)·이름 변경·폴더 이동(MOVE)·삭제 | 폴더도 이름 변경·이동 가능. 삭제는 확인 후 즉시 | ⬜ | |
| NAS-04 | 업로드 폴더 지정 → 큰 영상 업로드 | NAS 에 파일 생성, NAS 에서 재생 가능. 중간에 끊기면 처음부터 다시(재개 없음) | ⬜ | |
| NAS-05 | http(비TLS) 주소 입력 | 빨간 경고 "https 가 아니면 비밀번호가 평문으로 전송됩니다"(입력은 허용) | ⬜ | |
| NAS-06 | 자체 서명 인증서 NAS 로 연결 테스트 | "서버 인증서를 신뢰할까요?" 다이얼로그에 SHA-256 지문 표시 → 신뢰 → 재테스트 성공 → 저장. 폼에 "신뢰한 인증서" 표시. 이후 NAS 인증서를 바꾸면 연결 실패 | ⬜ | `certSha256`, `pinCertificate` |
| NAS-07 | 저장소 추가 → SMB, 주소(`192.168.x.x` 또는 `nas.local`)·공유 이름·사용자·비밀번호 → 연결 테스트 | 같은 Wi-Fi 에서 "연결 성공". 비밀번호 틀리면 "SMB 오류: STATUS_LOGON_FAILURE …", 공유 이름 틀리면 "STATUS_BAD_NETWORK_NAME" | ⬜ | Synology: 파일 서비스 → SMB 켬, 최소 SMB2 |
| NAS-08 | SMB 탐색·새 폴더·이름 변경·폴더 이동·삭제 | 한글 폴더·파일 정상, 폴더 이름 변경/이동 즉시(복사 없음), 삭제 확인 후 즉시(휴지통 없음) | ⬜ | `rename` = SMB2 FileRenameInformation |
| NAS-09 | SMB 폴더를 업로드 폴더로 지정 → 큰 영상 업로드 중 Wi-Fi 를 끄고 다시 켬 | 워커 재시도 시 원격 파일 크기부터 이어 올림(처음부터 아님), 완료 후 NAS 에서 재생 가능 | ⬜ | `queryStatus` → `Incomplete(size)` |
| NAS-10 | 같은 이름 파일이 이미 있는 폴더로 업로드 | 덮어쓰지 않고 `이름 (1).jpg` 로 생성 | ⬜ | `RemoteNames.unique` |
| NAS-11 | 모바일 데이터(Wi-Fi 끔)에서 SMB 연결 테스트 | 30초 안에 "SMB 오류: … timed out/unreachable" — 앱이 멈추지 않음 | ⬜ | 외부망 불가는 폼 힌트로 안내 |
