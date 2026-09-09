# NAS 연동 — WebDAV(W1) + SMB(W2) + SFTP(W3)

작성 2026-09-09. 집·사무실 NAS(Synology·QNAP·Nextcloud·일반 리눅스 서버)로 사진을 옮기고 탐색·CRUD 하려는 요구. `MULTI_CLOUD.md` 의 `RemoteStorage` 제공자로 구현한다.

## 1. 프로토콜 선택

| 프로토콜 | 장점 | 단점 | 결정 |
|---|---|---|---|
| **WebDAV(HTTPS)** | 표준 HTTP — 기존 OkHttp 로 끝, 외부망(DDNS·QuickConnect 역방향 프록시)에서도 동작, Synology·QNAP·Nextcloud 모두 기본 제공, TLS | 서버에서 WebDAV 서비스를 켜야 함, 재개 업로드 없음(전체 PUT) | **W1 로 먼저** |
| SMB2/3 | NAS 기본, 로컬 네트워크 빠름, 별도 설정 없음, **오프셋 쓰기로 재개 업로드** | 외부망 불가(VPN 필요), 라이브러리 필요(`smbj`, Apache 2.0, ~1MB, BouncyCastle 의존), 배터리·절전 시 연결 유지 문제 | **W2 구현**(§5) |
| SFTP | 리눅스 서버·NAS 어디나 있고 SSH 하나만 열면 됨, 오프셋 쓰기로 재개 가능 | 라이브러리(`sshj`) 필요(BouncyCastle 은 smbj 와 공유), 호스트 키 신뢰 절차 필요 | **W3 구현**(§7) |
| FTP | 구식·평문 | 제외 | 제외 |

## 2. WebDAV 제공자 (`WebDavStorage`)

- 계정: `RemoteAccountEntity(kind = WEBDAV, endpoint = "https://nas.example.com:5006/photos", username, secretRef)`. Basic 기본 + **Digest**(RFC 7616, MD5·SHA-256, qop=auth) — `DigestAuth`: 첫 요청은 Basic, 서버가 `401 Digest` 챌린지를 주면 `Authenticator` 가 응답을 계산해 재시도하고 챌린지를 기억, 이후 요청은 인터셉터가 선제적으로 Digest 헤더를 붙인다(스트리밍 PUT 은 재전송이 안 되므로 필수). 포기 조건은 "이미 Digest 로 보냈는데 **같은 nonce** 로 또 거절" 이다 — nonce 가 바뀌었으면 `stale` 표시가 없어도 한 번 더 시도한다(그러지 않으면 만료된 nonce 가 캐시에 남아 그 계정의 모든 요청이 실패한다). 한 줄에 `Basic …, Digest …` 를 합쳐 보내는 서버도 파싱하고, `-sess` 는 대소문자를 가리지 않는다. 자체 서명 인증서는 **기본 거부**, 계정 추가 화면에 "이 서버의 인증서 신뢰(지문 표시)" 옵션 → 지문을 계정에 저장해 `CertificatePinner` 로 고정(전체 신뢰는 하지 않는다).
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

## 5. SMB (W2) — `SmbStorage`

- 라이브러리 `com.hierynomus:smbj` 0.15.0(Apache 2.0; 의존 bcprov-jdk18on·asn-one·mbassador·slf4j-api). SMB2/3, 서명·암호화 협상은 smbj 기본값. slf4j 바인딩은 넣지 않는다(첫 호출에 "No SLF4J providers" 한 줄만 나온다).
- 계정: `RemoteAccountEntity(kind = SMB, endpoint = "host[:port]"(기본 445), bucketOrRoot = 공유 이름, region = 도메인/작업 그룹(선택), username, secretRef)`. 호스트 발견은 Android `NsdManager`(mDNS `_smb._tcp.`, 라이브러리 없음)로 **편의 기능**만 제공 — 폼의 "네트워크에서 찾기"(8초, 발견 서비스는 순서대로 resolve) 결과 칩을 누르면 주소·표시 이름이 채워진다. NAS 마다 광고 여부가 달라 직접 입력이 기본이고, 공유 이름은 항상 직접 입력(SMB 공유 열거는 RPC 라 smbj 범위 밖).
- **연결은 작업마다 열고 닫는다**(`withShare`: connect → authenticate → connectShare → 작업 → close). 소켓을 워커 밖에서 들고 있지 않아 절전·Wi-Fi 전환에 강하고, 목록 한 번에 왕복 3~4회가 늘지만 LAN 이라 체감 없다. 타임아웃 30초.
- `entryId` 는 다른 제공자와 같은 `/` 구분 상대 경로(폴더는 `/` 끝), smbj 로 넘길 때만 `\\` 로 바꾼다(`SmbPaths`). 루트는 빈 문자열.
- 목록: `DiskShare.list(path)` → `FileIdBothDirectoryInformation`(`.`/`..` 제외, `FILE_ATTRIBUTE_DIRECTORY` 로 폴더 판정, `endOfFile`, `changeTime`). 페이징 없음. 폴더 생성 `mkdir`, 이름 변경·이동은 `openFile/openDirectory(DELETE)` 후 `rename(새 전체 경로)`(SMB2 `FileRenameInformation`, 같은 공유 안에서만), 삭제는 `rm` / `rmdir(recursive)`. 휴지통 없음 → 능력 `RENAME·MOVE·FOLDER_MUTATION·RESUMABLE_UPLOAD`.
- **업로드(재개)**: `startSession` 은 같은 이름이 있으면 ` (n)` 을 붙인 대상 경로를 세션 URI 로 돌려준다. `queryStatus` 는 원격 파일 크기를 읽어 `길이와 같음 → Complete`, `작음 → Incomplete(size)`(그 오프셋부터 이어 씀), `없음/큼 → Expired`. `upload` 는 content 스트림을 오프셋까지 `skip` 한 뒤 `ByteChunkProvider` 로 `File.write` — 청크마다 `Progress`. 첫 시도는 `FILE_OVERWRITE_IF`, 이어 쓰기는 `FILE_OPEN_IF`.
- 외부망: SMB 는 인터넷에 노출하면 안 되는 프로토콜이라 Wi-Fi(또는 VPN) 안에서만 동작한다. 업로드 제약의 "Wi-Fi 전용"을 켜는 자동 규칙은 두지 않았다(VPN 사용자를 막지 않기 위해) — 폼 힌트로만 안내.
- R8: `-keep com.hierynomus.**`(mbassador 이벤트 버스가 리플렉션으로 핸들러를 찾음), `-keep net.engio.mbassy.**`, `-dontwarn org.bouncycastle.** / org.slf4j.** / javax.naming.**`. `assembleRelease` 후 `missing_rules.txt` 없음 확인. 첫 빌드에서 `org.ietf.jgss.*`(Kerberos)·`javax.el.*`(mbassador EL) 누락이 나와 `-dontwarn` 추가. 릴리스 APK 6.17MB → 8.01MB(+1.8MB, 대부분 BouncyCastle).
- 검증: 단위 `SmbPathsTest`(경로 변환·host:port). smbj 는 실제 서버가 필요해 프로토콜 테스트는 없고 실기기 `NAS-07~10` 으로.

## 6. 진행 기록

- 2026-09-09 W1 구현(`0a2c34d`): `WebDavStorage`(PROPFIND/MKCOL/MOVE/DELETE/PUT), 계정 추가 폼, 탐색·CRUD·업로드 대상 지정.
- 2026-09-09 자체 서명 인증서 지문 고정: 연결 테스트가 TLS 오류로 실패하면 서버 리프 인증서의 SHA-256 을 읽어 "이 인증서 신뢰" 다이얼로그를 띄우고, 수락 시 `RemoteAccount.certSha256`(Room v7)에 저장. `WebDavStorage` 는 그 인증서와 **정확히 같은** 경우만 연결(`pinCertificate`, 호스트 이름 검사는 생략 — 인증서 자체를 고정). 전체 신뢰 옵션은 두지 않았다. `PinnedTlsTest`(okhttp-tls 자체 서명 서버).
- 2026-09-09 W2 SMB 구현: `SmbStorage`/`SmbModule`(`@RemoteKindKey(SMB)`), 계정 추가 폼에 SMB 종류(주소·공유 이름·도메인·사용자·비밀번호), 오프셋 재개 업로드, R8 규칙. `SmbPathsTest`.
- 2026-09-09 WebDAV Digest 인증(`DigestAuth`/`DigestCalculator`, `DigestAuthTest` RFC 7616 벡터·MockWebServer 흐름).
- 2026-09-09 SMB 서버 mDNS 검색(`HostDiscovery`/`NsdHostDiscovery`, 폼 `SmbDiscoveryRow`).
- 2026-09-09 W3 SFTP 구현(§7): `SftpStorage`/`SftpPaths`/`PinnedHostKeyVerifier`, 호스트 키 지문 고정, 재개 업로드, mDNS `_sftp-ssh._tcp` 검색. 릴리스 APK 8.01MB → 8.32MB.
- 남은 것: 키 파일(공개키) 인증, WebDAV 서버 쪽 휴지통.

## 7. SFTP (W3) — `SftpStorage`

- 라이브러리 `com.hierynomus:sshj` 0.40.0(Apache 2.0). 의존성 대부분(bcprov·asn-one·slf4j)을 smbj 와 공유하고, **bcpkix 는 제외**한다 — PEM 개인키를 읽을 때만 필요한데 우리는 비밀번호 인증만 쓰고, bcpkix 가 끌어오는 오래된 bcutil 이 최신 bcprov 와 클래스가 겹쳐 빌드가 깨진다(`app/build.gradle.kts` 의 `exclude`).
- 계정: `endpoint = "host[:port]"`(기본 22), `bucketOrRoot` = 루트 경로(선택, 비우면 서버 기본 디렉터리), `username`/secret, `certSha256` = **SSH 호스트 키 SHA-256 지문**(WebDAV 인증서 지문과 같은 칸·같은 표시 형식을 재사용).
- **호스트 키 신뢰**: `known_hosts` 를 쓰지 않는다. 계정에 붙은 지문과 정확히 같은 키만 통과(`PinnedHostKeyVerifier`). 지문이 없으면 연결 테스트가 실패하고, 그때 인증 없이 호스트 키만 읽어(`fetchSshHostKeySha256`) "이 서버를 신뢰할까요?" 다이얼로그를 띄운다. 수락하면 지문을 저장하고 다시 테스트 — WebDAV 자체 서명 인증서와 같은 흐름. 나중에 서버 키가 바뀌면 연결이 거부된다(중간자 경고).
- `entryId` 는 다른 경로 기반 제공자와 같은 `/` 상대 경로(폴더는 `/` 끝). 서버에 보낼 때만 루트를 붙인다(`SftpPaths.absolute`). 공용 헬퍼는 `core/data/remote/RemotePaths`(SMB 도 같이 쓴다).
- 목록 `SFTPClient.ls`, 폴더 생성 `mkdir`, 이름 변경·이동 `rename`(같은 서버 안), 삭제는 파일 `rm` / 폴더는 아래부터 훑어 지운다(SFTP 는 재귀 삭제가 없다). 휴지통 없음 → 능력 `DOWNLOAD·RENAME·MOVE·FOLDER_MUTATION·RESUMABLE_UPLOAD`.
- **업로드(재개)**: `RemoteFile.write(offset, ...)` 로 임의 오프셋 쓰기가 되므로 `queryStatus` 가 `stat` 크기를 보고 `Incomplete(size)` 를 돌려주면 그 지점부터 이어 쓴다. 첫 시도만 `TRUNC`. 다운로드는 오프셋 읽기를 감싼 `InputStream` 이고, 닫을 때 SSH 세션까지 정리한다.
- 연결은 SMB 와 같이 **작업마다 열고 닫는다**. 타임아웃 30초.
- **버그 수정(2026-09-09, 2차 감사)**: SMB 청크 제공자를 직접 구현하지 않고 smbj 의 `InputStreamByteChunkProvider` 를 상속하도록 바꿨다 — `SMB2WriteRequest` 가 `bytesLeft()` 로 **패킷 Length 를 먼저 써 넣은 뒤** 본문을 채우므로, 직접 구현하면 EOF 에서 선언 길이와 본문이 어긋난 WRITE 가 나간다(상위 클래스가 `prepareWrite` 로 미리 읽어 둘을 맞춘다). 두 제공자 모두 업로드 후 **크기를 검증**해 원본이 잘렸을 때 완료로 기록하지 않는다(SFTP 는 조용히 잘린 파일을 성공으로 남기고 자동 백업이 다시 올리지 않던 데이터 손실 경로였다). `abort` 도 구현해 영구 실패 시 반쯤 올라간 파일을 지운다.
- **버그 수정(2026-09-09)**: SMB 청크 제공자가 원본 스트림이 예상 길이보다 짧을 때 `getChunk` 에서 -1 을 돌려줬는데, smbj 는 그 값을 그대로 `offset` 에 더해 **오프셋이 뒤로 가며 무한 루프**가 된다. 0 을 돌려주고 `isAvailable` 로 끝을 알리도록 고치고(`ContentChunkProvider`), 그 계약을 흉내 낸 회귀 테스트(`ContentChunkProviderTest`)를 붙였다. SFTP 쪽은 선언된 길이를 넘겨 쓰지 않도록 청크를 잘랐다(넘치면 `stat` 기반 재개 판정이 어긋난다).
- **오류 분류**: 인증 실패(`UserAuthException`)·전송 계층 오류(호스트 키 불일치 등 `TransportException`)는 4xx 로 표시해 업로드 워커가 즉시 포기한다. 연결 끊김·타임아웃만 재시도(최대 5회). SMB 도 같은 방식으로 `NtStatus`(LOGON_FAILURE·ACCESS_DENIED → 401, BAD_NETWORK_NAME·경로 없음 → 404)를 매핑한다.
- 검증: 단위 `SftpPathsTest`(루트 결합·기본 포트), `PinnedHostKeyVerifierTest`(지문 일치/불일치·대소문자). 실제 서버가 필요한 부분은 실기기 `NAS-14~17`.
