package com.jjw.easygallery.core.common.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 세 언어의 문자열이 서로 맞는지(`docs/plans/i18n/spec.md`). 번역이 빠진 것은 Lint(MissingTranslation)가 잡지만,
 * **형식 인자가 어긋난 것**(`%1$s` 를 `%1$d` 로, 인자 하나를 빠뜨림)은 빌드가 통과하고 그 문장을 띄우는 순간 앱이 죽는다.
 */
class StringResourcesParityTest {

    private val base = load("values")

    @Test
    fun `every language has exactly the same keys`() {
        TRANSLATIONS.forEach { dir ->
            val translated = load(dir)
            assertEquals("$dir 에 빠진 키", emptySet<String>(), base.keys - translated.keys)
            assertEquals("$dir 에만 있는 키", emptySet<String>(), translated.keys - base.keys)
        }
    }

    @Test
    fun `format arguments match the Korean text`() {
        TRANSLATIONS.forEach { dir ->
            val translated = load(dir)
            base.forEach { (key, texts) ->
                val expected = placeholders(texts.getValue(OTHER))
                translated[key]?.forEach { (quantity, text) ->
                    assertEquals("$dir/$key[$quantity]", expected, placeholders(text))
                }
            }
        }
    }

    @Test
    fun `plurals keep the other form`() {
        TRANSLATIONS.forEach { dir ->
            load(dir).filterValues { it.size > 1 || !it.containsKey(OTHER) }.forEach { (key, texts) ->
                assertTrue("$dir/$key 에 other 가 없다", texts.containsKey(OTHER))
            }
        }
    }

    /** 키 → (복수형 수량 → 문장). 일반 문자열은 [OTHER] 하나로 둔다 */
    private fun load(dir: String): Map<String, Map<String, String>> {
        val file = listOf(File("src/main/res/$dir/strings.xml"), File("app/src/main/res/$dir/strings.xml"))
            .first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = linkedMapOf<String, Map<String, String>>()
        val root = doc.documentElement
        val children = root.childNodes
        val elements = (0 until children.length).mapNotNull { children.item(it) as? Element }
            .filter { it.getAttribute("translatable") != "false" }
        for (node in elements) {
            val name = node.getAttribute("name")
            when (node.tagName) {
                "string" -> result[name] = mapOf(OTHER to node.textContent)
                "plurals" -> {
                    val items = node.getElementsByTagName("item")
                    result[name] = (0 until items.length).associate { index ->
                        val item = items.item(index) as Element
                        item.getAttribute("quantity") to item.textContent
                    }
                }
            }
        }
        return result
    }

    /** `%1$s`·`%2$d`·`%d`·`%1$.5f` 를 모은다. `%%` 는 글자라 뺀다. 순서가 아니라 집합으로 본다 — 어순은 언어마다 다르다 */
    private fun placeholders(text: String): Set<String> =
        PLACEHOLDER.findAll(text.replace("%%", "")).map { it.value }.toSet()

    private companion object {
        val TRANSLATIONS = listOf("values-en", "values-ja")
        const val OTHER = "other"
        val PLACEHOLDER = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXc]""")
    }
}
