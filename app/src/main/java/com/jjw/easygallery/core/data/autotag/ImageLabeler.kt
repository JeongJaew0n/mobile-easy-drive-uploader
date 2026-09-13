package com.jjw.easygallery.core.data.autotag

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 라벨 하나 */
data class AutoLabel(val label: String, val confidence: Float)

/** 모델을 아직 못 받았을 때. 계속 돌려도 전부 실패하므로 훑기를 멈춘다 */
class LabelModelUnavailableException(cause: Throwable?) :
    IllegalStateException("이미지 인식 모델을 준비하는 중입니다", cause)

/** 실기기에서만 도는 ML Kit 을 가려, 저장소를 가짜 구현으로 테스트할 수 있게 한다 */
interface ImageLabeler : Closeable {
    /** 신뢰도 내림차순. 인식된 게 없으면 빈 목록 */
    suspend fun label(uri: Uri): List<AutoLabel>
}

/**
 * ML Kit 온디바이스 이미지 라벨링(`docs/AUTO_TAGGING.md` §5.1).
 * 원본이 아니라 [MAX_EDGE_PX] 로 줄인 비트맵을 넣는다 — 모델 입력이 224px 안팎이라 원본을 디코딩할 이유가 없다.
 */
@Singleton
class MlKitImageLabeler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : ImageLabeler {

    // 사진마다 만들면 초기화 비용이 지배적이라 한 번 만들어 재사용한다
    private val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }

    override suspend fun label(uri: Uri): List<AutoLabel> {
        val bitmap = decodeScaled(uri)
        return try {
            runLabeler(InputImage.fromBitmap(bitmap, 0))
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * ML Kit 네이티브 분류기는 **ARGB_8888 만** 받는다. 다른 포맷을 넘기면 자바 예외가 아니라
     * `JNI DETECTED ERROR ... Bitmap must have RGBA_8888 format` 으로 **프로세스가 죽는다**
     * (실기기에서 1250장쯤에서 발생). 그래서 디코딩 뒤 포맷을 반드시 확인하고, 아니면 변환한다.
     */
    private fun decodeScaled(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val decoded = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = maxOf(info.size.width, info.size.height)
            if (longest > MAX_EDGE_PX) decoder.setTargetSampleSize(sampleSizeFor(longest))
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE // 하드웨어 비트맵은 읽지 못한다
            // 저메모리 정책이면 RGB_565 가 나온다 — 기본 정책을 명시해 8888 을 유도한다
            decoder.memorySizePolicy = ImageDecoder.MEMORY_POLICY_DEFAULT
            decoder.isMutableRequired = false
        }
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        val converted = decoded.copy(Bitmap.Config.ARGB_8888, false)
        decoded.recycle()
        return requireNotNull(converted) { "비트맵을 ARGB_8888 로 바꾸지 못했습니다" }
    }

    private suspend fun runLabeler(image: InputImage): List<AutoLabel> =
        suspendCancellableCoroutine { continuation ->
            labeler.process(image)
                .addOnSuccessListener { labels ->
                    continuation.resume(
                        labels.map { AutoLabel(it.text, it.confidence) }.sortedByDescending { it.confidence },
                    )
                }
                .addOnFailureListener { error ->
                    continuation.resumeWithException(
                        if (error.isModelUnavailable()) LabelModelUnavailableException(error) else error,
                    )
                }
        }

    override fun close() {
        labeler.close()
    }

    private companion object {
        const val MAX_EDGE_PX = 512

        fun sampleSizeFor(longestEdge: Int): Int {
            var sample = 1
            while (longestEdge / (sample * 2) >= MAX_EDGE_PX) sample *= 2
            return sample
        }

        /**
         * Play 서비스가 모델을 아직 안 받았을 때 나는 오류. 예외 타입이 버전마다 달라
         * 메시지로도 함께 판단한다(모델 준비는 일시적 상태라 훑기를 멈추고 나중에 다시 하면 된다).
         */
        fun Throwable.isModelUnavailable(): Boolean {
            val text = (message ?: "") + (cause?.message ?: "")
            return text.contains("model", ignoreCase = true) &&
                (text.contains("download", ignoreCase = true) || text.contains("unavailable", ignoreCase = true))
        }
    }
}
