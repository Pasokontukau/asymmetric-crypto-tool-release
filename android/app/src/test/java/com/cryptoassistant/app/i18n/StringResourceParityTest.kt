package com.cryptoassistant.app.i18n

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中英文资源门禁：键集合、占位符、复数数量必须一致；英文资源不能残留中文。
 *
 * <p>当前默认语言仍是中文（values），英语在 values-en。任何阶段新增/修改资源都要
 * 同时补齐两边，否则英语发布会回退成中文或格式错乱。</p>
 */
class StringResourceParityTest {

    private val placeholder = Regex("""%(\d+\$)?[sd]""")
    private val han = Regex("""\p{IsHan}""")

    private fun resDir(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        val candidates = listOf(
            File(cwd, "src/main/res"),
            File(cwd, "app/src/main/res"),
            File(cwd.parentFile, "app/src/main/res"),
        )
        return candidates.firstOrNull { it.isDirectory }
            ?: error("Cannot locate src/main/res from ${cwd.absolutePath}")
    }

    private fun readStrings(file: File): Map<String, String> {
        assertTrue("missing ${file.path}", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("string")
        val result = linkedMapOf<String, String>()
        for (index in 0 until nodes.length) {
            val node = nodes.item(index)
            val name = node.attributes.getNamedItem("name")?.nodeValue ?: continue
            result[name] = node.textContent
        }
        return result
    }

    private fun readPluralQuantities(file: File): Map<String, Set<String>> {
        assertTrue("missing ${file.path}", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("plurals")
        val result = linkedMapOf<String, Set<String>>()
        for (index in 0 until nodes.length) {
            val plural = nodes.item(index)
            val name = plural.attributes.getNamedItem("name")?.nodeValue ?: continue
            val quantities = linkedSetOf<String>()
            val children = plural.childNodes
            for (childIndex in 0 until children.length) {
                val item = children.item(childIndex)
                if (item.nodeName == "item") {
                    item.attributes.getNamedItem("quantity")?.nodeValue?.let { quantities += it }
                }
            }
            result[name] = quantities
        }
        return result
    }

    @Test
    fun `chinese and english string keys match`() {
        val root = resDir()
        val zh = readStrings(File(root, "values/strings.xml"))
        val en = readStrings(File(root, "values-en/strings.xml"))
        assertEquals("string keys differ", zh.keys, en.keys)
    }

    @Test
    fun `placeholders match per string`() {
        val root = resDir()
        val zh = readStrings(File(root, "values/strings.xml"))
        val en = readStrings(File(root, "values-en/strings.xml"))
        zh.forEach { (name, text) ->
            // 译文可以调整参数顺序（如“A 中有 B”改成“B in A”），只要求占位符种类与数量一致。
            assertEquals(
                "placeholder mismatch for $name",
                placeholder.findAll(text).map { it.value }.toList().groupingBy { it }.eachCount(),
                placeholder.findAll(en.getValue(name)).map { it.value }.toList()
                    .groupingBy { it }.eachCount(),
            )
        }
    }

    @Test
    fun `plural quantities follow locale rules`() {
        val root = resDir()
        val zh = readPluralQuantities(File(root, "values/strings.xml"))
        val en = readPluralQuantities(File(root, "values-en/strings.xml"))
        assertEquals("plural keys differ", zh.keys, en.keys)
        zh.forEach { (name, quantities) ->
            assertEquals("zh plural quantities for $name", setOf("other"), quantities)
        }
        en.forEach { (name, quantities) ->
            assertEquals("en plural quantities for $name", setOf("one", "other"), quantities)
        }
    }

    @Test
    fun `english resources contain no leftover chinese`() {
        val root = resDir()
        val en = readStrings(File(root, "values-en/strings.xml"))
        val leftovers = en.filterValues { han.containsMatchIn(it) }
        assertTrue("English resources contain Chinese: ${leftovers.keys}", leftovers.isEmpty())
    }

    @Test
    fun `locale config lists chinese and english`() {
        val root = resDir()
        val file = File(root, "xml/locales_config.xml")
        assertTrue("missing ${file.path}", file.isFile)
        val document = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(file)
        val nodes = document.getElementsByTagName("locale")
        val locales = (0 until nodes.length).mapNotNull { index ->
            nodes.item(index).attributes.getNamedItem("android:name")?.nodeValue
                ?: nodes.item(index).attributes.getNamedItem("name")?.nodeValue
        }.toSet()
        assertEquals(setOf("zh", "en"), locales)
    }
}
