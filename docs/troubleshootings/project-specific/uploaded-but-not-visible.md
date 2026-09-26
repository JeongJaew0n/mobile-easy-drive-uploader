# "올렸다는데 Drive 에 없다"

2026-09-26. 갤러리는 `백업 43개` 라고 하는데 Drive 어디에도 파일이 보이지 않았다.

## 증상

- 앱 상단: `6153개 · 백업 43개`
- 앱의 Drive 화면 → `Easy Gallery` 폴더: **비어 있음**
- 사진에는 "이 기기에서 올림" 배지가 붙어 있음

## 결론 — 파일은 있었다. 볼 수 없었을 뿐이다

Drive 앱에서 확인하니 **`Games` 폴더에 그대로 있었다.** 없어진 것이 아니라
**올린 곳과 보는 곳이 달랐다.**

## 원인

업로드 대상과 화면에 보이는 목록이 **서로 다른 설정**을 보고, 둘이 어긋날 수 있다.

| | 무엇을 보나 |
|---|---|
| 업로드 대상 | `UserPreferences.uploadFolderId` |
| Drive 화면 루트 | 지정 폴더 목록 + 기본 폴더(`Easy Gallery`) |

`drive.file` 로 좁힌 뒤로 앱은 **접근권이 있는 폴더만** 볼 수 있다
(`docs/DRIVE_FILE_SCOPE.md`). 남의 폴더는 피커로 지정해야 목록에 뜬다.

그런데 지정을 해제해도 업로드 폴더는 그대로 남는다.

```kotlin
// SettingsViewModel
fun removePickedFolder(id: String) = runBusy { prefs.removePickedFolder(id) }

// UserPreferencesRepository — 목록에서 빼기만 한다
suspend fun removePickedFolder(id: String) = editFolders { current ->
    current.filterNot { it.id == id }
}
```

그래서 이런 상태가 만들어진다.

1. 피커로 `Games` 를 지정 → 업로드 폴더를 `GamesFolder` 로 설정
2. 거기에 사진을 올림 → 원장에 기록되고 배지가 붙음
3. **지정 폴더에서 `GamesFolder` 를 해제**
4. 업로드 폴더는 여전히 `GamesFolder` → 계속 그리로 올라간다
5. 하지만 Drive 화면에는 `Easy Gallery` 만 보인다 → **올린 것을 볼 방법이 없다**

이번 경우 3번을 한 것은 테스트 뒷정리 중이었다. 사용자가 직접 해제해도 같은 일이 난다.

## 증상이 헷갈리는 이유

`Easy Gallery` 폴더가 **비어 있는 채로 존재한다.** 앱이 Drive 화면을 열 때마다
`ensureAppRootFolder()` 로 만들기 때문이다. 사용자는 "앱이 쓰는 폴더" 를 열어보고
비어 있으니 업로드가 안 된 줄로 읽는다. 실제로는 한 번도 그 폴더로 올린 적이 없다.

## 곁가지로 드러난 것 — 중복

`Games` 안에 같은 파일이 **4개씩** 있었다(`20260925_065038.jpg` ×4 등).
2026-09-26 에 고친 두 가지(앱이 죽었을 때 재업로드, 이미 올린 것 재업로드) 이전에
반복 업로드한 결과다. `docs/UPLOAD_PERFORMANCE.md` §4-4, §4-5.

## 무엇을 고쳐야 하나

지정 폴더를 해제할 때 **그 폴더가 업로드 대상이면 함께 되돌려야** 한다. 볼 수 없는
곳으로 계속 올리는 상태를 만들지 않는 것이 핵심이다.

계정 연결을 해제할 때는 이미 그렇게 하고 있다 —
`SettingsViewModel.signOut` 의 "업로드 대상이었으면 Drive 로 되돌림".
지정 해제에는 같은 처리가 빠져 있었다.

## 구분해 둘 것 — 원장은 Drive 를 따라가지 않는다

이번 건의 원인은 아니지만, 비슷하게 보이는 다른 문제가 있다.

원장(`uploaded_media`)은 **로컬 기록**이고 Drive 의 실제 상태와 동기화되지 않는다.
`UploadLedgerRepository` 에는 전체 삭제(`clear`)만 있고 개별 삭제가 없으며,
Drive 화면에서 파일을 지워도 원장을 정리하지 않는다.

그래서 **Drive 쪽에서 파일을 지우면 앱은 영영 모른다.** 배지는 계속 붙어 있고,
"올린 사진 기기에서 삭제" 는 그 사진을 지울 대상으로 본다. 원본이 사라질 수 있는
경로이므로 따로 다뤄야 한다.
