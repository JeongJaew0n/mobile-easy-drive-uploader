# 애니메이션 개선 분석

> 2026-09-08 기준 코드(`54adc2b`)를 대상으로 "전체적으로 애니메이션이 깔끔해지려면 무엇을 바꿔야 하는가"를
> 기술·구조·복잡성 세 축으로 분석한 문서. 실행 로드맵은 마지막 절.

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
| 그리드 → 상세보기 | 기본 전환(페이드 계열). 썸네일이 확대되며 이어지는 **공유 요소 전환 없음** |
| 상세보기 확대 | `scale = 2.5f` 직접 대입 → 두 번 탭 시 **순간 점프**, 손 뗄 때 경계 밖 보정·스프링 없음 |
| 상세보기 컨트롤 | `AnimatedVisibility` 기본(fade + 세로 expand). 화면 중앙 재생 버튼이 "펴지며" 나타나 어색 |
| 업로드 진행 바 | DB 가 1초마다 갱신되는 값을 그대로 그려 **계단식**으로 움직임 |
| 드래그 자동 스크롤 | `scrollBy()` + `delay(10ms)` 루프 — 프레임과 동기화되지 않아 미세하게 끊길 수 있음 |
| 리스트 화면(Drive·업로드 목록·휴지통) | 항목 추가/삭제 애니메이션 없음 |
| 모션 토큰 | 지속 시간·이징이 코드 곳곳의 리터럴(3초 자동 숨김 등). 중앙 정의 없음 |
| 성능 측정 | 2단계에서 **디버그 빌드** 플링 시 janky 16.7%, 90th 65ms 관측. 릴리스 빌드 미측정 |

사용 가능한 도구 (의존성 트리에서 확인):

- Compose `animation` / `foundation` **1.12.0** — `SharedTransitionLayout`, `Modifier.animateItem`, `AnimatedContent`, `LookaheadScope` 모두 사용 가능
- material3 **1.4.0** — `MotionScheme` 존재, `MaterialTheme(colorScheme, motionScheme, shapes, typography)` 오버로드 있음. `MaterialExpressiveTheme` 는 `@ExperimentalMaterial3ExpressiveApi`
- Navigation 3 UI **1.1.7** — `NavDisplay(transitionSpec, popTransitionSpec, predictivePopTransitionSpec, sizeTransform)` 전역 지정 + `NavDisplay.transitionSpec { }` 로 엔트리별 메타데이터 지정 가능. `SharedEntryInSceneNavEntryDecorator` 로 공유 요소 전환 지원

## 2. 원칙

1. **상태가 바뀌면 화면도 "움직여서" 바뀐다.** `if` 로 컴포저블을 넣고 빼는 자리는 전부 `AnimatedVisibility` / `AnimatedContent` / `animateItem` 후보다.
2. **모션 값은 한곳에서.** 지속 시간·이징·스프링 스펙을 `core/ui/motion` 토큰으로 정의하고 화면은 토큰만 참조한다. 일관성과 "모션 줄이기" 접근성 대응이 같은 곳에서 해결된다.
3. **프레임 예산이 먼저.** 애니메이션은 60/120Hz 에서 프레임을 놓치면 오히려 더 지저분해 보인다. 재구성 비용을 먼저 줄이고, 릴리스 빌드로 측정한다.
4. **ViewModel 은 의도, UI 는 전환.** ViewModel 은 "선택 모드다/아니다"만 내고, 어떻게 전환할지는 Compose 계층이 결정한다. 현재 구조가 이미 그렇게 되어 있으므로 유지한다.

## 3. 기술 개선 항목

각 항목: 현재 → 개선 → 사용할 API → 난이도(⚪ 낮음 / 🔵 중간 / 🔴 높음) / 체감 효과(★).

### 3.1 전역

| # | 항목 | 개선 | API | 난이도 / 효과 |
|---|---|---|---|---|
| G1 | 모션 토큰 | `core/ui/motion/Motion.kt` 에 `Durations`(short 150 / medium 300 / long 500ms), `Easings`, `Springs`(gentle·snappy) 정의. `LocalReduceMotion` 을 만들어 시스템 `ANIMATOR_DURATION_SCALE == 0` 이면 즉시 전환 | `tween`, `spring`, `CompositionLocal` | ⚪ / ★★ (이후 모든 항목의 기반) |
| G2 | Material 모션 스킴 | `EasyGalleryTheme` 에서 `MaterialTheme(motionScheme = MotionScheme.expressive())` 적용 → 버튼·스위치·바텀시트 등 M3 컴포넌트 내부 모션이 통일됨 | material3 1.4 `MotionScheme` | ⚪ / ★ |
| G3 | 화면 전환 | `NavDisplay` 에 전역 `transitionSpec`(fade-through: 페이드 + 살짝 확대), `popTransitionSpec`(반대), `predictivePopTransitionSpec`(뒤로 제스처 진행률에 따라 축소·이동) 지정. 상세보기는 `NavDisplay.transitionSpec { }` 메타데이터로 별도(§3.3) | Navigation 3 `NavDisplay` 파라미터, `AnimatedContentTransitionScope` | 🔵 / ★★★ |
| G4 | Predictive back | 매니페스트 `<application android:enableOnBackInvokedCallback="true">`. Nav3 는 이미 예측 뒤로가기를 소비하므로 G3 의 `predictivePopTransitionSpec` 이 곧바로 살아난다. 선택 모드의 `BackHandler` 도 그대로 동작 | Manifest, Nav3 | ⚪ / ★★ |

### 3.2 갤러리 그리드

| # | 항목 | 개선 | API | 난이도 / 효과 |
|---|---|---|---|---|
| L1 | 선택 모드 상단바 | `if` 교체 → `AnimatedContent(targetState = selectionMode)` 로 페이드 + 살짝 위/아래 슬라이드 | `AnimatedContent`, `slideInVertically`, `fadeIn` | ⚪ / ★★★ |
| L2 | 하단 액션 바 | `Scaffold.bottomBar` 안에서 `AnimatedVisibility(selectionMode, enter = slideInVertically { it } + fadeIn, exit = 반대)` | `AnimatedVisibility` | ⚪ / ★★★ |
| L3 | 썸네일 선택 | 인셋 `padding` 을 `animateDpAsState(if (selected) 10.dp else 0.dp, spring)` 으로. 체크 아이콘은 `AnimatedVisibility(scaleIn + fadeIn)`. 선택 시 짙은 스크림 `animateColorAsState` | `animateDpAsState`, `scaleIn` | ⚪ / ★★★ |
| L4 | 삭제·휴지통·이동 후 | `items(..., key = id)` 에 `Modifier.animateItem()` — 사라진 항목은 fade-out, 남은 항목이 미끄러져 채움. key 가 이미 안정(`item.id`)이라 바로 적용 가능 | `LazyGridItemScope.animateItem` | ⚪ / ★★★ |
| L5 | 배너 4종 | `if` → `AnimatedVisibility(expandVertically + fadeIn / shrinkVertically + fadeOut)`. 그리드가 점프하지 않고 밀려 내려감 | `AnimatedVisibility` | ⚪ / ★★ |
| L6 | 날짜 헤더 체크 | 세 상태(없음·일부·전부)를 `Crossfade` 또는 `AnimatedContent` 로 교차 | `Crossfade` | ⚪ / ★ |
| L7 | 드래그 자동 스크롤 | `delay(10)` 루프 → `withFrameNanos` 기반 루프로 프레임마다 dt 에 비례해 스크롤. 가장자리 가속 곡선은 `EaseIn` | `withFrameNanos`, `scrollBy` | ⚪ / ★ |
| L8 | 항목 등장 | 첫 로드 시 그리드 전체 페이드-인(`AnimatedVisibility` 1회). 스크롤 중 새 항목 등장 애니메이션은 **넣지 않는다**(수천 장 그리드에서 산만함) | — | ⚪ / ★ |
| L9 | 롱프레스 피드백 | 이미 햅틱 있음. 여기에 눌린 썸네일 `scale 0.96` 짧은 스프링 추가 | `Animatable`, `pointerInput` | 🔵 / ★ |

### 3.3 그리드 ↔ 상세보기 (가장 큰 체감 차이)

| # | 항목 | 개선 | API | 난이도 / 효과 |
|---|---|---|---|---|
| V1 | 공유 요소 전환 | 탭한 썸네일이 제자리에서 확대되며 상세보기로 이어지고, 뒤로 가면 다시 그 칸으로 돌아간다. `AppNavigation` 을 `SharedTransitionLayout` 으로 감싸고, 썸네일과 상세 이미지에 같은 key(`"media-$id"`) 로 `sharedElement`/`sharedBounds`. Nav3 의 `AnimatedContentScope` 를 `LocalNavAnimatedContentScope` 로 받아 `animatedVisibilityScope` 에 넘긴다 | `SharedTransitionLayout`(Compose 1.12, `@ExperimentalSharedTransitionApi`), Nav3 `SharedEntryInSceneNavEntryDecorator` | 🔴 / ★★★★ |
| V2 | 대안(1차) | V1 이 무거우면 상세보기 엔트리에만 `NavDisplay.transitionSpec { fadeIn + scaleIn(0.92f) }` / pop 은 `scaleOut + fadeOut` 을 메타데이터로 부여. V1 의 절반 효과를 1/10 비용으로 | Nav3 메타데이터 | ⚪ / ★★ |
| V3 | 뒤로 가는 제스처 | 상세보기에서 **아래로 스와이프해 닫기**(사진 앱 관행). 드래그 거리에 비례해 축소·투명도, 임계값 넘으면 pop. V1 과 결합하면 썸네일 자리로 빨려 들어감 | `pointerInput` + `Animatable`, Nav3 `predictivePopTransitionSpec` | 🔵 / ★★★ |

### 3.4 상세보기 내부

| # | 항목 | 개선 | API | 난이도 / 효과 |
|---|---|---|---|---|
| D1 | 확대/축소 | `ZoomState` 의 `scale/offset` 을 `Animatable` 로. 두 번 탭은 **탭한 지점을 중심으로** 스프링 애니메이션, 손을 뗄 때 경계 밖이면 스프링으로 되돌림, 1배 미만으로 줄이면 1배로 복귀. 핀치 중에는 즉시 반영(추적), 놓았을 때만 애니메이션 | `Animatable`, `animateTo`, `spring`, `calculateZoom/Pan` | 🔵 / ★★★ |
| D2 | 컨트롤 표시 | 상·하단 바 `slideIn/Out + fade`, 중앙 재생 버튼은 **fade + scale 만**(세로 expand 제거) | `AnimatedVisibility` 커스텀 enter/exit | ⚪ / ★★ |
| D3 | 페이지 스와이프 | `HorizontalPager` 기본은 좋음. 확대 상태에서 가장자리에 닿아 더 팬할 수 없을 때만 페이저로 넘겨주는 "엣지 핸드오프"(선택) | `pointerInput` + `PagerState` | 🔴 / ★ |
| D4 | 탐색 바 | 드래그를 놓은 뒤 위치 텍스트·슬라이더가 재생 위치로 "튀어" 돌아가는 현상 → `animateFloatAsState` 로 부드럽게. 재생 중 진행도 polling 400ms → `withFrameNanos` 로 매 프레임 보간 | `animateFloatAsState` | ⚪ / ★ |
| D5 | 정보 패널 | `AnimatedVisibility(expandVertically)` + 내용 `animateContentSize` | `animateContentSize` | ⚪ / ★ |
| D6 | 이미지 로드 | Coil `crossfade(true)` 는 이미 있음. 상세보기에서는 **썸네일 → 원본** 순서로 두 단계 로드(`placeholderMemoryCacheKey` 에 그리드 썸네일 키) 해서 회색 화면 없이 전환 | Coil `placeholderMemoryCacheKey` | 🔵 / ★★ |

### 3.5 리스트·기타 화면

| # | 항목 | 개선 | API | 난이도 / 효과 |
|---|---|---|---|---|
| R1 | Drive·업로드 목록·휴지통 | `LazyColumn` 항목에 `animateItem()`. 새 폴더 생성·완료 정리·재시도 시 항목이 미끄러진다 | `LazyItemScope.animateItem` | ⚪ / ★★ |
| R2 | 업로드 진행 바 | `LinearProgressIndicator(progress = { animatedFraction })` — `animateFloatAsState(target, tween(1000, LinearEasing))` 로 다음 DB 갱신까지 선형 보간. 시작 직후는 `indeterminate` | `animateFloatAsState` | ⚪ / ★★ |
| R3 | 설정 토글·행 | M3 `Switch` 는 G2 로 해결. 행 확장(계정 정보 로딩)에 `animateContentSize` | — | ⚪ / ★ |
| R4 | 다이얼로그 → 바텀시트 | 이름 변경·앨범 이동·기간 선택처럼 목록이 있는 입력은 `ModalBottomSheet` 가 M3 모션(스프링 슬라이드)을 공짜로 준다. 다이얼로그는 확인성 짧은 질문에만 | `ModalBottomSheet` | 🔵 / ★★ |
| R5 | 스켈레톤 | Drive 목록·EXIF 패널 로딩 중 원형 인디케이터 대신 shimmer 플레이스홀더 | 자체 `Brush` 애니메이션 | 🔵 / ★ |

## 4. 구조 개선

애니메이션을 "붙이기 쉬운" 코드 구조가 먼저다.

### 4.1 모션 토큰 모듈 — `core/ui/motion`

```kotlin
object Motion {
    object Duration { const val SHORT = 150; const val MEDIUM = 300; const val LONG = 500 }
    val standardEasing = FastOutSlowInEasing
    val emphasizedEasing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    fun <T> quick(): FiniteAnimationSpec<T> = tween(Duration.SHORT, easing = standardEasing)
    fun <T> gentleSpring(): SpringSpec<T> = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = Spring.DampingRatioLowBouncy)
}
val LocalReduceMotion = staticCompositionLocalOf { false }   // Settings.Global.ANIMATOR_DURATION_SCALE == 0f
```

- 화면 코드는 `Motion.quick()` 같은 토큰만 쓴다. 리터럴 `tween(300)` 은 detekt `MagicNumber` 로 이미 막히므로 자연스럽게 강제된다.
- `LocalReduceMotion` 이 true 면 `AnimatedVisibility` 의 spec 을 `snap()` 으로 바꾸는 헬퍼(`Motion.enter()`/`Motion.exit()`)를 통해 접근성 설정을 존중한다.

### 4.2 전환 정의를 내비게이션 키 옆으로

- `NavKeys.kt` 의 각 키에 대응하는 전환을 `AppNavigation` 한 곳에서 `entry<MediaViewerKey>(metadata = NavDisplay.transitionSpec { ... })` 로 선언한다. 화면 컴포저블은 자기 전환을 모른다.
- 전역 기본값(fade-through)과 예외(상세보기 확대, 다이얼로그성 화면 슬라이드 업) 두 종류만 둔다. 화면마다 다른 전환은 만들지 않는다.

### 4.3 큰 화면 파일 분할

| 파일 | 현재 | 분할 |
|---|---|---|
| `GalleryScreen.kt` | 651줄 | `GalleryRoute.kt`(권한·이벤트 배선), `GalleryTopBars.kt`(일반/선택 상단바 + `AnimatedContent`), `GalleryBanners.kt`(업로드·기간·부분접근 배너), `GalleryScreen.kt`(Scaffold 조립) |
| `MediaViewerScreen.kt` | 789줄 | `MediaViewerRoute.kt`, `ImagePage.kt`(+`ZoomState`), `VideoPage.kt`(+`VideoControls`), `ViewerChrome.kt`(상·하단 바·정보 패널) |

전환 로직이 들어가면 각 파일이 더 커지므로 분할을 먼저 한다. 동작 변화 없는 순수 이동이라 리스크가 낮다.

### 4.4 파생 상태 분리 — 선택이 바뀔 때 목록을 다시 계산하지 않기

현재 `GalleryViewModel.contentFlow` 는 `combine(media, selectedIds, uploadSummary, isMutating)` 안에서

```kotlin
sections = groupByDate(items)          // O(n)
albums   = albumsFrom(items)           // O(n) + 정렬
selectedAllFavorite = items.filter { it.id in selected }.all { ... }   // O(n)
```

를 **선택 하나 토글할 때마다** 다시 계산한다. 6,119장 기준 매번 수천 번 반복 + 새 `List` 할당 → 선택 애니메이션이 들어가면 첫 프레임에서 GC·계산이 겹쳐 끊긴다.

개선:

```kotlin
// 목록에서만 파생되는 값은 media 흐름에서 한 번 계산
private val catalog = combine(mediaRepository.observeMedia(filter), dateRange) { all, range ->
    val items = all.filterByDate(range)
    Catalog(items, groupByDate(items), albumsFrom(items), items.associateBy { it.id })
}.stateIn(viewModelScope, WhileSubscribed(5s), null)

// 선택은 O(k) 로만 결합
combine(catalog, selectedIds, uploadSummary, isMutating) { c, selected, ... ->
    selectedAllFavorite = selected.all { c.byId[it]?.isFavorite == true }
}
```

- `sections` 인스턴스가 선택 변경에도 동일하게 유지되므로 `LazyVerticalGrid` 의 `items(section.items, key)` 가 재실행돼도 Compose 가 안정성(stability)을 이용해 대부분의 썸네일 재구성을 건너뛴다.
- `Content` 상태 클래스에 `@Immutable` 을 붙이고 컬렉션은 `kotlinx.collections.immutable` (`ImmutableList`) 로 바꾸면 Compose 컴파일러가 `List` 를 불안정으로 보는 문제가 사라져 스킵률이 더 오른다. (Strong skipping 이 기본이라 지금도 어느 정도 스킵되지만, `List` 파라미터는 동일성 비교로만 스킵된다.)

### 4.5 애니메이션 가능한 상태 모델

- `GalleryUiState.Content.isSelectionMode` 처럼 **불리언 파생값**을 UI 가 `AnimatedContent(targetState)` 의 키로 직접 쓸 수 있게 유지한다.
- 삭제 직후 목록에서 항목이 빠지는 것은 MediaStore `ContentObserver` → 재조회(300ms 디바운스) 로 반영된다. `animateItem` 이 자연스럽게 동작하려면 **낙관적 제거**(동의 OK 직후 UI 목록에서 먼저 빼고, 재조회로 확정)를 고려한다. 실패하면 되돌린다.

## 5. 복잡성·성능

| 항목 | 내용 |
|---|---|
| 측정 기준 확립 | 디버그 빌드 수치(janky 16.7%)는 의미가 약하다. **릴리스(R8) 빌드**로 `dumpsys gfxinfo` 를 다시 재고, Macrobenchmark 모듈(`:benchmark`, `FrameTimingMetric`) 로 "그리드 플링", "선택 모드 진입", "상세보기 열기" 세 시나리오를 CI 에서 추적한다 |
| Baseline Profile | `:baselineprofile` 모듈로 콜드 스타트·첫 플링 경로를 AOT 컴파일. 첫 스크롤의 프레임 드롭이 크게 준다 |
| 재구성 관찰 | `-Pandroid.experimental.enableComposeCompilerMetrics=true` 리포트로 `GalleryGrid`·`MediaThumbnail` 의 skippable 여부 확인. Layout Inspector 재구성 카운트로 선택 토글 시 재구성 범위 확인 |
| 썸네일 크기 힌트 | `AsyncImage` 에 `Modifier.size` 가 없어 Coil 이 측정 후 요청한다. 셀 크기가 고정(≈100dp)이므로 `ImageRequest.size(px)` 를 명시하면 첫 프레임에서 디코딩 크기가 확정돼 스크롤이 매끄럽다 |
| `MediaStoreThumbnailFetcher` | `loadThumbnail` 은 시스템 캐시가 없을 때 동기 생성이라 느릴 수 있다. Coil `Dispatchers.IO` 병렬도(기본 64)를 `decoderDispatcher` 로 4~8 로 제한하면 스크롤 중 CPU 경쟁이 줄어 애니메이션 프레임이 살아난다 — 측정 후 결정 |
| 오버드로 | 썸네일 `background(surfaceVariant)` + 이미지 + 배지 3겹. 이미지가 로드되면 배경을 그리지 않아도 된다(`onState` 로 로드 완료 시 배경 제거) |
| 애니메이션 병목 | `graphicsLayer` 로 확대/이동하는 현재 방식은 올바르다(레이아웃 재측정 없음). 전환에서도 `Modifier.graphicsLayer { alpha/scale }` 계열만 쓰고 `size`/`padding` 애니메이션은 피한다(L3 의 인셋은 셀 하나라 예외적으로 허용) |
| 복잡성 한도 | 공유 요소 전환(V1)·엣지 핸드오프(D3)·낙관적 제거는 각각 새 상태 머신이 생긴다. 한 번에 하나씩, 각자 단위 테스트 가능한 순수 계산(예: 경계 보정 함수)을 분리해 넣는다 |

## 6. 우선순위 로드맵

효과 대비 비용으로 3단계. 각 단계는 독립적으로 커밋·검증 가능.

### 1단계 — 기반 + 값싼 승리 (하루 안)
G1 모션 토큰 → G4 predictive back → G3 전역 전환 → L1·L2 선택 모드 전환 → L3 썸네일 선택 → L4 `animateItem` → L5 배너 → D2 컨트롤 → R2 진행 바 → §4.4 파생 상태 분리(성능 기반).
*이 단계만으로 "즉시 교체" 느낌은 대부분 사라진다.*

### 2단계 — 상세보기 다듬기 (2~3일)
§4.3 파일 분할 → D1 확대 스프링·경계 보정 → V2 상세보기 진입 전환 → V3 아래로 스와이프 닫기 → D6 썸네일→원본 2단계 로드 → R1 리스트 `animateItem` → R4 바텀시트 전환 → L7 프레임 동기 자동 스크롤.

### 3단계 — 시그니처 모션 (측정 후 결정)
V1 공유 요소 전환(그리드↔상세) → G2 Expressive 모션 스킴 → D3 엣지 핸드오프 → R5 스켈레톤 → Macrobenchmark·Baseline Profile 모듈.
*V1 은 실험 API 이고 Nav3 와의 결합 코드가 복잡하므로, 1·2단계 후 릴리스 빌드 프레임 수치가 안정된 뒤에 착수한다.*

## 7. 검증 방법

- **수치**: 릴리스 빌드 설치 후 `adb shell dumpsys gfxinfo <pkg> reset` → 시나리오 수행 → `dumpsys gfxinfo <pkg>` 의 Janky frames / 90·95·99th. 목표: 플링 janky < 5%, 95th < 16ms(60Hz) 또는 < 8ms(120Hz)
- **회귀 방지**: Macrobenchmark `FrameTimingMetric` 을 CI(주 1회 물리 기기 or FTL) 에서 실행
- **육안**: 기기 개발자 옵션 "애니메이션 배율 5x" 로 전환을 느리게 보며 겹침·점프 확인. "애니메이션 끄기" 상태에서 `LocalReduceMotion` 경로가 즉시 전환되는지 확인
- **재구성**: Layout Inspector 재구성 카운트 — 선택 토글 1회에 썸네일 재구성이 화면에 보이는 개수(≈30) 를 넘지 않아야 함

## 8. 리스크·주의

- `SharedTransitionLayout`, `MaterialExpressiveTheme` 는 실험 API — `@OptIn` 범위를 파일 단위로 좁히고 Compose 업그레이드 시 회귀 테스트
- Predictive back 은 삼성 One UI 에서 시스템 제스처 설정에 따라 미리보기 강도가 다르다. 켰을 때 `BackHandler`(선택 해제·확대 해제) 우선순위가 바뀌지 않는지 확인
- `animateItem` 은 key 안정성에 의존한다. 그리드 key 는 `item.id`, 헤더 key 는 `"header-$date"` 로 이미 안정적. 필터 변경으로 목록이 통째로 바뀌는 경우는 `animateItem` 대신 화면 페이드가 맞다(수천 개가 동시에 미끄러지면 산만)
- 낙관적 제거는 MediaStore 재조회와 경합한다. 재조회 결과를 항상 진실로 삼고, 낙관적 상태는 다음 재조회 도착 시 폐기
- 애니메이션을 넣을수록 `MagicNumber` detekt 위반이 늘어난다 → G1 토큰을 먼저 만들어야 코드가 깨끗하게 유지된다
