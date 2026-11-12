package com.cryptoassistant.app.feature.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.cryptoassistant.app.ui.components.AppCard
import com.cryptoassistant.app.ui.components.InfoBullet
import com.cryptoassistant.app.ui.components.SectionTitle
import com.cryptoassistant.app.ui.components.StatusPill
import com.cryptoassistant.app.ui.components.StatusTone
import com.cryptoassistant.app.ui.theme.Spacing

/**
 * 功能阶段状态页：明确展示当前阶段已实现/未实现的内容，避免用占位按钮冒充功能。
 */
@Composable
fun FeatureStatusScreen(
    icon: ImageVector,
    title: String,
    phaseLabel: String,
    summary: String,
    implemented: List<String>,
    planned: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenPadding),
    ) {
        Spacer(Modifier.height(Spacing.md))
        AppCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(26.dp),
                    )
                }
                Spacer(Modifier.width(Spacing.lg))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(Spacing.xs))
                    StatusPill(text = phaseLabel, tone = StatusTone.WARNING)
                }
            }
            Spacer(Modifier.height(Spacing.lg))
            Text(
                text = summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        SectionTitle("本阶段已完成")
        AppCard {
            implemented.forEach { item ->
                BulletLine(text = item, done = true)
            }
        }

        SectionTitle("尚未接入")
        AppCard {
            if (planned.isEmpty()) {
                BulletLine(text = "无", done = false)
            } else {
                planned.forEach { item ->
                    BulletLine(text = item, done = false)
                }
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = "未完成的功能不会提供可点击的假按钮；达到对应阶段后才会上线。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (title.startsWith("文字")) {
            SectionTitle("安全边界")
            AppCard {
                InfoBullet("所有加解密计划在本机完成，不依赖服务器。")
                InfoBullet("密钥、口令、明文不会写入日志或上传。")
                InfoBullet("签名有效性会与“仅解密成功”分开显示。")
            }
        }

        Spacer(Modifier.height(Spacing.section))
    }
}

@Composable
private fun BulletLine(text: String, done: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.Start,
    ) {
        Icon(
            imageVector = if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
            contentDescription = null,
            tint = if (done) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier
                .padding(top = 2.dp)
                .size(18.dp),
        )
        Spacer(Modifier.width(Spacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
