# ML Kit 두 번째 실행부터 `This detector is already closed!` 로 전부 실패한다

## 환경

- Android, Kotlin, Hilt(Dagger), WorkManager
- `com.google.android.gms:play-services-mlkit-image-labeling:16.0.8`
- ML Kit 의 `ImageLabeler`·`BarcodeScanner`·`FaceDetector` 등 `Closeable` 인 검출기 전부에 해당

## 증상

검출기를 한 번 만들어 재사용하고 작업이 끝나면 `close()` 하도록 짰다.
첫 실행은 멀쩡한데 **두 번째 실행부터** 입력마다 예외가 난다.

```
java.lang.IllegalStateException: This detector is already closed!
```

앱을 껐다 켜면 다시 정상이 된다. 그래서 재현이 들쭉날쭉하고, 로그를 봐도
"왜 첫 번째만 되는지" 가 안 보인다.

## 원인

두 가지가 겹쳐야 터진다.

1. 검출기 구현이 DI 컨테이너에서 **싱글턴 스코프**로 바인딩돼 있다.
2. 그 검출기를 받아 쓰는 쪽이 작업이 끝나면 `close()` 를 부른다.

`close()` 는 네이티브 자원을 **영구히** 닫는다. 되살릴 방법이 없다.
싱글턴이면 두 번째 호출자가 받는 것은 같은 인스턴스, 즉 이미 닫힌 인스턴스다.

앱을 껐다 켜면 낫는 이유도 여기 있다. 프로세스가 죽으면 싱글턴도 사라지기 때문이다.
그래서 **프로세스가 살아 있는 동안 같은 작업을 두 번 돌릴 때만** 드러난다.
백그라운드 작업은 끝나고 나면 프로세스가 곧잘 죽어서, 우연히 통과하는 경우가 많다.

lazy 초기화를 써도 소용없다. 인스턴스가 늦게 만들어질 뿐 수명은 그대로 싱글턴이다.

## 해결

**둘 중 하나로 통일한다. 섞지 않는다.**

| 선택 | 조건 |
|---|---|
| 스코프를 빼고 쓸 때마다 만든다 | 호출부가 `close()` 를 부른다 |
| 싱글턴으로 두고 아무도 닫지 않는다 | 프로세스가 끝날 때 OS 가 회수한다 |

작업 단위로 돌고 마는 백그라운드 훑기라면 앞쪽이 낫다. 검출기가 붙잡는 네이티브 메모리를
작업이 없는 동안 계속 들고 있을 이유가 없다.

```kotlin
// 스코프 애너테이션을 붙이지 않는다 — Provider.get() 이 매번 새 인스턴스를 준다
class MlKitImageLabeler @Inject constructor(...) : ImageLabeler { ... }

@Binds
abstract fun bindsImageLabeler(impl: MlKitImageLabeler): ImageLabeler
```

호출부는 `Provider<T>` 로 받아 쓸 때 꺼내고 `finally` 에서 닫는다.

## 재발 방지

스코프 애너테이션이 없다는 것을 테스트로 굳힌다. 단위 테스트에서 돌고,
누가 나중에 "성능" 을 이유로 `@Singleton` 을 붙이면 바로 깨진다.

```kotlin
@Test
fun `검출기에는 스코프가 없다`() {
    assertNull(MlKitImageLabeler::class.java.getAnnotation(Singleton::class.java))
}
```

기기 확인 항목도 함께 남긴다 — **앱을 떠나지 않고 같은 작업을 연속 두 번** 돌려 본다.
한 번만 돌려 보는 검증으로는 절대 안 잡힌다.
