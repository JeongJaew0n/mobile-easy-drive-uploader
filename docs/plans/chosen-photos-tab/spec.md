# 고른 사진 탭 — 계획

2026-09-27. 요구: "앱 메인에 내가 선택한 사진만 나오도록. 지금은 '전체' 가 보이는데, 이거 말고 별도의 화면."

## 사용자 결정 (2026-09-27)

- **즐겨찾기와 따로, 새로 고르는 모음.** 앱 DB 에만 남고 삼성 갤러리에는 보이지 않는다.
- **탭 맨 앞에 두고, 앱을 켜면 이 탭부터.** 옆 탭(전체·카메라·스크린샷·다른 앱)은 그대로.

## feature

새 feature 를 만들지 않는다. 갤러리의 탭 하나이므로 `feature/gallery` 에 넣고, 저장소만 `core/data/chosen` 으로 둔다.

## 이름

용어집에 **고른 사진 (chosen)** 으로 올린다. 코드: `ChosenMediaRepository`, 표 `chosen_media`, `GalleryTab.CHOSEN`.
"picked"(지정 폴더)·"selected"(선택 모드)는 이미 다른 뜻으로 쓰고 있어 피한다.

## 만드는 것

1. Room `chosen_media(mediaId PK, chosenAt)` — `hidden_media` 와 같은 모양. DB 12 → 13, `AutoMigration`
2. `ChosenMediaRepository` — `observeIds`·`choose`·`unchoose`
3. `GalleryTab.CHOSEN` 을 맨 앞에. `GalleryViewModel` 의 시작 탭을 `CHOSEN` 으로
4. 거르는 순서: 숨김 → 탭. 숨긴 사진은 고른 사진이어도 안 보인다
5. 선택 하단바에 "고른 사진에 넣기 / 빼기" 토글(선택이 전부 고른 사진이면 빼기)
6. 빈 화면 문구: 다른 탭에서 길게 눌러 고른 뒤 넣으라는 안내
7. 스낵바: "N개를 고른 사진에 넣었습니다 / 뺐습니다"

## 확인

- 단위 테스트: 시작 탭이 CHOSEN, CHOSEN 탭은 고른 것만, 숨김이 이긴다, 토글이 넣고 뺀다
- S23+ (지시가 있을 때만): 앱 시작 화면, 넣기·빼기, 재시작 후 유지 — `docs/manual-tests/01-gallery-basics.md`
