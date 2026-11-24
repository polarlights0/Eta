package io.github.mangi.eta.ui.model

import androidx.compose.runtime.Immutable
import io.github.mangi.eta.data.model.InputOptimizeMode

/** 输入优化面板所处的阶段。面板只在非 [Idle] 阶段展开在输入框上方。 */
internal enum class InputOptimizePhase {
    /** 面板收起，输入框保持用户草稿。 */
    Idle,

    /** 正在调用模型，可展示流式预览，可被取消。 */
    Running,

    /** 已拿到可用结果，等待用户采用或重试。 */
    Ready,

    /** 调用失败，展示原因并提供重试。 */
    Failed,

    /** 结果已写回输入框，保留一次撤销入口。 */
    Applied,
}

/**
 * 输入框 AI 优化的瞬态 UI 状态。
 *
 * 只描述这一次改写过程（模式、原文、结果、错误、所属 run），不进入消息与上下文：
 * 采用结果时才通过 `input` + `inputSyncVersion` 写回输入框，撤销时把 [source] 写回。
 * 面板状态属于界面态，不写回会话存储，切换会话后回到 [InputOptimizePhase.Idle]。
 */
@Immutable
internal data class InputOptimizeUiState(
    val phase: InputOptimizePhase = InputOptimizePhase.Idle,
    val mode: InputOptimizeMode = InputOptimizeMode.DEFAULT,
    /** 发起优化时的原始草稿；重试与撤销都以它为基准。 */
    val source: String = "",
    /** 模型输出（[InputOptimizePhase.Running] 阶段是流式增量，[InputOptimizePhase.Ready] 后是最终结果）。 */
    val result: String = "",
    val errorMessage: String = "",
    /** 当前这次调用的标识；回调按它校验，避免覆盖已切换会话或已重启的面板。 */
    val runId: String = "",
) {
    val isVisible: Boolean get() = phase != InputOptimizePhase.Idle

    val isRunning: Boolean get() = phase == InputOptimizePhase.Running

    val canApply: Boolean get() = phase == InputOptimizePhase.Ready && result.isNotBlank()

    val canUndo: Boolean get() = phase == InputOptimizePhase.Applied && source.isNotBlank()
}
