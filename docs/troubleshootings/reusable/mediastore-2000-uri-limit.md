# MediaStore 일괄 요청은 URI 2,000개까지 (API 36 타깃)

2026-10-05. 증상: 사진을 많이 골라 지우거나 휴지통으로 옮기면(특히 "올린 사진 기기에서 삭제") 동의 창이 뜨지 않고
오류 스낵바가 뜬다. 앱은 죽지 않는다 — 그래서 기기의 크래시 기록(dropbox `data_app_crash`)에도 남지 않는다.

## 원인

`MediaStore.createDeleteRequest` · `createTrashRequest` · `createFavoriteRequest` · `createWriteRequest` 문서:

> Note: if your app targets `android.os.Build.VERSION_CODES#BAKLAVA` and above, you can send a maximum of 2000 uris
> in each request. Attempting to send more than 2000 uris will result in a `java.lang.IllegalArgumentException`.

이 앱은 `targetSdk = 36`(BAKLAVA)이다. 그런데 고른 항목 전체를 요청 하나로 넘겼다(`MediaStoreRepository.requestTrash`
등 → `MediaActionRunner`). 2,000장을 넘는 순간 요청을 만드는 자리에서 `IllegalArgumentException` 이 나고,
`MediaActionController` 가 잡아 스낵바로 띄웠다. 타깃 35 이하일 때는 제한이 없어 드러나지 않았다.

갤러리에 사진이 6천 장 있고, 올린 사진을 한 번에 정리하는 흐름이 있어 쉽게 넘는다.

## 고친 것

`MediaActionController` 가 명령을 2,000개씩 나눠(`MediaAction.chunked()`, `MAX_URIS_PER_REQUEST`) 차례로 처리한다.

- 조각마다 동의를 받는다. **미디어 관리 권한이 있으면** 창 없이 지나가고, 없으면 조각 수만큼 시스템 창이 뜬다(4,500장 → 3번).
- 완료는 다 끝난 뒤 한 번만 알린다. 중간에 취소하면 그때까지 처리한 만큼을 완료로 알린다(이미 지운 것을 "취소" 라고 하지 않는다).
- 요청을 만드는 곳은 모두 이 컨트롤러를 지나므로(갤러리·상세보기·휴지통·중복·백업 칸) 한 곳 수정으로 끝난다.

테스트: `MediaActionControllerTest`(4,500장 → 2,000·2,000·500, 중간 취소, 첫 조각 취소).

## 다른 프로젝트에서

targetSdk 를 36 으로 올릴 때 MediaStore 일괄 요청을 쓰는 곳을 찾아 2,000개씩 나눈다. 크래시가 아니라 잡힌 예외로
나타나는 경우가 많아 기록에 잘 안 남는다.
