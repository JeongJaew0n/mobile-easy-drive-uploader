# 06 · 릴리스 빌드 (R8 적용)

R8 이 직렬화·리플렉션·DI 를 깨뜨렸는지는 **런타임에서만** 드러난다. 릴리스 APK(`com.jjw.easygallery`, 접미사 없음)로 확인.

```bash
./gradlew :app:assembleRelease && adb install -r app/build/outputs/apk/release/app-release.apk
```

| ID | 시나리오 | 기대 결과 | 상태 | 메모 |
|---|---|---|---|---|
| REL-01 | 릴리스 APK 설치·실행 | 크래시 없이 갤러리 표시 (Hilt 컴포넌트·Room 생성) | ⬜ | `adb logcat -s AndroidRuntime` 병행 |
| REL-02 | 화면 이동 후 앱 종료(최근 앱에서 제거) → 재실행 | 백스택 복원(Nav 키 kotlinx.serialization) | ⬜ | 프로세스 종료는 `adb shell am kill` |
| REL-03 | 상세보기 열고 회전 → 복귀 | 상태 유지, 크래시 없음 | ⬜ | |
| REL-04 | Google 계정 연결 (릴리스 SHA-1 등록 필요) | 로그인 성공, 저장공간 표시(Drive `about` DTO 역직렬화) | ⬜ | 미등록이면 `DEVELOPER_ERROR` — R8 문제와 구분 |
| REL-05 | Drive 파일 보기 | 목록 표시(`DriveFileListDto` 역직렬화, Retrofit 애노테이션) | ⬜ | |
| REL-06 | 사진 1장 업로드 → 완료 | 세션 시작(`DriveFileMetadata` 직렬화) → PUT → 완료 알림 | ⬜ | WorkManager `HiltWorkerFactory` 경로 |
| REL-07 | 업로드 중 강제 종료 → 재시작 | Room 큐 복원·재개 | ⬜ | |
| REL-08 | 영상 재생 | ExoPlayer 정상 | ⬜ | |
| REL-09 | 크래시 유발 시 `mapping.txt` 로 스택 복원 | `retrace` 로 원래 이름 복원됨 | ⬜ | `$ANDROID_HOME/cmdline-tools/latest/bin/retrace mapping.txt stack.txt` |
