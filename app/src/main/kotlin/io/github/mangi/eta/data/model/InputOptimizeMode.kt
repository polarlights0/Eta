package io.github.mangi.eta.data.model

/**
 * 输入框 AI 优化的目标模式。
 *
 * 模式只决定系统提示词里的改写约束（见 `agent.model.InputOptimizer`），调用链、超时、
 * 结果校验与应用方式完全一致；因此新增一格模式只需补一条枚举与一份提示词。
 *
 * 与 [ReasoningEffort] 保持一致保持公开可见性：`AgentHomeAction` 是公开 sealed 接口，
 * 其 data class 的构造参数类型不能是 internal（否则 Kotlin 报 public 暴露 internal 类型）。
 */
enum class InputOptimizeMode {
    /** 润色：保留原意与信息量，修正错别字、语序与表达，让指令更明确。 */
    POLISH,

    /** 精简：去掉啰嗦与重复，完整保留目标、约束与关键信息。 */
    CONCISE,

    /** 补全：补上背景、目标、约束与期望输出格式等让模型一次做对的细节，不臆造事实。 */
    DETAIL,

    /** 结构化：整理成分点 / 编号 / 小标题等清晰结构，不丢原文要求。 */
    STRUCTURE,
    ;

    companion object {
        /** 默认模式：用户第一次点开优化按钮时使用的档位。 */
        val DEFAULT: InputOptimizeMode = POLISH

        /** 面板上按固定顺序展示的全部模式。 */
        val ALL: List<InputOptimizeMode> = entries.toList()
    }
}
