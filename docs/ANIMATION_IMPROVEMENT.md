# 애니메이션 개선 분석

> 2026-09-08 기준 코드(`54adc2b`)를 대상으로 "전체적으로 애니메이션이 깔끔해지려면 무엇을 바꿔야 하는가"를
> 기술·구조·복잡성 세 축으로 분석한 문서. **2차 검토(같은 날)에서 항목별 CPU·GPU·전력·지연 비용을 재평가해
> 4개를 바꾸고 3개를 제외했다** (§3 각 표의 "비용" 열과 §6 참고). 실행 로드맵은 §7.

## 1. 진단 — 지금 상태

코드를 훑어 확인한 사실 (추정 아님):

| 항목 | 현재 |
|---|---|
| 애니메이션 API 사용 | `AnimatedVisibility` 5곳(전부 상세보기 컨트롤). `AnimatedContent`, `Crossfade`, `animate*AsState`, `Animatable`, `animateItem`, `SharedTransitionLayout` **사용 없음** |
| 화면 전환 | `NavDisplay` 기본값 그대로. `transitionSpec / popTransitionSpec / predictivePopTransitionSpec` 미지정 |
| Predictive back | 매니페스트에 `enableOnBackInvokedCallback` 없음 → Android 14+ 뒤로가기 미리보기 비활성 |
| 선택 모드 진입 | `if (selectionMode) SelectionTopBar else GalleryTopBar` — 상단바가 **순간 교체**. 하단 액션 바도 `if` 로 즉시 등장 |
| 썸네일 선택 표시 | `padding(10.dp)` 를 조건부로 붙여 **크기가 튐**. 체크 아이콘은 즉시 표시 |
| 배너(업로드 진행·실패·기간·부분 접근) | `if` 로 즉시 나타나고 사라짐 → 그리드가 아래로 **점프** |
| 삭제·휴지통 후 그리드 | 항목이 즉시 사라짐(`animateItem` 없음) |
| 그리드 → 상세보기 | 기본 전환(페이드 계열). 썸네일이 확대되며 이어지는 연출 없음 |
| 상세보기 확대 | `scale = 2.5f` 직접 대입 → 두 번 탭 시 **순간 점프**, 손 뗄 때 경계 밖 보정·스프링 없음 |
| 상세보기 컨트롤 | `AnimatedVisibility` 기본(fade + 세로 expand). 화면 중앙 재생 버튼이 "펴지며" 나타나 어색 |
| 영상 진행 폴링 | 컨트롤이 **숨겨진 뒤에도** 400ms 마다 `currentPosition` 을 읽어 상태를 갱신 → 보이지 않는 재구성이 계속됨 (전력 낭비, 이번 검토에서 발견) |
| 업로드 진행 바 | DB 가 1초마다 갱신되는 값을 그대로 그려 **계단식**으로 움직임 |
| 드래그 자동 스크롤 | `scrollBy()` + `delay(10ms)` 루프 — 프레임과 동기화되지 않아 미세하게 끊길 수 있음 |
| 리스트 화면(Drive·업로드 목록·휴지통) | 항목 추가/삭제 애니메이션 없음 |
| 모션 토큰 | 지속 시간·이징이 코드 곳곳의 리터럴(3초 자동 숨김 등). 중앙 정의 없음 |
| 성능 측정 | 2단계에서 **디버그 빌드** 플링 시 janky 16.7%, 90th 65ms 관측. 릴리스 빌드 미측정 |

사용 가능한 도구 (의존성 트리에서 확인):

- Compose `animation` / `foundation` **1.12.0** — `SharedTransitionLayout`, `Modifier.animateItem`, `AnimatedContent`, `LookaheadScope` 모두 사용 가능
- material3 **1.4.0** — `MotionScheme` 존재, `MaterialTheme(colorScheme, motionScheme, shapes, typography)` 오버로드 있음. `MaterialExpressiveTheme` 는 `@ExperimentalMaterial3ExpressiveApi`
- Navigation 3 UI **1.1.7** — `NavDisplay(transitionSpec, popTransitionSpec, predictivePopTransitionSpec, sizeTransform)` 전역 지정 + `NavDisplay.transitionSpec { }` 로 엔트리별 메타데이터 지정 가능

## 2. 원칙 — 비용 기준 포함

1. **상태가 바뀌면 화면도 "움직여서" 바뀐다.** `if` 로 컴포저블을 넣고 빼는 자리는 전부 `AnimatedVisibility` / `AnimatedContent` / `animateItem` 후보다.
2. **모션 값은 한곳에서.** 지속 시간·이징·스프링 스펙을 `core/ui/motion` 토큰으로 정의하고 화면은 토큰만 참조한다.
3. **프레임 예산이 먼저.** 애니메이션은 60/120Hz 에서 프레임을 놓치면 오히려 더 지저분해 보인다. 재구성 비용을 먼저 줄이고, 릴리스 빌드로 측정한다.
4. **ViewModel 은 의도, UI 는 전환.**
5. **비용의 세 종류를 구분한다.** 이번 검토의 기준.
   - **① 전환 순간 비용**: 사용자가 조작한 직후 150~400ms 동안만 드는 비용. 사실상 공짜 — 그 시간엔 어차피 화면을 다시 그린다.
   - **② 연속/무한 비용**: 화면이 켜진 동안 매 프레임 재구성·재그리기를 유발하는 것(shimmer, 매 프레임 진행 보간, 끝나지 않는 트윈). **전력 소모의 실제 주범.** 반드시 시간 상한이나 가시성 조건을 둔다.
   - **③ 상시 구조 비용**: 애니메이션이 돌지 않을 때도 매 레이아웃 패스에 붙는 오버헤드(예: `LookaheadScope` 의 이중 측정). 얻는 것이 아주 크지 않으면 피한다.
6. **`graphicsLayer` 로 움직인다.** `alpha / scale / translation` 은 레이아웃을 건드리지 않아 GPU 합성만으로 끝난다. `size / padding / height` 애니메이션은 매 프레임 재측정을 부르므로 원칙적으로 피한다.

## 3. 기술 개선 항목

각 항목: 현재 → 개선 → 사용할 API → 난이도(⚪ 낮음 / 🔵 중간 / 🔴 높음) / 체감 효과(★) / **비용**(🟢 전환 순간만 · 🟡 조건부 · 🔴 연속/상시 → 대책 필수) / **판정**.

### 3.1 전역

| # | 항목 | 개선 | API | 난이도/효과 | 비용 | 판정 |
|---|---|---|---|---|---|---|
| G1 | 모션 토큰 | `core/ui/motion/Motion.kt` 에 `Durations`(short 150 / medium 250 / long 400ms), `Easings`, `Springs` 정의. `LocalReduceMotion` — 시스템 `ANIMATOR_DURATION_SCALE == 0` 이면 즉시 전환 | `tween`, `spring`, `CompositionLocal` | ⚪ / ★★ | 🟢 런타임 비용 0 | **유지** |
| G2 | Material 모션 스킴 | `MaterialTheme(motionScheme = MotionScheme.standard())` — 표준 스킴만. Expressive 는 오버슈트 스프링이 길어(전환 시간 ↑) 배터리보다 "느려 보임" 문제 | material3 1.4 `MotionScheme` | ⚪ / ★ | 🟢 | **유지(standard 로)** |
| G3 | 화면 전환 | `NavDisplay` 전역 `transitionSpec`(fade-through 250ms), `popTransitionSpec`, `predictivePopTransitionSpec`. 전환 중 두 화면이 동시에 그려지지만 250ms 한정 | Nav3 `NavDisplay` | 🔵 / ★★★ | 🟢 (전체 화면 alpha 는 오프스크린 버퍼 1장 — 시스템 전환도 같은 비용) | **유지** |
| G4 | Predictive back | `enableOnBackInvokedCallback="true"`. 제스처 중 양쪽 화면이 살아 있지만 손가락이 닿은 동안만 | Manifest | ⚪ / ★★ | 🟢 | **유지** |

### 3.2 갤러리 그리드

| # | 항목 | 개선 | API | 난이도/효과 | 비용 | 판정 |
|---|---|---|---|---|---|---|
| L1 | 선택 모드 상단바 | `AnimatedContent(targetState = selectionMode)` 페이드 + 짧은 슬라이드 | `AnimatedContent` | ⚪ / ★★★ | 🟢 | **유지** |
| L2 | 하단 액션 바 | `AnimatedVisibility(slideInVertically + fadeIn)` | `AnimatedVisibility` | ⚪ / ★★★ | 🟢 | **유지** |
| L3 | 썸네일 선택 | ~~`animateDpAsState` 로 padding~~ → **`graphicsLayer { scaleX = scaleY = animatedScale }`** 로 0.9 배 축소. padding 은 레이아웃 재측정을 부르고, 하루 전체 선택 시 화면의 ~30셀이 동시에 재측정된다. scale 은 GPU 합성만. 체크 아이콘 `scaleIn + fadeIn`, 스크림은 `drawWithContent` 로 | `animateFloatAsState`, `graphicsLayer` | ⚪ / ★★★ | 🟢 (변경 후) | **변경** |
| L4 | 삭제·휴지통 후 | `Modifier.animateItem()`. **단, 대량 변경 시 끈다**: 기간 필터·즐겨찾기 필터 전환처럼 키 집합이 통째로 바뀌면 화면의 모든 항목이 동시에 이동 애니메이션을 타서 산만하고 프레임을 잡아먹는다 → ViewModel 이 `bulkChange` 플래그(필터 변경 직후 1회)를 내리면 `animateItem(placementSpec = null, fadeInSpec = null)` 로 비활성 | `LazyGridItemScope.animateItem` | ⚪ / ★★★ | 🟡 (조건부) | **유지 + 조건** |
| L5 | 배너 4종 | `AnimatedVisibility(expandVertically + fade)`. 배너 아래 `LazyVerticalGrid` 가 200ms 동안 매 프레임 재측정된다(가시 셀 ~30개). 배너 등장은 드문 이벤트(업로드 시작·필터 적용)라 허용. 지속 시간은 200ms 로 짧게 | `AnimatedVisibility` | ⚪ / ★★ | 🟡 (드물게 200ms) | **유지(200ms)** |
| L6 | 날짜 헤더 체크 | 세 상태 `Crossfade` | `Crossfade` | ⚪ / ★ | 🟢 | **유지** |
| L7 | 드래그 자동 스크롤 | `delay(10)` 루프 → `withFrameNanos` 로 프레임당 1회. **현재보다 비용이 줄어든다**(10ms 타이머 ≈ 100Hz 웨이크업 → 디스플레이 주사율로 정렬) | `withFrameNanos` | ⚪ / ★ | 🟢 (개선) | **유지** |
| L8 | 항목 등장 | 첫 로드 1회 그리드 페이드-인. 스크롤 중 등장 애니메이션은 넣지 않는다 | — | ⚪ / ★ | 🟢 | **유지** |
| L9 | 롱프레스 피드백 | 눌린 셀 `scale 0.96` 스프링 | `Animatable` | 🔵 / ★ | 🟢 | 유지(후순위) |

### 3.3 그리드 ↔ 상세보기

| # | 항목 | 개선 | API | 난이도/효과 | 비용 | 판정 |
|---|---|---|---|---|---|---|
| V1 | 썸네일 → 상세 확대 연출 | ~~`SharedTransitionLayout` 로 `AppNavigation` 감싸기~~ → **자체 히어로 오버레이**로 교체. 이유: `SharedTransitionLayout` 은 `LookaheadScope` 를 깔아 **감싼 모든 화면을 매 레이아웃 패스마다 두 번 측정**한다(③ 상시 구조 비용). 6천 장 그리드가 가끔 쓰는 전환 하나 때문에 항상 그 비용을 낸다. 대신: 탭 시 `onGloballyPositioned` 로 썸네일 사각형을 잡아 `MediaViewerKey` 에 실어 보내고, 상세보기 진입 시 **메모리 캐시의 썸네일**을 그 사각형에서 전체 화면으로 `Animatable` 로 키운다(250ms, `graphicsLayer`). 끝나면 원본 로드(D6). 뒤로 갈 때는 반대. 비용은 전환 중에만 발생 | `Animatable`, `graphicsLayer`, Coil 메모리 캐시 키 | 🔴 / ★★★★ | 🟢 (변경 후) — 원래 안은 🔴 상시 | **변경** |
| V2 | 대안(1차) | 상세보기 엔트리에 `NavDisplay.transitionSpec { fadeIn + scaleIn(0.92f) }`. V1 이 들어오기 전 임시 | Nav3 메타데이터 | ⚪ / ★★ | 🟢 | **유지** |
| V3 | 아래로 스와이프 닫기 | 드래그 거리에 비례해 `graphicsLayer` 축소·투명도, 임계값 넘으면 pop. 제스처 중에만 비용 | `pointerInput` + `Animatable` | 🔵 / ★★★ | 🟢 | **유지** |

### 3.4 상세보기 내부

| # | 항목 | 개선 | API | 난이도/효과 | 비용 | 판정 |
|---|---|---|---|---|---|---|
| D1 | 확대/축소 | `ZoomState` 를 `Animatable` 로. 두 번 탭은 탭 지점 중심 스프링, 손 뗄 때 경계 보정. 핀치 중엔 즉시 반영. 이미 `graphicsLayer` 라 레이아웃 비용 없음. **원본 디코딩 상한**: Coil `size(4096)` 을 명시해 50MP 사진이 통째로 메모리에 올라오는 것을 막는다(확대 6배에서 픽셀이 조금 무뎌지는 것은 감수. 서브샘플링 라이브러리는 도입하지 않음) | `Animatable`, `spring` | 🔵 / ★★★ | 🟢 | **유지 + 디코딩 상한** |
| D2 | 컨트롤 표시 | 상·하단 `slide + fade`, 중앙 재생 버튼은 `fade + scale` 만 | `AnimatedVisibility` | ⚪ / ★★ | 🟢 | **유지** |
| D3 | 엣지 핸드오프 | 확대 상태에서 가장자리에 닿으면 페이저로 넘기기 | — | 🔴 / ★ | — | **제외** (복잡도 대비 효과 낮음. 두 번 탭으로 1배 복귀 후 스와이프가 관행) |
| D4 | 진행 표시 | ~~매 프레임 `withFrameNanos` 보간~~ → **제외**. 영상 재생 내내 Compose 오버레이가 매 프레임 재구성되는 ② 연속 비용. 대신 두 가지: (a) **컨트롤이 숨겨지면 폴링 자체를 멈춘다**(현재 버그 수준의 낭비 제거), (b) 보일 때만 폴링 주기를 250ms 로 하고 `Slider` 는 애니메이션 없이 갱신. 손을 뗀 직후 튐만 `animateFloatAsState(tween(150))` 1회 | 가시성 조건 | ⚪ / ★ | 🟢 (변경 후) — 원래 안은 🔴 | **변경** |
| D5 | 정보 패널 | `AnimatedVisibility(expandVertically)` | — | ⚪ / ★ | 🟢 | **유지** |
| D6 | 이미지 로드 | 썸네일(메모리 캐시) → 원본 2단계. `placeholderMemoryCacheKey` 로 회색 화면 없이. 추가 디코딩 없음(썸네일은 이미 캐시) | Coil | 🔵 / ★★ | 🟢 (오히려 체감 지연 ↓) | **유지** |
| D7 | 영상 폴링(신규) | 현재 컨트롤 표시 여부와 무관하게 400ms 폴링 → `if (controlsVisible)` 안에서만 루프. 재생 중 컨트롤이 숨겨진 3초 후부터는 상태 갱신 0회 | `LaunchedEffect(controlsVisible)` | ⚪ / — | 🟢 (전력 절감) | **추가** |

### 3.5 리스트·기타 화면

| # | 항목 | 개선 | API | 난이도/효과 | 비용 | 판정 |
|---|---|---|---|---|---|---|
| R1 | Drive·업로드 목록·휴지통 | `animateItem()`. 목록이 수십 개 수준이라 대량 변경 문제 없음 | `LazyItemScope.animateItem` | ⚪ / ★★ | 🟢 | **유지** |
| R2 | 업로드 진행 바 | ~~1초 선형 트윈으로 다음 갱신까지 보간~~ → **200ms 이즈 1회**로 변경. 선형 트윈은 업로드가 진행되는 내내(수십 분) 매 프레임 재구성되는 ② 연속 비용. DB 갱신마다 200ms 만 움직이면 프레임 재구성 시간이 20% 로 줄고 계단 느낌은 사라진다 | `animateFloatAsState(tween(200))` | ⚪ / ★★ | 🟢 (변경 후) — 원래 안은 🔴 | **변경** |
| R3 | 설정 토글·행 | M3 `Switch` 는 G2 로 해결 | — | ⚪ / ★ | 🟢 | 유지 |
| R4 | 다이얼로그 → 바텀시트 | 이름 변경·앨범 이동·기간 선택을 `ModalBottomSheet` 로. M3 모션 포함 | `ModalBottomSheet` | 🔵 / ★★ | 🟢 | 유지(후순위) |
| R5 | 스켈레톤(shimmer) | — | — | 🔵 / ★ | 🔴 무한 애니메이션 | **제외**. 로딩 중 매 프레임 그라디언트를 다시 그린다. Drive 목록 로딩(~1초)엔 원형 인디케이터로 충분. 정적 플레이스홀더(회색 박스, 애니메이션 없음)만 허용 |

### 3.6 검토로 바뀐 것 요약

| 항목 | 원안 | 문제 | 결론 |
|---|---|---|---|
| V1 | `SharedTransitionLayout` | 모든 화면 상시 이중 측정(③) | 자체 히어로 오버레이 |
| L3 | padding 애니메이션 | 셀 재측정 × 동시 30셀 | `graphicsLayer` scale |
| D4 | 매 프레임 진행 보간 | 재생 내내 매 프레임 재구성(②) | 폴링 가시성 조건 + 250ms |
| R2 | 1초 선형 트윈 | 업로드 내내 매 프레임 재구성(②) | 200ms 이즈 1회 |
| L4 | 무조건 `animateItem` | 필터 전환 시 수백 개 동시 이동 | 대량 변경 시 비활성 |
| D3 | 엣지 핸드오프 | 복잡도 대비 효과 낮음 | 제외 |
| R5 | shimmer | 무한 애니메이션(②) | 제외 |
| G2 | Expressive 스킴 | 오버슈트로 전환이 길어짐 | standard 스킴 |
| D7 | (없음) | 숨겨진 컨트롤 폴링 계속 | 신규 추가 — 현재 낭비 제거 |

## 4. 구조 개선

애니메이션을 "붙이기 쉬운" 코드 구조가 먼저다.

### 4.1 모션 토큰 모듈 — `core/ui/motion`

```kotlin
object Motion {
    object Duration { const val SHORT = 150; const val MEDIUM = 250; const val LONG = 400 }
    val standardEasing = FastOutSlowInEasing
    fun <T> quick(): FiniteAnimationSpec<T> = tween(Duration.SHORT, easing = standardEasing)
    fun <T> settle(): SpringSpec<T> = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioNoBouncy)
}
val LocalReduceMotion = staticCompositionLocalOf { false }   // Settings.Global.ANIMATOR_DURATION_SCALE == 0f
```

- 화면 코드는 토큰만 쓴다. 리터럴 `tween(300)` 은 detekt `MagicNumber` 로 막힌다.
- `LocalReduceMotion` 이 true 면 `snap()` 으로 바꾸는 헬퍼(`Motion.enter()/exit()`)로 접근성 설정을 존중한다. 이것이 켜진 기기에서는 이 문서의 모든 항목이 비용 0 이 된다.
- 지속 시간을 원안(300/500)보다 짧게(250/400) 잡았다. 짧을수록 두 화면이 동시에 살아 있는 시간과 재측정 프레임 수가 줄어든다.

### 4.2 전환 정의를 내비게이션 키 옆으로

- `AppNavigation` 한 곳에서 `entry<MediaViewerKey>(metadata = NavDisplay.transitionSpec { ... })` 로 선언한다. 화면 컴포저블은 자기 전환을 모른다.
- 전역 기본값(fade-through)과 예외(상세보기) 두 종류만 둔다.

### 4.3 큰 화면 파일 분할

| 파일 | 현재 | 분할 |
|---|---|---|
| `GalleryScreen.kt` | 651줄 | `GalleryRoute.kt`, `GalleryTopBars.kt`, `GalleryBanners.kt`, `GalleryScreen.kt`(Scaffold 조립) |
| `MediaViewerScreen.kt` | 789줄 | `MediaViewerRoute.kt`, `ImagePage.kt`(+`ZoomState`), `VideoPage.kt`(+`VideoControls`), `ViewerChrome.kt` |

동작 변화 없는 순수 이동. 전환 코드가 들어가기 전에 한다.

### 4.4 파생 상태 분리 — 선택이 바뀔 때 목록을 다시 계산하지 않기

현재 `GalleryViewModel.contentFlow` 는 `combine(media, selectedIds, uploadSummary, isMutating)` 안에서

```kotlin
sections = groupByDate(items)          // O(n)
albums   = albumsFrom(items)           // O(n) + 정렬
selectedAllFavorite = items.filter { it.id in selected }.all { ... }   // O(n)
```

를 **선택 하나 토글할 때마다** 다시 계산한다. 6,119장 기준 매번 수천 번 반복 + 새 `List` 할당. 애니메이션이 들어가면 첫 프레임에서 GC·계산이 겹쳐 끊긴다. **이 항목은 애니메이션과 무관하게도 성능·전력에 순이익**이다.

```kotlin
// 목록에서만 파생되는 값은 media 흐름에서 한 번 계산
private val catalog = combine(mediaRepository.observeMedia(filter), dateRange) { all, range ->
    val items = all.filterByDate(range)
    Catalog(items, groupByDate(items), albumsFrom(items), items.associateBy { it.id })
}

// 선택은 O(k) 로만 결합
combine(catalog, selectedIds, uploadSummary, isMutating) { c, selected, ... ->
    selectedAllFavorite = selected.all { c.byId[it]?.isFavorite == true }
}
```

- `sections` 인스턴스가 선택 변경에도 동일하게 유지되므로 `LazyVerticalGrid` 가 대부분의 썸네일 재구성을 건너뛴다.
- `Content` 에 `@Immutable`, 컬렉션은 `kotlinx.collections.immutable` 로 바꾸면 스킵률이 더 오른다(선택).

### 4.5 애니메이션 가능한 상태 모델

- `isSelectionMode` 같은 불리언 파생값을 `AnimatedContent(targetState)` 키로 직접 쓴다.
- 삭제 후 목록 반영은 `ContentObserver` → 재조회(300ms 디바운스). `animateItem` 이 자연스럽게 보이려면 **낙관적 제거**(동의 OK 직후 UI 목록에서 먼저 제거, 재조회로 확정)를 고려한다. 재조회 결과가 항상 진실.

## 5. 복잡성·성능

| 항목 | 내용 |
|---|---|
| 측정 기준 확립 | 디버그 수치(janky 16.7%)는 의미가 약하다. **릴리스(R8) 빌드**로 `dumpsys gfxinfo` 를 다시 재고, Macrobenchmark(`FrameTimingMetric`) 로 "그리드 플링", "선택 모드 진입", "상세보기 열기" 를 추적 |
| **전력 측정** | 애니메이션 변경 전후로 `adb shell dumpsys batterystats --reset` → 5분 시나리오(갤러리 스크롤 2분, 영상 재생 2분, 대기 1분) → `batterystats` 의 앱 CPU 시간·wakeup 비교. 특히 D7(숨겨진 컨트롤 폴링 제거) 전후 영상 재생 CPU 시간이 줄어야 정상 |
| Baseline Profile | 콜드 스타트·첫 플링 경로 AOT 컴파일 → 첫 스크롤 프레임 드롭 ↓, JIT 컴파일 CPU ↓ (전력에도 이득) |
| 재구성 관찰 | `enableComposeCompilerMetrics` 로 `GalleryGrid`·`MediaThumbnail` skippable 확인. Layout Inspector 로 선택 토글 1회의 재구성 범위 확인 |
| 썸네일 크기 힌트 | `AsyncImage` 에 `ImageRequest.size(px)` 명시 → 디코딩 크기가 첫 프레임에 확정 |
| `MediaStoreThumbnailFetcher` | Coil 디코더 디스패처 병렬도를 4~8 로 제한하면 스크롤 중 CPU 경쟁 ↓ (측정 후) |
| 오버드로 | 썸네일 `background + 이미지 + 배지` 3겹. 로드 완료 시 배경 생략 |
| 상시 비용 금지 | `LookaheadScope`/`SharedTransitionLayout` 를 루트에 두지 않는다(V1 참고). 무한 `rememberInfiniteTransition` 은 로딩 인디케이터 외 금지 |
| 복잡성 한도 | 히어로 오버레이(V1)·낙관적 제거는 각각 새 상태 머신. 한 번에 하나씩, 순수 계산(경계 보정 함수 등)을 분리해 단위 테스트 |

## 6. 비용 총평

- 이 문서의 항목 대부분은 **① 전환 순간 비용**이다. 사용자가 조작한 직후 150~250ms 동안만 GPU 합성이 늘고, 그 시간엔 어차피 화면을 다시 그리므로 배터리·발열에 유의미한 차이가 없다.
- 위험했던 것은 **연속 애니메이션 3개**(D4·R2·R5)와 **상시 구조 비용 1개**(V1 원안)였고, 전부 바꾸거나 뺐다. 이 네 개를 원안대로 넣었다면 "애니메이션 넣었더니 폰이 뜨겁다"가 될 수 있는 항목들이었다.
- 반대로 **현재 코드에 이미 있는 낭비**(D7 폴링, L7 10ms 타이머, §4.4 O(n) 재계산)를 고치는 것이 포함돼 있어, 1단계를 끝내면 애니메이션이 늘었는데 CPU 시간은 **줄어드는** 쪽으로 기대한다. 이것을 §5 의 전력 측정으로 확인한다.
- `LocalReduceMotion` 을 존중하므로, 시스템 설정에서 애니메이션을 끈 사용자에게는 비용이 0 이다.

## 7. 우선순위 로드맵

### 1단계 — 기반 + 값싼 승리 + 현재 낭비 제거 ✅ (2026-09-08 완료)
G1 모션 토큰 → **D7 숨겨진 컨트롤 폴링 중단** → **§4.4 파생 상태 분리** → G4 predictive back → G3 전역 전환 → L1·L2 선택 모드 → L3 썸네일 선택(`graphicsLayer`) → L4 `animateItem`(+대량 변경 조건) → L5 배너(200ms) → D2 컨트롤 → R2 진행 바(200ms 이즈) → L7 프레임 동기 자동 스크롤.
*"즉시 교체" 느낌이 대부분 사라지고, CPU 시간은 오히려 줄어야 한다.*
구현 메모: D7 은 폴링 대신 `Player.Listener.onIsPlayingChanged` 로 재생 상태를 받고 위치 폴링은 컨트롤이 보일 때만(250ms). L4 는 `GalleryUiState.Content.animateItemChanges` 로 필터 전환 직후 1회 생략. 실기기 프레임·전력 측정(§5·§8)은 기기 재연결 후 수행 예정.

### 2단계 — 상세보기 다듬기 (2~3일)
§4.3 파일 분할 → D1 확대 스프링·경계 보정(+디코딩 상한) → V2 상세보기 진입 전환 → V3 아래로 스와이프 닫기 → D6 썸네일→원본 2단계 → R1 리스트 `animateItem` → R4 바텀시트.

### 3단계 — 시그니처 모션 (측정 후 결정)
V1 자체 히어로 오버레이 → G2 standard 모션 스킴 → Macrobenchmark·Baseline Profile 모듈.
*1·2단계 후 릴리스 프레임·전력 수치가 안정된 뒤 착수.*

## 8. 검증 방법

- **프레임**: 릴리스 빌드에서 `dumpsys gfxinfo` — 플링 janky < 5%, 95th < 16ms(60Hz) / < 8ms(120Hz)
- **전력**: §5 의 `batterystats` 시나리오. 변경 전후 앱 CPU 시간이 증가하면 해당 커밋을 되돌린다
- **회귀 방지**: Macrobenchmark `FrameTimingMetric` 을 CI(주 1회 물리 기기 or FTL)
- **육안**: "애니메이션 배율 5x" 로 겹침·점프 확인, "애니메이션 끄기" 에서 `LocalReduceMotion` 즉시 전환 확인
- **재구성**: Layout Inspector — 선택 토글 1회에 썸네일 재구성이 가시 개수(≈30) 를 넘지 않아야 함

## 9. 리스크·주의

- 히어로 오버레이(V1)는 썸네일 사각형을 키에 실어 보내므로 회전·창 크기 변경 시 좌표가 어긋날 수 있다 → 좌표 유효성 검사 후 실패 시 V2 로 폴백
- Predictive back 은 삼성 One UI 에서 미리보기 강도가 다르다. `BackHandler`(선택 해제·확대 해제) 우선순위 확인
- `animateItem` 은 key 안정성에 의존. 그리드 key 는 `item.id`, 헤더는 `"header-$date"` 로 안정적
- 낙관적 제거는 MediaStore 재조회와 경합 → 재조회 결과를 항상 진실로 삼는다
- 애니메이션을 넣을수록 `MagicNumber` 위반이 늘어난다 → G1 토큰을 먼저
