package com.cryptoassistant.app.feature.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideContentTest {

    private val zh = GuideContent.book(GuideLanguage.ZH)
    private val en = GuideContent.book(GuideLanguage.EN)
    private val books = listOf(zh, en)

    private fun GuideBook.allSections() = chapters.flatMap { it.sections }

    @Test
    fun `eight chapters numbered continuously`() {
        books.forEach { book ->
            assertEquals(8, book.chapters.size)
            book.chapters.forEachIndexed { index, chapter ->
                assertEquals(index + 1, chapter.number)
            }
        }
    }

    @Test
    fun `chapter ids are unique`() {
        books.forEach { book ->
            val ids = book.chapters.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `thirty three sections with expected per chapter counts`() {
        val expected = mapOf(1 to 3, 2 to 5, 3 to 4, 4 to 4, 5 to 5, 6 to 3, 7 to 5, 8 to 4)
        books.forEach { book ->
            assertEquals(33, book.totalSections())
            book.chapters.forEach { chapter ->
                assertEquals(expected[chapter.number], chapter.sections.size)
            }
        }
    }

    @Test
    fun `section ids are globally unique`() {
        books.forEach { book ->
            val ids = book.allSections().map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `section numbers match their chapter`() {
        books.forEach { book ->
            book.chapters.forEach { chapter ->
                chapter.sections.forEach { section ->
                    assertTrue(
                        "节号 ${section.number} 不属于第 ${chapter.number} 章",
                        section.number.startsWith("${chapter.number}."),
                    )
                }
            }
        }
    }

    @Test
    fun `chapter key points are exactly three`() {
        books.forEach { book ->
            book.chapters.forEach { chapter ->
                assertEquals(chapter.id, 3, chapter.keyPoints.size)
                chapter.keyPoints.forEach { assertTrue(it.isNotBlank()) }
            }
        }
    }

    @Test
    fun `sections have required text and at least one block`() {
        books.forEach { book ->
            book.allSections().forEach { section ->
                assertTrue(section.title.isNotBlank())
                assertTrue(section.summary.isNotBlank())
                assertTrue(section.lead.isNotBlank())
                assertTrue(section.blocks.isNotEmpty())
                section.blocks.forEach { block ->
                    when (block) {
                        is GuideBlock.Heading -> assertTrue(block.text.isNotBlank())
                        is GuideBlock.Paragraph -> assertTrue(block.text.isNotBlank())
                        is GuideBlock.Steps -> assertTrue(block.items.isNotEmpty())
                        is GuideBlock.Bullets -> assertTrue(block.items.isNotEmpty())
                        is GuideBlock.Note -> {
                            assertTrue(block.title.isNotBlank())
                            assertTrue(block.text.isNotBlank())
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `content has no placeholder words`() {
        val placeholders = listOf("TODO", "TBD", "待补", "占位")
        books.forEach { book ->
            val text = buildString {
                book.chapters.forEach { chapter ->
                    append(chapter.title)
                    append(chapter.summary)
                    chapter.keyPoints.forEach { append(it) }
                    chapter.sections.forEach { section ->
                        append(section.title)
                        append(section.summary)
                        append(section.lead)
                        section.blocks.forEach { block ->
                            when (block) {
                                is GuideBlock.Heading -> append(block.text)
                                is GuideBlock.Paragraph -> append(block.text)
                                is GuideBlock.Steps -> block.items.forEach { append(it) }
                                is GuideBlock.Bullets -> block.items.forEach { append(it) }
                                is GuideBlock.Note -> {
                                    append(block.title)
                                    append(block.text)
                                }
                            }
                        }
                    }
                }
            }
            placeholders.forEach { word ->
                assertFalse("正文包含占位词 $word", text.contains(word))
            }
        }
    }

    @Test
    fun `related refs exist and never point to themselves`() {
        books.forEach { book ->
            book.chapters.forEach { chapter ->
                chapter.sections.forEach { section ->
                    assertTrue(section.related.size <= 3)
                    section.related.forEach { ref ->
                        val target = book.section(ref.chapterId, ref.sectionId)
                        assertNotNull("相关条目不存在: ${ref.chapterId}/${ref.sectionId}", target)
                        assertFalse(
                            "相关条目不能指向自己: ${chapter.id}/${section.id}",
                            ref.chapterId == chapter.id && ref.sectionId == section.id,
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `queries resolve chapters and sections`() {
        books.forEach { book ->
            book.chapters.forEach { chapter ->
                assertEquals(chapter.id, chapter, book.chapter(chapter.id))
                chapter.sections.forEach { section ->
                    assertEquals(section.id, section, book.section(chapter.id, section.id))
                    assertNotNull(book.titleOf(GuideRef(chapter.id, section.id)))
                }
            }
            assertNull(book.chapter("missing"))
            assertNull(book.section("missing", "missing"))
        }
    }

    @Test
    fun `previous and next helpers stop at boundaries`() {
        books.forEach { book ->
            book.chapters.forEach { chapter ->
                assertNull(book.previousSection(chapter.id, chapter.sections.first().id))
                assertNull(book.nextSection(chapter.id, chapter.sections.last().id))
                chapter.sections.dropLast(1).forEach { section ->
                    assertNotNull(book.nextSection(chapter.id, section.id))
                }
                chapter.sections.drop(1).forEach { section ->
                    assertNotNull(book.previousSection(chapter.id, section.id))
                }
            }
            assertNull(book.previousChapter(book.chapters.first().id))
            assertNull(book.nextChapter(book.chapters.last().id))
        }
    }

    @Test
    fun `chapter navigation walks the whole catalog`() {
        books.forEach { book ->
            var chapter: GuideChapter? = book.chapters.first()
            val visited = mutableListOf<String>()
            while (chapter != null) {
                visited += chapter.id
                chapter = book.nextChapter(chapter.id)
            }
            assertEquals(book.chapters.map { it.id }, visited)
        }
    }

    @Test
    fun `english covers every chapter and section`() {
        assertEquals(
            zh.chapters.map { it.id }.toSet(),
            GuideContentEn.chapters.keys,
        )
        assertEquals(
            zh.chapters.flatMap { chapter -> chapter.sections.map { chapter.id to it.id } }.toSet(),
            GuideContentEn.sections.keys,
        )
    }

    @Test
    fun `english keeps ids numbers relations and block shapes`() {
        assertEquals(zh.chapters.map { it.id }, en.chapters.map { it.id })
        zh.chapters.forEach { zhChapter ->
            val enChapter = en.chapter(zhChapter.id)
            assertNotNull("缺少英文章 ${zhChapter.id}", enChapter)
            enChapter!!
            assertEquals(zhChapter.number, enChapter.number)
            assertEquals(zhChapter.keyPoints.size, enChapter.keyPoints.size)
            assertEquals(zhChapter.sections.map { it.id }, enChapter.sections.map { it.id })
            zhChapter.sections.forEach { zhSection ->
                val enSection = en.section(zhChapter.id, zhSection.id)
                assertNotNull("缺少英文节 ${zhChapter.id}/${zhSection.id}", enSection)
                enSection!!
                assertEquals(zhSection.number, enSection.number)
                assertEquals(zhSection.related, enSection.related)
                assertEquals(zhSection.blocks.size, enSection.blocks.size)
                zhSection.blocks.zip(enSection.blocks).forEach { (zhBlock, enBlock) ->
                    assertSameShape(zhBlock, enBlock)
                }
            }
        }
    }

    @Test
    fun `english text contains no chinese`() {
        val han = Regex("""\p{IsHan}""")
        books.filter { it.language == GuideLanguage.EN }.forEach { book ->
            fun check(text: String) {
                assertFalse("英文工具书残留中文: $text", han.containsMatchIn(text))
            }
            book.chapters.forEach { chapter ->
                check(chapter.title)
                check(chapter.summary)
                chapter.keyPoints.forEach { check(it) }
                chapter.sections.forEach { section ->
                    check(section.title)
                    check(section.summary)
                    check(section.lead)
                    section.blocks.forEach { block ->
                        when (block) {
                            is GuideBlock.Heading -> check(block.text)
                            is GuideBlock.Paragraph -> check(block.text)
                            is GuideBlock.Steps -> block.items.forEach { check(it) }
                            is GuideBlock.Bullets -> block.items.forEach { check(it) }
                            is GuideBlock.Note -> {
                                check(block.title)
                                check(block.text)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `language selection follows the interface language`() {
        assertEquals(GuideLanguage.EN, GuideContent.languageOf("en"))
        assertEquals(GuideLanguage.EN, GuideContent.languageOf("en-US"))
        assertEquals(GuideLanguage.ZH, GuideContent.languageOf("zh"))
        assertEquals(GuideLanguage.ZH, GuideContent.languageOf("zh-CN"))
        assertEquals(GuideLanguage.ZH, GuideContent.languageOf(null))
    }

    private fun assertSameShape(zhBlock: GuideBlock, enBlock: GuideBlock) {
        assertEquals(zhBlock::class, enBlock::class)
        when (zhBlock) {
            is GuideBlock.Heading -> Unit
            is GuideBlock.Paragraph -> Unit
            is GuideBlock.Steps ->
                assertEquals(zhBlock.items.size, (enBlock as GuideBlock.Steps).items.size)

            is GuideBlock.Bullets ->
                assertEquals(zhBlock.items.size, (enBlock as GuideBlock.Bullets).items.size)

            is GuideBlock.Note -> Unit
        }
    }
}
