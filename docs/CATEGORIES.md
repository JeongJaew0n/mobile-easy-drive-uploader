# 카테고리 기능 설계

작성 2026-09-09. 사진·영상에 사용자가 만든 카테고리를 **여러 개** 붙이고(N:M), 카테고리로 갤러리를 거르고, 카테고리 자체를 만들고·고치고·지운다. 구현 전 설계 문서. 구현은 §9 순서대로, 각 단계 끝에 `manual-tests/10-categories.md` 의 항목을 갱신한다.

## 1. 목표와 범위

- 한 항목에 카테고리 0개 이상. 카테고리는 이름 + 색(팔레트 8색 중 하나).
- **CRUD 전부**: 카테고리(생성·이름 변경·색 변경·삭제·순서), 할당(여러 항목에 한 번에 붙이기/떼기), 조회(카테고리로 필터, "미분류" 필터).
- 기존 필터(즐겨찾기·기간·백업 안 됨)와 **AND** 로 조합된다. 카테고리를 여러 개 고르면 그 안에서는 **OR**(어느 하나라도 붙은 항목).
- 데이터는 기기 로컬(Room). MediaStore 에는 카테고리를 쓸 곳이 없다(사용자 태그 컬럼 없음) → 앱 DB 가 유일한 저장소.

하지 않는 것(이번 범위 밖, §10 에 후보로만):
- 자동 분류(ML), 카테고리별 Drive 폴더 자동 업로드, 카테고리 Drive 백업/복원, 카테고리 공유.

## 2. 도메인 모델

```kotlin
// core/domain/model/Category.kt
data class Category(
    val id: Long,
    val name: String,          // trim, 1~30자, 대소문자 무시 유일
    val colorIndex: Int,       // 0..7 — 팔레트 인덱스. 테마(다크/라이트)별 실제 색은 UI 가 결정
    val sortOrder: Int,        // 관리 화면 순서(수동). 새 카테고리는 마지막
    val itemCount: Int = 0,    // 파생값(조회 시 JOIN). 엔티티에는 없음
)

/** mediaId → 붙은 카테고리 ID. 6천 항목 기준 메모리 수십 KB */
typealias CategoryAssignments = Map<Long, Set<Long>>
```

**항목 식별자는 MediaStore `_ID`** 를 쓴다. 근거: 업로드 원장(`uploaded_media`)·해시 캐시(`media_hash`)와 같은 기준이고, 갤러리 `Catalog` 가 이미 `byId` 를 들고 있다.
알려진 위험: MediaStore 가 재색인(초기화·복원·"앨범 이동"이 아닌 파일 시스템 수준 이동)되면 `_ID` 가 바뀌어 할당이 끊긴다. 완화책은 §6.

## 3. 저장 (Room v4 → v5)

```kotlin
@Entity(tableName = "category", indices = [Index(value = ["nameLower"], unique = true)])
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val nameLower: String,     // 유일성 검사용(대소문자 무시). Room 은 COLLATE NOCASE 유니크 인덱스를 어노테이션으로 못 걸어 컬럼으로 둔다
    val colorIndex: Int,
    val sortOrder: Int,
    val createdAt: Long,
)

@Entity(
    tableName = "media_category",
    primaryKeys = ["mediaId", "categoryId"],
    foreignKeys = [ForeignKey(entity = CategoryEntity::class, parentColumns = ["id"], childColumns = ["categoryId"], onDelete = CASCADE)],
    indices = [Index("categoryId")],   // 카테고리별 개수·필터. mediaId 는 PK 선두라 별도 인덱스 불필요
)
data class MediaCategoryEntity(
    val mediaId: Long,
    val categoryId: Long,
    val assignedAt: Long,
)
```

- `AppDatabase.version = 5`, `AutoMigration(from = 4, to = 5)` (표 추가만). `app/schemas/…/5.json` 커밋.
- 외래키 CASCADE 로 카테고리 삭제 시 할당 행이 함께 사라진다 → 삭제 UX 에 개수 경고(§5.3).
- 파일 위치: 기존 DB 패키지 `core/data/upload/db` 에 두면 이름이 어색하다. **`core/data/db`** 로 패키지를 옮기는 리팩터는 이 작업과 분리한다(순수 이동 커밋). 이번엔 기존 패키지에 추가하고 TODO 주석.

### DAO

```kotlin
@Dao
interface CategoryDao {
    @Query("""SELECT c.*, COUNT(mc.mediaId) AS itemCount FROM category c
              LEFT JOIN media_category mc ON mc.categoryId = c.id
              GROUP BY c.id ORDER BY c.sortOrder, c.id""")
    fun observeWithCounts(): Flow<List<CategoryWithCount>>

    @Query("SELECT mediaId, categoryId FROM media_category")
    fun observeAssignments(): Flow<List<MediaCategoryRef>>          // → Map<Long, Set<Long>> 은 Repository 에서

    @Insert suspend fun insert(entity: CategoryEntity): Long
    @Query("UPDATE category SET name = :name, nameLower = :nameLower WHERE id = :id") suspend fun rename(id: Long, name: String, nameLower: String)
    @Query("UPDATE category SET colorIndex = :colorIndex WHERE id = :id") suspend fun recolor(id: Long, colorIndex: Int)
    @Query("UPDATE category SET sortOrder = :order WHERE id = :id") suspend fun reorder(id: Long, order: Int)
    @Query("DELETE FROM category WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM category") suspend fun nextSortOrder(): Int

    @Insert(onConflict = IGNORE) suspend fun assign(rows: List<MediaCategoryEntity>)
    @Query("DELETE FROM media_category WHERE mediaId IN (:mediaIds) AND categoryId = :categoryId") suspend fun unassign(mediaIds: List<Long>, categoryId: Long)
    @Query("DELETE FROM media_category WHERE mediaId IN (:mediaIds)") suspend fun deleteForMedia(mediaIds: List<Long>)
    @Query("SELECT DISTINCT mediaId FROM media_category") suspend fun assignedMediaIds(): List<Long>
}
```

`IN (:list)` 는 SQLite 변수 한도(999) 때문에 `UploadLedgerRepository.QUERY_CHUNK = 900` 과 같은 방식으로 900개씩 끊어 부른다.

### Repository (`core/data/category/CategoryRepository.kt`)

```kotlin
interface CategoryRepository {
    fun observeCategories(): Flow<List<Category>>                 // sortOrder 순, itemCount 포함
    fun observeAssignments(): Flow<CategoryAssignments>           // distinctUntilChanged
    suspend fun create(name: String, colorIndex: Int): Result<Category>   // 실패: 이름 중복·빈 이름
    suspend fun rename(id: Long, name: String): Result<Unit>
    suspend fun recolor(id: Long, colorIndex: Int)
    suspend fun reorder(orderedIds: List<Long>)                   // 관리 화면 드래그 결과를 한 번에
    suspend fun delete(id: Long)
    suspend fun assign(mediaIds: Collection<Long>, add: Set<Long>, remove: Set<Long>)   // 한 트랜잭션
    suspend fun removeMedia(mediaIds: Collection<Long>)           // 영구 삭제·고아 정리
}
```

`observeAssignments` 는 `shareIn(replay = 1)` 로 앱 범위 공유(갤러리·상세보기·관리 화면이 같은 맵을 본다 — `observeMedia` 와 같은 이유, `VIEWER_STABILITY.md` §6).

## 4. 갤러리 연결 (조회 = R)

### 4.1 필터 상태

```kotlin
/** 카테고리 필터. null 이면 끔 */
sealed interface CategoryFilter {
    data class Any(val ids: Set<Long>) : CategoryFilter      // 고른 카테고리 중 하나라도 (OR)
    data object Uncategorized : CategoryFilter               // 카테고리 없는 항목만
}
```

`GalleryViewModel` 에 `categoryFilter: MutableStateFlow<CategoryFilter?>` 추가. `Catalog` 를 만드는 `combine` 에 `categoryRepository.observeAssignments()` 와 `categoryFilter` 를 더하고, 순서는 **기간 → 백업 → 카테고리** 로 메모리에서 거른다(`items.filter { ids -> assignments[it.id] … }`, 6천 개 O(n)).
필터가 바뀌면 기존과 같이 `filterVersion++`(첫 목록 애니메이션 생략), `clearSelection()`.
`Content` 에 추가: `categories: List<Category>`, `assignments: CategoryAssignments`, `categoryFilter: CategoryFilter?`. `dayCounts` 는 카테고리 필터 **적용 후** 목록으로 센다(기간 선택 달력이 "이 카테고리 사진이 있는 날"을 보이도록 — `DATE_RANGE_PICKER.md` §3 의 원칙과 동일).

### 4.2 UI

- **⋮ 메뉴** 에 "카테고리" 항목 → `CategoryFilterSheet`(바텀시트): 칩 목록(색 점 + 이름 + 개수), 다중 선택, 맨 앞에 "미분류" 칩(단독 선택), 하단 "적용"·"해제", 우상단 "관리" 링크 → 관리 화면.
- 필터가 켜져 있으면 기간 바와 같은 자리에 **카테고리 바**(`enterExpand/exitShrink`, `ANIMATION_IMPROVEMENT.md` 배너 규칙): 선택된 카테고리 색 점 + 이름들(넘치면 "+n"), X 로 해제. 상단 제목은 카테고리 하나면 그 이름, 여럿이면 "카테고리 n개", 미분류면 "미분류".
- **썸네일 배지**: 항목에 카테고리가 있으면 **오른쪽 위**에 색 점 최대 3개(4개 이상이면 3개 + 작은 "+"). 왼쪽 아래 클라우드 배지·오른쪽 아래 영상 길이와 겹치지 않는 자리. 점은 `Box(background, CircleShape)` 만 — 애니메이션 없음, 선택 모드 체크 표시와 같은 모서리를 피해 왼쪽 위(체크)와 구분. 설정에서 끌 수 있게 `showCategoryBadges`(기본 켬) — 배지가 많은 사람에게 시끄러울 수 있다.
- **상세보기 정보 패널**: "카테고리" 행에 칩 나열. ⋮ 메뉴에 "카테고리 편집" → §5.2 의 피커(단일 항목).

## 5. 할당·관리 (C/U/D)

### 5.1 선택 모드에서 붙이기/떼기

선택 모드 하단 바(`GalleryActions`)에 **"카테고리" 아이콘**(`ic_label`) 추가 → `CategoryPickerSheet`:

```
┌────────────────────────────────┐
│ 카테고리 지정            13개 항목 │
│ [+ 새 카테고리]                  │
│ ● 여행            13/13   ☑     │  ← 전부 붙어 있음
│ ● 가족             5/13   ◪     │  ← 일부(tri-state)
│ ● 영수증           0/13   ☐     │
│ …                                │
│                  [취소]   [적용]  │
└────────────────────────────────┘
```

- 각 행의 초기 상태는 선택 항목들의 현재 할당으로 계산: 전부 → 체크, 일부 → 부분, 없음 → 빈 칸. 탭 순서: 부분 → 체크 → 빈 칸 → 체크(부분으로는 되돌아가지 않음; "그대로 두기"는 취소).
- 적용 시 `assign(mediaIds, add = 체크로 바뀐 것, remove = 빈 칸으로 바뀐 것)` 한 번. 부분 상태 그대로인 행은 건드리지 않는다.
- "+ 새 카테고리" 는 시트 안 인라인 입력(이름 + 색 선택) → 만들어지면 바로 체크 상태로 목록에 들어간다.
- 순수 로직 `CategoryPickerState`(초기 tri-state 계산, 탭 전이, 적용 diff)는 단위 테스트 대상.
- 적용 후 스낵바: "13개 항목에 카테고리를 지정했습니다" (복수형 `plurals`). 선택 모드는 유지(다른 작업 이어서).

### 5.2 상세보기에서 한 항목 편집

같은 `CategoryPickerSheet` 를 `mediaIds = listOf(current.id)` 로 띄운다. tri-state 는 나오지 않는다(항목 하나).

### 5.3 관리 화면 (`CategoriesKey`, feature/categories)

```
카테고리                           [+]
─────────────────────────────────────
≡ ● 여행                      312  ⋮   ← ⋮: 이름 변경 / 색 변경 / 삭제
≡ ● 가족                       88  ⋮
≡ ● 영수증                      7  ⋮
(비어 있으면) "카테고리가 없습니다. + 로 만들어 보세요"
```

- **생성 [+]**: 다이얼로그 — 이름(TextField, 30자, 앞뒤 공백 제거) + 색 팔레트(8개 원, 기본은 사용 적은 색). 중복 이름이면 필드 아래 오류 "같은 이름이 있습니다".
- **이름 변경 / 색 변경**: 같은 다이얼로그를 값 채워서.
- **삭제**: 확인 다이얼로그 "'여행'을 삭제할까요? 312개 항목에서 이 카테고리가 제거됩니다(사진은 지워지지 않습니다)". CASCADE.
- **순서**: 행 왼쪽 ≡ 핸들로 드래그 정렬(`LazyColumn` + `animateItem`, 이동 스펙 `motion.settle()`; 드래그 중엔 `graphicsLayer` 이동만). 놓으면 `reorder(orderedIds)`. 순서는 필터 시트·피커·정보 패널 칩 순서에 모두 쓰인다.
- 행 탭 → 그 카테고리 하나로 필터를 켠 채 갤러리로 복귀(`backStack` 을 GalleryKey 까지 pop 하고 필터 설정 — `GalleryViewModel.setCategoryFilter`).
- 진입 경로: 설정 화면 "카테고리 관리", 필터 시트의 "관리".

### 5.4 ViewModel / UseCase

- `CategoriesViewModel`(@HiltViewModel): `uiState: StateFlow<CategoriesUiState>`(sealed: Loading / Content(list) / Error), 액션은 `CategoryRepository` 직접 호출(단순 CRUD 라 UseCase 층은 두지 않음. 이름 검증은 Repository 의 `create/rename` 이 `Result` 로 돌려준다).
- 갤러리·상세보기의 피커 적용은 `AssignCategoriesUseCase(mediaIds, add, remove)` 하나로 — 두 화면이 같은 진입점을 쓰고, 나중에 "할당 시 Drive appProperties 갱신" 같은 부수 효과를 붙일 자리.

## 6. 항목 삭제·이동·재색인과의 정합

| 상황 | 할당 처리 |
|---|---|
| 휴지통으로 이동 | **유지**. 복원하면 카테고리가 그대로 붙어 있어야 한다(휴지통 화면에서는 배지 안 보임) |
| 영구 삭제(앱에서) | `MediaActionRunner` 의 Delete 성공 콜백에서 `removeMedia(ids)` |
| 다른 앱이 지움 / 재색인으로 `_ID` 소멸 | **고아 정리**: `Catalog` 를 만들 때 `assignments.keys - byId.keys` 가 있으면 `removeMedia` 를 백그라운드로 호출. 단, 휴지통 항목은 `observeMedia(All)` 에 없으므로 `observeMedia(Trashed)` 의 ID 도 살아 있는 것으로 본다(휴지통 항목의 카테고리를 지우면 복원 시 사라진다). 정리는 목록이 **비어 있지 않을 때만**(권한 회수·일시 오류로 빈 목록이 오면 전부 지워 버리는 사고 방지) |
| 이름 변경·앨범 이동(앱의 Rename/Move) | `_ID` 가 유지되므로 영향 없음(MediaStore `update`) |
| 앨범 이동을 다른 앱이 파일 복사+삭제로 하면 | 새 `_ID` → 할당 유실. 완화: §10 의 "Drive/로컬 JSON 백업" 후보. 이번 범위에서는 문서로만 |

## 7. 성능·전력

- 할당 맵은 Room Flow → `Map<Long, Set<Long>>` 변환을 IO 에서, `distinctUntilChanged`. 갤러리 `combine` 에 들어가지만 **선택 토글과는 무관**(Catalog 분리 원칙 유지).
- 썸네일에는 `List<Int>`(색 인덱스, 최대 3) 만 넘긴다. `MediaThumbnail` 재구성 범위는 그 항목뿐.
- 카테고리 필터는 메모리 필터. 6천 개 × Set 조회 = 수 ms.
- 배지 그리기: 항목당 최대 3개 `Box` — 레이아웃 비용 미미. 무한·매 프레임 애니메이션 없음(규칙).

## 8. 문자열·아이콘·내비게이션

- `AppNavKey` 에 `CategoriesKey`(@Serializable data object). `MediaViewerKey` 는 그대로(필터는 갤러리와 같은 기준이어야 하므로 `categoryFilter` 를 키에 실어 상세보기 스와이프 범위도 맞춘다 — `favoritesOnly`/`startEpochDay` 와 같은 방식. `Set<Long>` 은 `List<Long>` 으로 직렬화).
- 아이콘: `ic_label.xml`(카테고리), `ic_label_off.xml`(미분류), `ic_drag_handle.xml`(정렬), `ic_palette.xml`(색). `res/drawable` 에 직접 추가(규칙).
- 문자열(모두 `strings.xml`): `category_title`, `category_new`, `category_name_hint`, `category_name_duplicate`, `category_name_empty`, `category_delete_confirm(name, count)`, `category_filter_title`, `category_filter_uncategorized`, `category_filter_summary(count)`, `category_assign_title(count)`, `category_assigned(plurals)`, `category_manage`, `category_empty`, `category_badges_setting`, `category_color_n`(접근성용 색 이름 8개).
- 색 팔레트(`core/ui/theme/CategoryColors.kt`): 8색을 다크/라이트에서 각각 정의(배지·칩 배경). `MaterialTheme` 동적 색과 독립 — 사용자가 고른 색이 테마마다 바뀌면 안 된다.

## 9. 구현 순서와 검증

| 단계 | 내용 | 검증 |
|---|---|---|
| 1 | 엔티티·DAO·Repository·v5 마이그레이션, `AssignCategoriesUseCase`, `CategoryPickerState` | Robolectric Room 인메모리 DAO 테스트(CASCADE·유니크·개수 JOIN), 피커 tri-state 단위 테스트, `5.json` 커밋, v4 설치 위에 업데이트(CAT-01) |
| 2 | 선택 모드 "카테고리" + 피커 시트, 갤러리 필터 시트·바, Catalog 결합, 고아 정리 | `GalleryViewModelTest`(필터 AND/OR·미분류·필터 전환 시 애니메이션 생략), 실기기 CAT-02~08 |
| 3 | 관리 화면(생성·이름/색 변경·삭제·드래그 정렬), 설정 진입, 상세보기 칩·편집, 썸네일 배지·설정 토글 | `CategoriesViewModelTest`, 실기기 CAT-09~15 |

로컬 검증은 매 단계 `detekt` + `testDebugUnitTest`(+ 마이그레이션이 있는 1단계는 `assembleRelease` 로 `$$serializer`/Room 스키마 R8 확인). 실기기는 사용자 지시가 있을 때만.

### `manual-tests/10-categories.md` (구현 시 생성)

| ID | 시나리오 | 기대 |
|---|---|---|
| CAT-01 | v4 설치 위에 업데이트 | 크래시 없음, 기존 큐·원장 유지, `category`/`media_category` 표 생성 |
| CAT-02 | 13개 선택 → 카테고리 → 새 카테고리 "여행" 만들고 적용 | 스낵바 "13개 항목에…", 썸네일 오른쪽 위 색 점 |
| CAT-03 | 일부만 붙은 상태에서 피커 열기 | 부분(◪) 표시, 탭하면 체크 → 빈 칸 → 체크 순환 |
| CAT-04 | ⋮ → 카테고리 → "여행" 적용 | 목록이 그 항목만, 상단 제목 "여행", 카테고리 바 표시, 상세보기 스와이프 범위 일치 |
| CAT-05 | 카테고리 둘 선택 | OR — 둘 중 하나라도 붙은 항목 |
| CAT-06 | 카테고리 + 기간 + 즐겨찾기 동시 | AND 조합, 기간 달력엔 카테고리 사진 있는 날만 점 |
| CAT-07 | "미분류" | 카테고리 없는 항목만 |
| CAT-08 | 카테고리 붙은 항목을 휴지통 → 복원 | 카테고리 유지 |
| CAT-09 | 관리 화면에서 이름 변경·색 변경 | 필터 바·배지·칩에 즉시 반영 |
| CAT-10 | 같은 이름(대소문자만 다름)으로 생성 | "같은 이름이 있습니다" |
| CAT-11 | 카테고리 삭제(확인 문구에 개수) | 항목에서 제거, 사진은 남음, 켜져 있던 필터는 해제 |
| CAT-12 | 드래그 정렬 | 순서가 필터 시트·피커·칩에 반영, 재시작 후 유지 |
| CAT-13 | 상세보기 ⋮ → 카테고리 편집 | 한 항목만 대상, 정보 패널 칩 갱신 |
| CAT-14 | 다른 앱(삼성 갤러리)으로 사진 영구 삭제 후 앱 복귀 | 고아 할당 정리(관리 화면 개수 감소), 크래시 없음 |
| CAT-15 | 설정에서 배지 끄기 | 썸네일 점 사라짐, 필터·칩은 그대로 |

## 10. 이후 후보 (이번 범위 밖)

- **할당 백업**: `category.json`(이름·색·순서 + 항목 지문 `relativePath/displayName/size/dateTaken`) 을 Drive 의 앱 폴더에 저장하고, 재설치·재색인 후 지문으로 매칭해 복원. `_ID` 유실 문제의 근본 해결.
- **업로드 연동**: 업로드 시 Drive 파일 `appProperties.categories` 에 이름 기록, 또는 카테고리별 Drive 하위 폴더.
- **자동 백업 조건**: "이 카테고리만 자동 백업".
- **스마트 제안**: 같은 앨범(bucket)·같은 날 항목에 일괄 제안. ML 분류는 하지 않는다(중복 탐지에서 유사 사진을 배제한 것과 같은 이유 — 오분류 위험).

## 11. 진행 기록

- 2026-09-09 1단계 완료(`f1e7b4c`): 엔티티·DAO·저장소·v5 마이그레이션·`AssignCategoriesUseCase`·`CategoryPickerState` + 테스트 10건.
- 2026-09-09 2단계 완료: 선택 모드 피커, 필터 시트·바·제목, `Catalog` 결합, `MediaViewerKey` 전달, 관리 화면(생성·이름/색 변경·삭제·위/아래 이동 — 드래그 정렬은 보류), 설정 진입점, `OrphanAssignmentCleaner`(권한 전체 접근일 때만 구독; 초기 구현이 권한 전에 MediaStore 를 구독해 "권한 확인 전 조회 금지" 테스트가 잡아냈다). 남은 3단계: 상세보기 칩·편집, 썸네일 배지 + 설정 토글.
- 2026-09-09 3단계 완료: 썸네일 오른쪽 위 색 점 배지(최대 3, 즐겨찾기 별과 한 줄) + 설정 "썸네일에 카테고리 색 점 표시" 토글(`showCategoryBadges`, 기본 켬), 상세보기 정보 패널 "카테고리" 칩 행 + ⋮ "카테고리 편집"(같은 피커, 항목 하나). 보류: 관리 화면 드래그 정렬(위/아래 이동으로 대체), 필터 중인 카테고리 삭제 시 자동 해제(CAT-11 확인 후 결정).
