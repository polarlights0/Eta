package io.github.mangi.eta.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.InputOptimizeMode
import io.github.mangi.eta.ui.model.InputOptimizePhase
import io.github.mangi.eta.ui.model.InputOptimizeUiState
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.squircle.squircleBorder
import top.yukonga.miuix.kmp.squircle.squircleSurface
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val OptimizeStripCornerRadius = 16.dp
private val OptimizeStripMaxResultHeight = 148.dp
private val OptimizeStripActionHeight = 32.dp

/**
 * 输入框 AI 优化入口：位于输入框工具栏左侧、附件按钮旁。
 *
 * 与参考实现（dsh-prompt-library 的 composer 润色按钮）一致，只用当前草稿触发一次改写；
 * 处理中位置换成转圈指示（空闲时不跑任何常驻动画），面板展开期间按钮保持主题色高亮，
 * 草稿为空时禁用。
 */
@Composable
internal fun AgentInputOptimizeButton(
    manualEnabled: Boolean,
    optimize: InputOptimizeUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = MiuixTheme.colorScheme
    val tint = when {
        !manualEnabled -> colors.disabledOnSecondaryVariant
        optimize.isVisible -> colors.primary
        else -> colors.onSurface
    }
    IconButton(
        onClick = onClick,
        enabled = manualEnabled,
        minWidth = ChatInputActionSize,
        minHeight = ChatInputActionSize,
        modifier = modifier,
    ) {
        if (optimize.isRunning) {
            InfiniteProgressIndicator(size = ChatInputActionIconSize)
        } else {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = stringResource(R.string.input_optimize_button_description),
                modifier = Modifier.size(ChatInputActionIconSize),
                tint = tint,
            )
        }
    }
}

/**
 * 悬浮在输入框正上方的输入优化面板。
 *
 * 结构与排队消息胶囊、待发送附件条一致：占据输入框上方的同一条区域，不遮挡消息流；
 * 内部按阶段切换：运行中（流式预览）→ 结果（采用 / 重新生成 / 复制）→ 失败（重试）→ 已应用（撤销）。
 */
@Composable
internal fun AgentInputOptimizeStrip(
    optimize: InputOptimizeUiState,
    onModeChange: (InputOptimizeMode) -> Unit,
    onApply: () -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** 已应用且输入框仍与结果一致时才允许撤销，避免覆盖用户接下来的手改。 */
    undoEnabled: Boolean = optimize.canUndo,
) {
    if (!optimize.isVisible) return
    val colors = MiuixTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .squircleSurface(color = colors.surfaceContainer, cornerRadius = OptimizeStripCornerRadius)
            .squircleBorder(
                width = 0.5.dp,
                color = colors.outline.copy(alpha = 0.55f),
                cornerRadius = OptimizeStripCornerRadius,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = colors.primary,
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.input_optimize_title),
                style = MiuixTheme.textStyles.footnote1,
                fontWeight = FontWeight.Medium,
                color = colors.onSurface,
                maxLines = 1,
            )
            Spacer(modifier = Modifier.weight(1f))
            if (optimize.result.isNotBlank() && optimize.phase != InputOptimizePhase.Applied) {
                Text(
                    text = stringResource(
                        R.string.input_optimize_length_delta,
                        optimize.source.length,
                        optimize.result.length,
                    ),
                    style = MiuixTheme.textStyles.footnote2,
                    color = colors.onSurfaceVariantSummary,
                    maxLines = 1,
                )
                Spacer(modifier = Modifier.width(6.dp))
            }
            IconButton(
                onClick = onDismiss,
                minWidth = OptimizeStripActionHeight,
                minHeight = OptimizeStripActionHeight,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = stringResource(R.string.action_close),
                    modifier = Modifier.size(16.dp),
                    tint = colors.onSurfaceVariantSummary,
                )
            }
        }

        if (optimize.phase != InputOptimizePhase.Applied) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InputOptimizeMode.ALL.forEach { mode ->
                    EtaChoiceChip(
                        text = stringResource(mode.labelRes()),
                        selected = optimize.mode == mode,
                        enabled = !optimize.isRunning,
                        onClick = { onModeChange(mode) },
                    )
                }
            }
        }

        when (optimize.phase) {
            InputOptimizePhase.Running -> InputOptimizeRunningBody(optimize)
            InputOptimizePhase.Ready -> InputOptimizeResultBody(optimize.result, colors.onSurface)
            InputOptimizePhase.Failed -> Text(
                text = optimize.errorMessage,
                style = MiuixTheme.textStyles.footnote1,
                color = colors.error,
            )
            InputOptimizePhase.Applied -> Text(
                text = stringResource(R.string.input_optimize_applied),
                style = MiuixTheme.textStyles.footnote1,
                color = colors.onSurfaceVariantSummary,
            )
            InputOptimizePhase.Idle -> Unit
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(modifier = Modifier.weight(1f))
            when (optimize.phase) {
                InputOptimizePhase.Running -> TextButton(
                    text = stringResource(R.string.action_cancel),
                    onClick = onDismiss,
                    minWidth = 0.dp,
                    minHeight = OptimizeStripActionHeight,
                    insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                )
                InputOptimizePhase.Ready -> {
                    TextButton(
                        text = stringResource(R.string.input_optimize_action_copy),
                        onClick = { clipboard.setText(AnnotatedString(optimize.result)) },
                        minWidth = 0.dp,
                        minHeight = OptimizeStripActionHeight,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                    TextButton(
                        text = stringResource(R.string.input_optimize_action_retry),
                        onClick = onRetry,
                        minWidth = 0.dp,
                        minHeight = OptimizeStripActionHeight,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                    TextButton(
                        text = stringResource(R.string.input_optimize_action_apply),
                        onClick = onApply,
                        minWidth = 0.dp,
                        minHeight = OptimizeStripActionHeight,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                InputOptimizePhase.Failed -> {
                    TextButton(
                        text = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        minWidth = 0.dp,
                        minHeight = OptimizeStripActionHeight,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    )
                    TextButton(
                        text = stringResource(R.string.action_retry),
                        onClick = onRetry,
                        minWidth = 0.dp,
                        minHeight = OptimizeStripActionHeight,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                InputOptimizePhase.Applied -> {
                    if (undoEnabled) {
                        TextButton(
                            text = stringResource(R.string.input_optimize_action_undo),
                            onClick = onUndo,
                            minWidth = 0.dp,
                            minHeight = OptimizeStripActionHeight,
                            insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        )
                    }
                    TextButton(
                        text = stringResource(R.string.action_close),
                        onClick = onDismiss,
                        minWidth = 0.dp,
                        minHeight = OptimizeStripActionHeight,
                        insideMargin = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
                InputOptimizePhase.Idle -> Unit
            }
        }
    }
}

/** 运行中：转圈 + 若模型已开始输出则同步预览增量文字，避免长草稿时面板一片空白。 */
@Composable
private fun InputOptimizeRunningBody(optimize: InputOptimizeUiState) {
    val colors = MiuixTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        InfiniteProgressIndicator(size = 16.dp)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.input_optimize_running),
            style = MiuixTheme.textStyles.footnote1,
            color = colors.onSurfaceVariantSummary,
            maxLines = 1,
        )
    }
    if (optimize.result.isBlank()) return
    InputOptimizeResultBody(optimize.result, colors.onSurfaceVariantSummary)
}

/** 结果正文：限制高度后内部滚动，长草稿优化后不会把输入框顶出屏幕。 */
@Composable
private fun InputOptimizeResultBody(
    text: String,
    color: Color,
) {
    if (text.isBlank()) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = OptimizeStripMaxResultHeight)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = text,
            style = MiuixTheme.textStyles.body2,
            color = color,
            overflow = TextOverflow.Clip,
        )
    }
}

@StringRes
private fun InputOptimizeMode.labelRes(): Int = when (this) {
    InputOptimizeMode.POLISH -> R.string.input_optimize_mode_polish
    InputOptimizeMode.CONCISE -> R.string.input_optimize_mode_concise
    InputOptimizeMode.DETAIL -> R.string.input_optimize_mode_detail
    InputOptimizeMode.STRUCTURE -> R.string.input_optimize_mode_structure
}
