# NAS 연동 — WebDAV 우선, SMB 는 후속

작성 2026-09-09. 집·사무실 NAS(Synology·QNAP·Nextcloud·일반 리눅스 서버)로 사진을 옮기고 탐색·CRUD 하려는 요구. `MULTI_CLOUD.md` 의 `RemoteStorage` 제공자로 구현한다.

## 1. 프로토콜 선택

| 프로토콜 | 장점 | 단점 | 결정 |
|---|---|---|---|
| **WebDAV(HTTPS)** | 표준 HTTP — 기존 OkHttp 로 끝, 외부망(DDNS·QuickConnect 역방향 프록시)에서도 동작, Synology·QNAP·Nextcloud 모두 기본 제공, TLS | 서버에서 WebDAV 서비스를 켜야 함, 재개 업로드 없음(전체 PUT) | **W1 로 먼저** |
| SMB2/3 | NAS 기본, 로컬 네트워크 빠름, 별도 설정 없음 | 외부망 불가(VPN 필요), 라이브러리 필요(`smbj`, Apache 2.0, ~1MB, BouncyCastle 의존), 배터리·절전 시 연결 유지 문제 | **W2**(§5) |
| SFTP | 어디나 있음 | 라이브러리(`sshj`) 무거움, 파일 목록 메타데이터 빈약 | 후보 |
| FTP | 구식·평문 | 제외 | 제외 |

## 2. WebDAV 제공자 (`WebDavStorage`)

- 계정: `RemoteAccountEntity(kind = WEBDAV, endpoint = "https://nas.example.com:5006/photos", username, secretRef)`. Basic 인증(Digest 는 OkHttp `Authenticator` 로 후속). 자체 서명 인증서는 **기본 거부**, 계정 추가 화면에 "이 서버의 인증서 신뢰(지문 표시)" 옵션 → 지문을 계정에 저장해 `CertificatePinner` 로 고정(전체 신뢰는 하지 않는다).
- `entryId` = endpoint 기준 상대 경로(`/2026/09/IMG.jpg`, 폴더는 `/2026/09/`).
- **목록**: `PROPFIND` Depth 1, 본문 `<propfind><prop><displayname/><getcontentlength/><getlastmodified/><getcontenttype/><resourcetype/></prop></propfind>` → 207 Multi-Status XML(`XmlPullParser`). 자기 자신(첫 response)은 제외, `resourcetype/collection` 이면 폴더. 페이징 없음(한 번에).
- **폴더 생성**: `MKCOL`. **이름 변경·이동**: `MOVE` + `Destination` 헤더(`Overwrite: F`). **삭제**: `DELETE`(휴지통 없음 → 확인 다이얼로그; Nextcloud 는 서버 쪽 휴지통이 있지만 표준이 아니라 기대하지 않는다).
- **업로드**: `PUT` 스트리밍(`ContentUriRequestBody` 재사용), `Content-Length` 명시. 재개 없음 → `RemoteUploader.queryStatus` 는 항상 `Expired`, 워커가 처음부터 다시. 완료 판정 201/204. 같은 이름이 있으면 `If-None-Match: *` 로 덮어쓰기 방지 → 412 면 이름에 ` (1)` 붙여 재시도.
- **용량**: `PROPFIND` Depth 0 의 `quota-available-bytes`/`quota-used-bytes`(있으면).
- 연결 테스트: `PROPFIND` Depth 0 → 207. 401 이면 "아이디/비밀번호", 404 면 "경로", SSL 예외면 인증서 안내.
- Synology 메모: 제어판 → 파일 서비스 → WebDAV 켬(HTTPS 5006). Nextcloud: `https://host/remote.php/dav/files/<user>/`.

## 3. 화면

`MULTI_CLOUD.md` §5 의 공용 화면을 쓴다. WebDAV 폼: 서버 주소(https 강제·경고), 사용자, 비밀번호, 표시 이름, 인증서 신뢰 옵션. 탐색·CRUD·업로드 폴더 지정은 `RemoteBrowser` 그대로(능력: RENAME·MOVE, TRASH 없음, QUOTA 선택).

## 4. 검증

- 단위: MockWebServer 로 PROPFIND 207 파싱(폴더/파일/자기 자신 제외/한글 경로 인코딩), MKCOL·MOVE(Destination 절대 URL)·DELETE·PUT 요청 형태, 401/404 매핑.
- 실기기(지시 시): `manual-tests/11-remote-storage.md` NAS-01~08 — Synology WebDAV 연결, 한글 폴더, 큰 영상 업로드 후 NAS 에서 재생, 절전 후 재개(처음부터 다시 올라가는지), 자체 서명 인증서 지문 고정.

## 5. SMB (W2, 후속)

- `com.hierynomus:smbj`(Apache 2.0) — SMB2/3, 서명·암호화. 연결은 세션당 하나 유지, 워커 실행 중에만 열고 닫는다.
- Wi-Fi 에서만 동작(외부망 없음) → 업로드 제약에 "이 저장소는 Wi-Fi 필요"를 자동 적용.
- 호스트 발견은 하지 않는다(mDNS 광고가 제각각). 주소·공유 이름·계정 직접 입력.
- 재개: `SMB2 Write` 는 오프셋 지정이 가능하므로 `queryStatus` 로 원격 파일 크기를 읽어 그 지점부터 이어 쓴다(`RESUMABLE_UPLOAD` 가능).
- 위험: BouncyCastle 이 R8 규칙과 크기를 늘린다. W1 이 충분하면 미룬다.
