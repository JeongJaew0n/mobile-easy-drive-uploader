# Drive 사진 — 폴더가 아니라 사진첩으로 본다

2026-10-06. 기획: 백업 칸 기획 아티팩트 2부(https://claude.ai/artifact/3CgS8DbGbH9x8ZLZu3HWbz). 사용자가 제안대로 확정했다.
1부(백업된 사진)는 `docs/plans/backed-up-photos/spec.md` 에서 따로 진행한다 — 이 작업이 먼저다.

## 1. 왜

지금 Drive 화면은 파일 탐색기다. 사진 한 장을 보려면 백업 칸 → Drive → 루트(지정·기본 폴더 목록) → 폴더 → 이름순 목록 → 미리보기 창.
미리보기는 한 장짜리 창이라 다음 사진을 보려면 닫고 다시 골라야 한다.

## 2. 확정된 결정

| 물음 | 결정 |
|---|---|
| 기본 순서 | **올린 날짜순**(`createdTime desc`, 서버 정렬). "찍은 날짜순" 은 메뉴에서 켜고, 그때만 목록을 끝까지 읽어 기기에서 정렬 |
| 읽기 권한을 켠 사람 | 기본은 **앱이 올린 것만**. 폴더 칩에 "Drive 전체(보기 전용)" 를 하나 더 둔다 |
| 만드는 순서 | 2부(이 문서) 먼저 |

## 3. 화면

### 3.1 백업 칸 — "Google Drive 사진" 카드
- 기존 "Google Drive 파일 보기" 줄을 대신한다. Google 계정이 연결돼 있을 때만 보인다.
- 최근 Drive 사진 썸네일 한 줄(최대 8장). 썸네일을 누르면 Drive 사진 화면이 열리며 **그 사진의 상세**가 바로 뜬다.
  "모두 보기" 는 Drive 사진 화면.
- 목록을 못 읽으면(오프라인 등) 썸네일 줄 없이 카드만 — 눌러서 들어가면 화면이 오류를 보인다.

### 3.2 Drive 사진 화면(`DrivePhotosKey`)
- 폴더를 가로질러 사진·영상을 격자로, 달별 머리글. 기본 올린 날짜순.
- 거르기(세그먼트): **전체 / 기기에 없음 / 영상**
  - 영상은 서버 조건(`mimeType contains 'video/'`)
  - 기기에 없음은 기기에서 거른다 — 원장(`uploaded_media`)에서 `driveFileId → mediaId` 를 찾아 그 mediaId 가 MediaStore 에 있으면 "기기에 있음".
    원장에 없는 파일(다른 기기·앱 재설치 전 업로드)은 기기에 없음으로 본다. 걸러서 남는 게 적으면 다음 쪽을 알아서 더 읽는다.
- 범위(칩): 앱이 올린 것(기본) / 폴더 하나(지정·기본·보기 전용 폴더) / Drive 전체(보기 전용, 읽기 권한이 있을 때만)
- 칸의 ☁ 표시 = 이 기기에 없음. 영상은 길이.
- 상단: 뒤로 · 제목 · "폴더로 보기"(지금의 Drive 탐색 화면) · ⋮(순서, 새로 고침)
- 길게 눌러 고르기 → 기기로 받기 · Drive 휴지통(실행 취소 있음). 보기 전용 범위에서는 받기만.
  이동은 폴더 보기에서 한다 — 폴더를 가로지른 목록에서는 "어디서" 옮기는지가 항목마다 달라서다.

### 3.3 넘겨 보기(`DriveMediaPager`)
- 전체 화면, 좌우로 넘긴다. 끝에 가까워지면 다음 쪽을 읽는다.
- 사진: 큰 썸네일(`=s1600`)을 먼저 깔고 원본을 위에 덮는다 — 넘길 때 빈 화면이 짧다.
- 영상: 지금 보이는 쪽만 플레이어를 만든다(옆 쪽은 썸네일 + 재생 표시).
- 위: 이름, 찍은(없으면 올린) 시각 · 크기. 아래: "☁ 이 기기에는 없음"/"이 기기에도 있음", 기기로 받기 · Drive 휴지통 · Drive 앱에서 열기.
- 누르면 위아래 막대를 숨기고 보인다.
- **폴더 보기에도 같은 넘겨 보기를 쓴다**(그 폴더의 사진·영상끼리). 폴더 보기의 격자도 기본으로 켠다.
- 기획 견본의 "공유" 는 뺀다. Drive 링크 공유는 받는 사람에게 권한이 없어 열리지 않고, 파일 공유는 먼저 받아야 해서 "기기로 받기" 와 겹친다.

## 4. 데이터

- 조회: `files.list`
  - 공통: `(mimeType contains 'image/' or mimeType contains 'video/') and trashed = false`
  - 앱이 올린 것: `+ 'me' in owners`. 읽기 권한(`drive.readonly`)이 있으면 Drive 전체가 보이므로 업로드 표식
    `appProperties has { key = 'easyGallery' and value = '1' }` 를 더한다. 읽기 권한이 없으면 `drive.file` 이 이미 앱 파일로 좁히므로
    표식을 붙이지 않는다 — 표식이 생기기 전에 올린 옛 파일도 보이게.
  - 폴더 하나: `+ '<id>' in parents` (하위 폴더는 들어가지 않는다)
  - Drive 전체: 공통 조건만
  - `orderBy=createdTime desc`, `pageSize=300`(찍은 날짜순은 1000으로 끝까지)
  - `fields` 에 `createdTime, imageMediaMetadata(time), videoMediaMetadata(durationMillis)` 추가
- `imageMediaMetadata.time` 은 EXIF 형식(`yyyy:MM:dd HH:mm:ss`, 시간대 없음) — 기기 시간대로 읽는다.
- 원장: `driveFileId → mediaId` 를 주는 DAO 조회 추가(목적지 = 지금 연결된 Google 계정). DB 스키마 변경 없음.

## 5. 코드 배치

- `core/data/drive/DriveMediaQuery.kt` — 조회 조건(순수 함수, 단위 테스트)
- `DriveRepository.listMedia(...)`
- `feature/drive/` — `DrivePhotosViewModel`, `DrivePhotosScreen`(Route+Screen), `DrivePhotosTopBar`, `DrivePhotosGrid`,
  `DrivePhotosModel`(묶기·거르기 순수 함수), `DriveMediaPager`, `DriveThumbnail`(격자·띠·넘겨 보기가 같이 쓰는 칸)
- `feature/backup/` — `DrivePhotosCard`, `BackupViewModel` 이 최근 Drive 사진을 읽는다
- 새 feature 를 만들지 않는다 — Drive 의 다른 보기다.

## 6. 확인

- 단위: 조회 조건, 달별 묶기, 기기에 있음 판정, EXIF 시각 해석, 찍은 날짜순 정렬
- 기기(사용자가 나중에): `docs/manual-tests/19-drive-photos.md`

## 7. 알려진 한계

- **받은 사진은 ☁ 가 남는다.** 기기로 받기(`DownloadWorker`)는 MediaStore 에 새 파일을 만들 뿐 원장과 잇지 않는다.
  잇으려면 받은 파일의 새 mediaId 를 그 Drive 파일 ID 로 원장에 적어야 한다 — 다음 작업 후보(DPH-11).
- 폴더 범위는 그 폴더 **바로 아래**만 본다. 하위 폴더까지 훑으려면 폴더마다 조회해야 한다.
- 찍은 날짜순은 사진마다 EXIF 시각을 기기 시간대로 읽는다 — 다른 시간대에서 찍은 사진은 몇 시간 어긋날 수 있다(갤러리와 같은 방식).
