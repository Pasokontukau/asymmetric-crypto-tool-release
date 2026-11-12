package com.cryptoassistant.app.overlay

import android.view.MotionEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.R
import com.cryptoassistant.app.data.BallWorkMode
import com.cryptoassistant.app.data.keys.ContactKey
import com.cryptoassistant.app.ui.theme.Spacing
import kotlin.math.hypot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 长按多久打开快捷菜单。 */
private const val LONG_PRESS_MILLIS = 380L

/** 胶囊展开面板的大圆角半径。 */
private const val CAPSULE_EXPANDED_CORNER_DP = 24

/**
 * 打开文字面板的内部动作标记。
 *
 * 长按菜单已精简为只选接收方，不再承载动作行；保留下这个枚举是因为面板
 * 仍用它区分「标准模式单击的空白输入框」与磁贴的自动剪贴板入口。
 */
enum class OverlayAction {
    ENCRYPT_TEXT,
}

/** 胶囊里要展示的内容：一次解密结果。 */
data class OverlayCapsulePayload(
    val plaintext: String,
    val senderName: String?,
)

/** 同窗口菜单需要的数据；回调由 OverlayController 持有。 */
internal data class OverlayMenuPayload(
    val contacts: List<ContactKey>,
    val selectedContactId: String?,
    val mode: BallWorkMode,
)

internal const val OVERLAY_MENU_WIDTH_DP = 248
internal const val OVERLAY_MENU_SHADOW_PADDING_DP = 8

/**
 * 悬浮球本体：一个窗口同时承担「球」和「胶囊」两种形态。
 *
 * 手势约定（两种模式一致，差别只在单击做什么）：
 * - 单击：便捷模式静默加密/解密当前输入框；标准模式打开文字输入面板（见 OverlayService）；
 * - 长按：打开快捷菜单，只用来选择加密接收方；
 * - 拖动超过阈值：移动球的位置。
 */
@Composable
fun FloatingBallContent(
    progress: Float,
    capsule: OverlayCapsulePayload?,
    capsuleExpanded: Boolean = false,
    workMode: BallWorkMode = BallWorkMode.QUICK,
    /** 球形态透明度；拉长为胶囊时自动回到 1，保证文字始终可读。 */
    ballOpacity: Float = 1f,
    /** 球图标尺寸，来自同一个尺寸档位。 */
    iconSizeDp: Int = 24,
    gesturesEnabled: Boolean = true,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDragTo: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onCopyPlaintext: () -> Unit,
    onDismissCapsule: () -> Unit,
    onExpandCapsule: () -> Unit = {},
    onCollapseCapsule: () -> Unit = {},
) {
    val primary = MaterialTheme.colorScheme.primary
    val primaryDeep = MaterialTheme.colorScheme.primaryContainer
    val viewConfiguration = LocalViewConfiguration.current
    val haptic = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val gesture = remember { DragGestureState() }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    val expanded = capsule != null || progress > 0.01f
    // 只有球形态使用设定透明度；胶囊一旦开始展开就回到不透明。
    val surfaceAlpha = 1f - (1f - ballOpacity.coerceIn(0f, 1f)) *
        (1f - progress.coerceIn(0f, 1f))

    // 球与胶囊的圆角都是「高度的一半」，所以同一个 RoundedCornerShape(percent 50)
    // 在窗口被拉长时会自然从圆形过渡成药丸形；展开面板改用固定大圆角。
    val shape = if (capsuleExpanded) {
        RoundedCornerShape(CAPSULE_EXPANDED_CORNER_DP.dp)
    } else {
        RoundedCornerShape(percent = 50)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = surfaceAlpha }
            .clip(shape)
            .background(Brush.linearGradient(colors = listOf(primary, primaryDeep)))
            // 高光描边跟随主题的 onPrimary，动态配色/深浅色下都能保持可见。
            .border(
                1.dp,
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.35f),
                shape,
            )
            .pointerInteropFilter { event ->
                // 长按打开菜单后，同一个窗口会立刻变成菜单；手指还没抬起。
                // 这时必须继续消费 MOVE/UP，否则 UP 会落到刚出现的菜单行上（表现为
                // 一松手菜单就自己关掉，甚至误选联系人）。
                if (gesture.active && !gesturesEnabled) {
                    if (event.actionMasked == MotionEvent.ACTION_UP ||
                        event.actionMasked == MotionEvent.ACTION_CANCEL
                    ) {
                        longPressJob?.cancel()
                        gesture.active = false
                        gesture.dragging = false
                    }
                    return@pointerInteropFilter true
                }
                if (!gesturesEnabled || expanded) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        gesture.active = true
                        gesture.downRawX = event.rawX
                        gesture.downRawY = event.rawY
                        gesture.dragging = false
                        gesture.longPressFired = false
                        onDragStart()
                        longPressJob?.cancel()
                        longPressJob = scope.launch {
                            delay(LONG_PRESS_MILLIS)
                            if (!gesture.dragging) {
                                gesture.longPressFired = true
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLongPress()
                            }
                        }
                        true
                    }

                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - gesture.downRawX
                        val dy = event.rawY - gesture.downRawY
                        if (!gesture.dragging && hypot(dx, dy) > viewConfiguration.touchSlop) {
                            gesture.dragging = true
                            longPressJob?.cancel()
                        }
                        if (gesture.dragging) onDragTo(dx, dy)
                        true
                    }

                    MotionEvent.ACTION_UP -> {
                        longPressJob?.cancel()
                        when {
                            gesture.dragging -> onDragEnd()
                            !gesture.longPressFired -> onTap()
                        }
                        gesture.dragging = false
                        gesture.active = false
                        true
                    }

                    MotionEvent.ACTION_CANCEL -> {
                        longPressJob?.cancel()
                        if (gesture.dragging) onDragEnd()
                        gesture.dragging = false
                        gesture.active = false
                        true
                    }

                    else -> false
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 球图标随胶囊展开淡出；展开面板时不再显示球图标。
        if (progress < 0.98f && !capsuleExpanded) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(1f - progress),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Shield,
                    contentDescription = when (workMode) {
                        BallWorkMode.QUICK -> stringResource(R.string.ball_cd_quick)
                        BallWorkMode.STANDARD -> stringResource(R.string.ball_cd_standard)
                    },
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(iconSizeDp.dp),
                )
            }
        }

        if (capsule != null && progress > 0.02f) {
            if (capsuleExpanded) {
                CapsuleDetail(
                    payload = capsule,
                    alpha = progress,
                    onCopy = onCopyPlaintext,
                    onCollapse = onCollapseCapsule,
                )
            } else {
                CapsuleRow(
                    payload = capsule,
                    alpha = progress,
                    onCopy = onCopyPlaintext,
                    onDismiss = onDismissCapsule,
                    onExpand = onExpandCapsule,
                )
            }
        }
    }
}

/**
 * 球窗口的统一内容：球/胶囊层与菜单层在同一个 ComposeView 内交叉淡入。
 * 菜单内容固定 248dp 宽，窗口变大时只裁剪，不让文字重排。
 */
@Composable
internal fun OverlayWindowContent(
    ballProgress: Float,
    capsule: OverlayCapsulePayload?,
    capsuleExpanded: Boolean,
    workMode: BallWorkMode,
    ballOpacity: Float,
    iconSizeDp: Int,
    menu: OverlayMenuPayload?,
    /** 用 lambda 而不是 Float：动画进度只在绘制层读取，避免菜单每帧重组。 */
    menuProgress: () -> Float,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    onDragStart: () -> Unit,
    onDragTo: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onCopyPlaintext: () -> Unit,
    onDismissCapsule: () -> Unit,
    onExpandCapsule: () -> Unit,
    onCollapseCapsule: () -> Unit,
    onSelectContact: (String) -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 菜单展开时球层快速退场，避免拉成长条后还和菜单叠加。
                    val p = menuProgress().coerceIn(0f, 1f)
                    alpha = (1f - p * 1.6f).coerceIn(0f, 1f)
                },
        ) {
            FloatingBallContent(
                progress = ballProgress,
                capsule = capsule,
                capsuleExpanded = capsuleExpanded,
                workMode = workMode,
                ballOpacity = ballOpacity,
                iconSizeDp = iconSizeDp,
                gesturesEnabled = menu == null,
                onTap = onTap,
                onLongPress = onLongPress,
                onDragStart = onDragStart,
                onDragTo = onDragTo,
                onDragEnd = onDragEnd,
                onCopyPlaintext = onCopyPlaintext,
                onDismissCapsule = onDismissCapsule,
                onExpandCapsule = onExpandCapsule,
                onCollapseCapsule = onCollapseCapsule,
            )
        }

        if (menu != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = menuProgress().coerceIn(0f, 1f) },
                contentAlignment = Alignment.TopStart,
            ) {
                Box(
                    modifier = Modifier
                        .padding(OVERLAY_MENU_SHADOW_PADDING_DP.dp)
                        .requiredWidth(OVERLAY_MENU_WIDTH_DP.dp)
                        .wrapContentHeight(),
                ) {
                    FloatingBallMenu(
                        mode = menu.mode,
                        contacts = menu.contacts,
                        selectedContactId = menu.selectedContactId,
                        onSelectContact = onSelectContact,
                    )
                }
            }
        }
    }
}

/** 离屏测量菜单最终高度；只用于测量，不参与展示。 */
@Composable
internal fun OverlayMenuMeasure(
    mode: BallWorkMode,
    contacts: List<ContactKey>,
    selectedContactId: String?,
) {
    Box(
        modifier = Modifier
            .padding(OVERLAY_MENU_SHADOW_PADDING_DP.dp)
            .requiredWidth(OVERLAY_MENU_WIDTH_DP.dp)
            .wrapContentHeight(),
    ) {
        FloatingBallMenu(
            mode = mode,
            contacts = contacts,
            selectedContactId = selectedContactId,
            onSelectContact = {},
        )
    }
}

/** 胶囊内的明文展示：图标 + 明文（两行）+ 复制 / 收起；明文溢出时点击文字可展开。 */
@Composable
private fun CapsuleRow(
    payload: OverlayCapsulePayload,
    alpha: Float,
    onCopy: () -> Unit,
    onDismiss: () -> Unit,
    onExpand: () -> Unit,
) {
    var overflow by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .padding(start = Spacing.lg, end = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.width(Spacing.sm))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = payload.plaintext.ifBlank { stringResource(R.string.capsule_empty_plaintext) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                onTextLayout = { result -> overflow = result.hasVisualOverflow },
                modifier = if (overflow) {
                    Modifier.clickable(onClick = onExpand)
                } else {
                    Modifier
                },
            )
            Text(
                text = payload.senderName?.let {
                    stringResource(R.string.capsule_signature_from, it)
                } ?: stringResource(R.string.capsule_sender_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        IconButton(onClick = onCopy) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.capsule_cd_copy_plaintext),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onDismiss) {
            Icon(
                imageVector = Icons.Outlined.Close,
                contentDescription = stringResource(R.string.capsule_cd_collapse),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * 胶囊展开后的可滚动面板：标题行（发送者 + 收起）+ 可滚动全文 + 底部复制。
 * 只用于 [OverlayCapsulePayload.plaintext] 超长、两行装不下的场景。
 */
@Composable
private fun CapsuleDetail(
    payload: OverlayCapsulePayload,
    alpha: Float,
    onCopy: () -> Unit,
    onCollapse: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .alpha(alpha)
            .padding(Spacing.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.LockOpen,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Text(
                text = payload.senderName?.let {
                    stringResource(R.string.capsule_signature_from, it)
                } ?: stringResource(R.string.capsule_sender_unknown),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.capsule_cd_collapse_panel),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(
            color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f),
            modifier = Modifier.padding(vertical = Spacing.sm),
        )
        Text(
            text = payload.plaintext.ifBlank { stringResource(R.string.capsule_empty_plaintext) },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            IconButton(onClick = onCopy) {
                Icon(
                    imageVector = Icons.Outlined.ContentCopy,
                    contentDescription = stringResource(R.string.capsule_cd_copy_full),
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private class DragGestureState {
    var active = false
    var downRawX = 0f
    var downRawY = 0f
    var dragging = false
    var longPressFired = false
}

/**
 * 长按打开的快捷菜单：只负责「加密给谁」。
 *
 * 工作模式与文件加解密都是低频设置/任务，统一放在应用内；菜单不再承载动作行，
 * 长按后点一个联系人就结束，避免菜单过长、误触和两种模式的入口分叉。
 */
@Composable
fun FloatingBallMenu(
    mode: BallWorkMode = BallWorkMode.QUICK,
    contacts: List<ContactKey> = emptyList(),
    selectedContactId: String? = null,
    onSelectContact: (String) -> Unit = {},
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(Spacing.sm)
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = stringResource(R.string.ball_menu_encrypt_to),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
            )
            if (contacts.isEmpty()) {
                Text(
                    text = if (mode == BallWorkMode.QUICK) {
                        stringResource(R.string.ball_menu_no_contacts_quick)
                    } else {
                        stringResource(R.string.ball_menu_no_contacts_standard)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            } else {
                contacts.forEach { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(MaterialTheme.shapes.medium)
                            .selectable(
                                selected = contact.id == selectedContactId,
                                onClick = { onSelectContact(contact.id) },
                            )
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = contact.id == selectedContactId,
                            onClick = { onSelectContact(contact.id) },
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = contact.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = contact.fingerprint.takeLast(8).chunked(4).joinToString(" "),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 菜单打开时的透明遮罩：点击任意空白处关闭菜单。[alpha] 随展开/收回动画变化。 */
@Composable
fun OverlayScrim(
    alpha: Float = 0.30f,
    enabled: Boolean = true,
    onDismiss: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = alpha))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        // 遮罩本身不绘制内容，只承担点击关闭。
    }
}

@Composable
fun OverlayMenuPreviewShell() {
    // 供 Compose Preview 使用的容器，不参与运行时逻辑。
    Box(modifier = Modifier.fillMaxWidth()) {
        FloatingBallMenu(mode = BallWorkMode.QUICK)
    }
}
