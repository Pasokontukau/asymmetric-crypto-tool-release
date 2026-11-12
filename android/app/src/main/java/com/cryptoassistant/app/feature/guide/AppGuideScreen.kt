package com.cryptoassistant.app.feature.guide

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.BuildConfig
import com.cryptoassistant.app.R
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.theme.Spacing

/** 按当前界面语言取一本工具书；语言变化时重新构建。 */
@Composable
private fun rememberGuideBook(): GuideBook {
    val locale = LocalConfiguration.current.locales[0]
    return remember(locale) { GuideContent.book(GuideContent.languageOf(locale.language)) }
}

/**
 * 使用说明工具书。
 *
 * 三个页面共用一本按语言组装好的 [GuideBook]：目录（章列表）、章页（章导语 + 节列表）、
 * 节文章（正文块 + 相关条目）。页面只负责阅读，不承载开关或权限操作。
 */
@Composable
internal fun AppGuideScreen(
    onOpenChapter: (String) -> Unit,
    onOpenSection: (GuideRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = rememberGuideBook()
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            AppCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Outlined.MenuBook,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(Modifier.width(Spacing.md))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.guide_home_title),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = stringResource(
                                R.string.guide_home_subtitle,
                                book.chapters.size,
                                book.totalSections(),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = stringResource(
                        R.string.guide_home_version_note,
                        BuildConfig.VERSION_NAME,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { SectionTitle(stringResource(R.string.guide_section_quick_start)) }

        item {
            AppCard {
                GuideLinkRow(
                    title = stringResource(R.string.guide_quick_first_run_title),
                    subtitle = stringResource(R.string.guide_quick_first_run_subtitle),
                    onClick = { onOpenSection(GuideRef("start", "first-run")) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                GuideLinkRow(
                    title = stringResource(R.string.guide_quick_text_title),
                    subtitle = stringResource(R.string.guide_quick_text_subtitle),
                    onClick = { onOpenSection(GuideRef("text", "text-quick")) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                GuideLinkRow(
                    title = stringResource(R.string.guide_quick_files_title),
                    subtitle = stringResource(R.string.guide_quick_files_subtitle),
                    onClick = { onOpenSection(GuideRef("files", "file-home")) },
                )
            }
        }

        item { SectionTitle(stringResource(R.string.guide_section_contents)) }

        item {
            AppCard {
                book.chapters.forEachIndexed { index, chapter ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    GuideChapterRow(chapter = chapter, onClick = { onOpenChapter(chapter.id) })
                }
            }
        }

        item {
            Spacer(Modifier.height(Spacing.xl))
            Text(
                text = stringResource(R.string.guide_home_footer),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 章页：章导语、本章要点与节列表。 */
@Composable
internal fun GuideChapterScreen(
    chapterId: String,
    onOpenGuideHome: () -> Unit,
    onOpenSection: (GuideRef) -> Unit,
    onOpenChapter: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = rememberGuideBook()
    val chapter = book.chapter(chapterId)
    if (chapter == null) {
        GuideMissingScreen(onBackToGuideHome = onOpenGuideHome, modifier = modifier)
        return
    }

    val previous = book.previousChapter(chapter.id)
    val next = book.nextChapter(chapter.id)

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            GuideBreadcrumb(
                crumbs = listOf(
                    GuideCrumb(stringResource(R.string.guide_breadcrumb_home), onOpenGuideHome),
                    GuideCrumb(
                        stringResource(R.string.guide_chapter_label, chapter.number),
                        null,
                    ),
                ),
            )
        }

        item {
            Spacer(Modifier.height(Spacing.lg))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = guideChapterIcon(chapter.number),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(Spacing.xs))
                Text(
                    text = stringResource(R.string.guide_chapter_label, chapter.number),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = chapter.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Spacer(Modifier.height(Spacing.lg))
            AppCard {
                Text(
                    text = stringResource(R.string.guide_chapter_key_points),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.sm))
                chapter.keyPoints.forEach { point ->
                    GuideBullet(point)
                }
            }
        }

        item { SectionTitle(stringResource(R.string.guide_chapter_contents)) }

        item {
            AppCard {
                chapter.sections.forEachIndexed { index, section ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    GuideSectionRow(
                        section = section,
                        onClick = { onOpenSection(GuideRef(chapter.id, section.id)) },
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (previous != null) {
                    TextButton(onClick = { onOpenChapter(previous.id) }) {
                        Text(stringResource(R.string.guide_prev_chapter))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (next != null) {
                    TextButton(onClick = { onOpenChapter(next.id) }) {
                        Text(stringResource(R.string.guide_next_chapter))
                    }
                }
            }
        }
    }
}

/** 节文章：面包屑、导语、正文块、相关条目与前后节。 */
@Composable
internal fun GuideSectionScreen(
    chapterId: String,
    sectionId: String,
    onOpenGuideHome: () -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenSection: (GuideRef) -> Unit,
    modifier: Modifier = Modifier,
) {
    val book = rememberGuideBook()
    val chapter = book.chapter(chapterId)
    val section = book.section(chapterId, sectionId)
    if (chapter == null || section == null) {
        GuideMissingScreen(onBackToGuideHome = onOpenGuideHome, modifier = modifier)
        return
    }

    val previous = book.previousSection(chapter.id, section.id)
    val next = book.nextSection(chapter.id, section.id)
    val related = section.related.mapNotNull { ref ->
        book.titleOf(ref)?.let { title -> ref to title }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = Spacing.screenPadding,
            end = Spacing.screenPadding,
            bottom = Spacing.xxl,
        ),
    ) {
        item {
            Spacer(Modifier.height(Spacing.sm))
            GuideBreadcrumb(
                crumbs = listOf(
                    GuideCrumb(stringResource(R.string.guide_breadcrumb_home), onOpenGuideHome),
                    GuideCrumb(chapter.title, { onOpenChapter(chapter.id) }),
                    GuideCrumb(section.number, null),
                ),
            )
        }

        item {
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = "${section.number} ${section.title}",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = section.lead,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        section.blocks.forEach { block ->
            item {
                Spacer(Modifier.height(Spacing.lg))
                GuideBlockView(block)
            }
        }

        if (related.isNotEmpty()) {
            item { SectionTitle(stringResource(R.string.guide_section_related)) }
            item {
                AppCard {
                    related.forEachIndexed { index, (ref, title) ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        GuideLinkRow(
                            title = title,
                            subtitle = book.chapter(ref.chapterId)?.title.orEmpty(),
                            onClick = { onOpenSection(ref) },
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(Spacing.xl))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (previous != null) {
                    TextButton(onClick = {
                        onOpenSection(GuideRef(chapter.id, previous.id))
                    }) {
                        Text(stringResource(R.string.guide_prev_section))
                    }
                } else {
                    Spacer(Modifier.width(1.dp))
                }
                if (next != null) {
                    TextButton(onClick = {
                        onOpenSection(GuideRef(chapter.id, next.id))
                    }) {
                        Text(stringResource(R.string.guide_next_section))
                    }
                }
            }
            TextButton(
                onClick = { onOpenChapter(chapter.id) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.guide_back_to_chapter))
            }
        }
    }
}

@Composable
private fun GuideBlockView(block: GuideBlock) {
    when (block) {
        is GuideBlock.Heading -> Text(
            text = block.text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is GuideBlock.Paragraph -> Text(
            text = block.text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        is GuideBlock.Steps -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            block.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(24.dp),
                    ) {
                        Text(
                            text = "${index + 1}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(top = 3.dp),
                        )
                    }
                    Spacer(Modifier.width(Spacing.sm))
                    Text(
                        text = item,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        is GuideBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            block.items.forEach { item -> GuideBullet(item) }
        }

        is GuideBlock.Note -> Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(Spacing.md)) {
                Text(
                    text = block.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GuideBullet(text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = "·",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(Spacing.md),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

private data class GuideCrumb(
    val label: String,
    val onClick: (() -> Unit)?,
)

@Composable
private fun GuideBreadcrumb(crumbs: List<GuideCrumb>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        crumbs.forEachIndexed { index, crumb ->
            if (index > 0) {
                Text(
                    text = "›",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = crumb.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (crumb.onClick != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = if (crumb.onClick != null) {
                    Modifier.clickable(onClick = crumb.onClick)
                } else {
                    Modifier
                },
            )
        }
    }
}

@Composable
private fun GuideChapterRow(chapter: GuideChapter, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BoxChapterNumber(chapter.number)
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = chapter.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = chapter.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = pluralStringResource(
                R.plurals.guide_section_count,
                chapter.sections.size,
                chapter.sections.size,
            ),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(Spacing.xs))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BoxChapterNumber(number: Int) {
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(36.dp),
    ) {
        Text(
            text = number.toString().padStart(2, '0'),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 8.dp),
        )
    }
}

@Composable
private fun GuideSectionRow(section: GuideSection, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = section.number,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = section.summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun GuideLinkRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 章/节 ID 失效时的兜底页：不崩溃、不静默回首页。 */
@Composable
private fun GuideMissingScreen(onBackToGuideHome: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Spacing.xxl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.ErrorOutline,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(Spacing.lg))
        Text(
            text = stringResource(R.string.guide_missing_title),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = stringResource(R.string.guide_missing_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(onClick = onBackToGuideHome) {
            Text(stringResource(R.string.guide_missing_back))
        }
    }
}

/** 供目录页按章号取图标；图标不进入数据模型。 */
internal fun guideChapterIcon(number: Int): ImageVector = when (number) {
    1 -> Icons.Outlined.PlayCircleOutline
    2 -> Icons.Outlined.TouchApp
    3 -> Icons.Outlined.Folder
    4 -> Icons.Outlined.VpnKey
    5 -> Icons.Outlined.Settings
    6 -> Icons.Outlined.Fingerprint
    7 -> Icons.Outlined.BugReport
    else -> Icons.AutoMirrored.Outlined.MenuBook
}
