# Easy Gallery

핸드폰 갤러리(사진·영상)를 Google Drive·S3 호환 클라우드(Naver Cloud·KT Cloud·AWS·MinIO)·NAS(WebDAV)로 옮기고, 갤러리를 자유롭게 CRUD 하는 **Android 전용** 앱.

Kotlin · Jetpack Compose · Hilt · Navigation 3 · Room · WorkManager · Retrofit(Drive REST v3) · OkHttp(S3 SigV4·WebDAV 직접 구현)

## 주요 기능

- 갤러리: 날짜 섹션, 드래그 다중 선택, 날짜별 전체 선택, 기간 필터(사진 있는 날만 선택되는 달력), 즐겨찾기·휴지통·이름 변경·앨범 이동, 완전 중복 탐지
- 카테고리: 항목에 여러 카테고리 지정(tri-state 피커), 카테고리 필터(OR·미분류), 썸네일 색 점, 관리 화면 — `docs/CATEGORIES.md`
- 상세보기: 히어로 전환, 확대·스와이프 닫기, 영상 재생(비율 유지·재생바·배속·회전), EXIF 정보
- 업로드: WorkManager 큐, Drive 재개 업로드, 영상 압축(Media3), 자동 백업, 업로드 배지
- 저장소: Google Drive 파일 CRUD(이름 변경·이동·휴지통 실행 취소), 연결된 저장소 추가(S3 호환·WebDAV) 후 탐색·CRUD·업로드 대상 지정 — `docs/MULTI_CLOUD.md`, `docs/NAS_STORAGE.md`

## 문서

- [기술 스택 및 결정 근거](docs/TECH_STACK.md)
- [코드 구조](docs/ARCHITECTURE.md)
- [애니메이션 개선 분석](docs/ANIMATION_IMPROVEMENT.md)
- [상세보기 안정화](docs/VIEWER_STABILITY.md) · [기간 선택 UI](docs/DATE_RANGE_PICKER.md) · [카테고리](docs/CATEGORIES.md)
- [Drive 파일 CRUD](docs/DRIVE_FILE_CRUD.md) · [다중 클라우드](docs/MULTI_CLOUD.md) · [NAS](docs/NAS_STORAGE.md)
- [Google 로그인 문제 해결](docs/GOOGLE_SIGN_IN_TROUBLESHOOTING.md)
- [수동 테스트 대기 목록](docs/manual-tests/README.md)
- [릴리스 빌드·서명](docs/RELEASE.md)

## 시작하기

```bash
# Android SDK 경로 (local.properties 의 sdk.dir 과 동일하게)
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools

./gradlew :app:assembleDebug        # 빌드
./gradlew :app:testDebugUnitTest    # 단위 테스트
./gradlew detekt                    # 정적 분석
```

`local.properties` 는 커밋되지 않으므로 처음 클론하면 직접 만든다:

```properties
sdk.dir=/path/to/android/sdk
```

## 요구 사항

- JDK 17+
- Android SDK Platform 37, Build-Tools 37
- minSdk 29 (Android 10)
