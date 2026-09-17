# 진행

- [x] 기기 데이터로 분포 확인 (2026-09-17)
- [x] 탭 구성·필터 관계 사용자 확정
- [x] 계획 작성
- [x] glossary 에 '출처'·'앨범' 추가
- [x] `MediaSource` + 단위 테스트
- [x] ViewModel 탭 상태·필터 초기화 + 테스트
- [x] 화면 `PrimaryTabRow`
- [x] detekt · test · assembleDebug · lintDebug
- [x] 설계 문서(`docs/GALLERY_SOURCE_TABS.md`)·수동 테스트 항목 GAL-27~31
- [x] 실기기 확인 (2026-09-17)
- [ ] 커밋·푸시

## 남은 것

- 탭 선택은 저장하지 않는다(앱을 다시 켜면 '전체'). 필요해지면 `UserPreferences` 로 옮긴다.
- `SilentCamera/` 같은 최상위 카메라류 앱은 '다른 앱' 으로 간다 — 규칙을 한 줄로 유지한 대가.
