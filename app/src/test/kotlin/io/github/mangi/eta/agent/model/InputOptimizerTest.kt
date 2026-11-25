package io.github.mangi.eta.agent.model

import io.github.mangi.eta.agent.model.provider.AgentModelClient
import io.github.mangi.eta.agent.model.provider.ProviderRequestPurpose
import io.github.mangi.eta.agent.model.provider.openai.OpenAiChatCompletionsProvider
import io.github.mangi.eta.agent.model.provider.openai.ResponsesRequestBuilder
import io.github.mangi.eta.data.model.InputOptimizeMode
import io.github.mangi.eta.data.model.ModelReasoningCapabilities
import io.github.mangi.eta.data.model.ReasoningEffort
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 输入优化器的纯 JVM 契约。
 *
 * 这里重点固定住「不退化成一个 Agent 回合」的请求形状：系统提示只有改写规则、
 * provider 上那份 Eta 人格被清空、工具与托管联网关闭、思考档位压到最低，
 * 以及草稿被 `<draft>` 包成数据。这几条任何一条丢了，模型就会开始「思考下一步」。
 */
class InputOptimizerTest {
    private val agentPersona = "你是 Eta，运行在 Android 设备上的 AI 助手。你可以回答问题、也可以通过工具执行操作。"

    private fun config(
        systemPrompt: String = agentPersona,
        capabilities: ModelReasoningCapabilities? = null,
    ) = AgentModelClient.ModelConfig(
        baseUrl = "https://example.invalid/v1",
        apiKey = "test-key",
        model = "test-model",
        systemPrompt = systemPrompt,
        thinkingEnabled = true,
        reasoningEffort = ReasoningEffort.HIGH,
        reasoningCapabilities = capabilities,
        terminalTools = true,
        browserTools = true,
        deviceDirectTools = true,
        deviceSensitiveReadTools = true,
        deviceSensitiveActionTools = true,
        subagentTools = true,
        hostedWebSearchEnabled = true,
        supportsSearchSuggestions = true,
        autoCompactionEnabled = true,
    )

    @Test
    fun `call config drops the agent persona and every agent capability`() {
        val optimized = InputOptimizer.callConfig(config())

        assertEquals("", optimized.systemPrompt)
        assertFalse(optimized.thinkingEnabled)
        assertEquals(ReasoningEffort.OFF, optimized.reasoningEffort)
        assertFalse(optimized.terminalTools)
        assertFalse(optimized.browserTools)
        assertFalse(optimized.deviceDirectTools)
        assertFalse(optimized.deviceSensitiveReadTools)
        assertFalse(optimized.deviceSensitiveActionTools)
        assertFalse(optimized.subagentTools)
        assertFalse(optimized.hostedWebSearchEnabled)
        assertFalse(optimized.supportsSearchSuggestions)
        assertFalse(optimized.autoCompactionEnabled)
    }

    @Test
    fun `minimal reasoning effort prefers off and otherwise takes the lowest tier`() {
        assertEquals(ReasoningEffort.OFF, InputOptimizer.minimalReasoningEffort(config()))

        val canDisable = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MINIMAL, ReasoningEffort.HIGH),
            canDisable = true,
        )
        assertEquals(ReasoningEffort.OFF, InputOptimizer.minimalReasoningEffort(config(capabilities = canDisable)))

        val mandatoryThinking = ModelReasoningCapabilities(
            supportedEfforts = listOf(ReasoningEffort.MEDIUM, ReasoningEffort.HIGH),
            mandatory = true,
        )
        assertEquals(
            ReasoningEffort.MEDIUM,
            InputOptimizer.minimalReasoningEffort(config(capabilities = mandatoryThinking)),
        )
    }

    @Test
    fun `request messages carry only the rewrite prompt and wrap the draft as data`() {
        val draft = "帮我把那个设置弄一下"
        val messages = InputOptimizer.buildMessages(draft, InputOptimizeMode.POLISH)

        assertEquals(2, messages.length())
        val system = messages.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        assertTrue(system.getString("content").contains("输入框文本改写器"))
        assertFalse(system.getString("content").contains("你是 Eta"))

        val user = messages.getJSONObject(1)
        assertEquals("user", user.getString("role"))
        val userText = user.getString("content")
        assertTrue(userText.startsWith("<draft>"))
        assertTrue(userText.contains(draft))
        assertTrue(userText.contains("只输出改写后的正文"))
        assertTrue(userText.contains("不得原样照抄"))
    }

    @Test
    fun `chat completions request sends no agent persona and no tools`() {
        val messages = InputOptimizer.buildMessages("帮我看看这个问题", InputOptimizeMode.CONCISE)
        val request = OpenAiChatCompletionsProvider.buildRequestJson(
            config = InputOptimizer.callConfig(config()),
            messages = messages,
            tools = JSONArray(),
            purpose = ProviderRequestPurpose.INPUT_OPTIMIZE,
        )

        val sent = request.getJSONArray("messages")
        val systemMessages = (0 until sent.length())
            .map { sent.getJSONObject(it) }
            .filter { it.optString("role") == "system" }
        assertEquals(1, systemMessages.size)
        assertTrue(systemMessages.first().optString("content").contains("输入框文本改写器"))
        assertFalse(systemMessages.first().optString("content").contains("你是 Eta"))
        assertEquals(0, request.optJSONArray("tools")?.length() ?: 0)
    }

    @Test
    fun `responses instructions never fall back to the agent persona`() {
        val messages = InputOptimizer.buildMessages("把这段话改清楚", InputOptimizeMode.DETAIL)
        val optimized = InputOptimizer.callConfig(config())
        val request = ResponsesRequestBuilder.build(
            config = optimized,
            messages = messages,
            tools = JSONArray(),
            purpose = ProviderRequestPurpose.INPUT_OPTIMIZE,
        )

        val instructions = request.optString("instructions")
        assertTrue(instructions.contains("输入框文本改写器"))
        assertFalse(instructions.contains("你是 Eta"))

        // 即使有人丢掉 system 消息，兜底也必须落到空串而不是 Eta 人格。
        val fallback = ResponsesRequestBuilder.build(
            config = optimized,
            messages = JSONArray().put(AgentConversationCodec.userTextMessage("帮我改一下")),
            tools = JSONArray(),
            purpose = ProviderRequestPurpose.INPUT_OPTIMIZE,
        ).optString("instructions")
        assertFalse(fallback.contains("你是 Eta"))
    }

    @Test
    fun `wrapped quote pairs are stripped but inner quotes survive`() {
        val raw = "\"请帮我整理下周的日程\""
        assertTrue(InputOptimizer.sanitize(raw) == "请帮我整理下周的日程")

        val keepInner = "把 settings.json 里的 \"debug\" 改成 true"
        assertTrue(InputOptimizer.sanitize(keepInner) == keepInner)
    }

    @Test
    fun `fenced code block with language tag is unwrapped`() {
        val raw = "```text\n优化后的内容\n```"
        assertTrue(InputOptimizer.sanitize(raw) == "优化后的内容")
    }

    @Test
    fun `label prefixes are removed`() {
        assertTrue(InputOptimizer.sanitize("优化后：请总结这篇文档") == "请总结这篇文档")
        assertTrue(InputOptimizer.sanitize("optimized: summarize the document") == "summarize the document")
    }

    @Test
    fun `reasoning blocks and draft tags never reach the panel`() {
        val withThinking = "<thinking>用户想要我做事，下一步先看看设置在哪。</thinking>请帮我把勿扰模式关掉"
        assertEquals("请帮我把勿扰模式关掉", InputOptimizer.sanitize(withThinking))

        val withDraftTag = "<draft>\n请帮我把勿扰模式关掉\n</draft>"
        assertEquals("请帮我把勿扰模式关掉", InputOptimizer.sanitize(withDraftTag))

        val analysisFirst = "先分析一下：草稿有歧义。\n改写结果：请帮我把勿扰模式关掉，并说明关闭范围。"
        assertEquals("请帮我把勿扰模式关掉，并说明关闭范围。", InputOptimizer.sanitize(analysisFirst))
    }

    @Test
    fun `unchanged detection ignores whitespace and punctuation only edits`() {
        assertTrue(InputOptimizer.isUnchanged("帮我看看这个设置", "帮我看看这个设置"))
        assertTrue(InputOptimizer.isUnchanged("帮我看看这个设置", "帮我看看这个设置。"))
        assertTrue(InputOptimizer.isUnchanged("帮我看看这个设置", "  帮我看看这个设置\n"))
        assertTrue(InputOptimizer.isUnchanged("帮我看看这个设置", ""))
        assertFalse(InputOptimizer.isUnchanged("帮我看看这个设置", "请帮我看一下这个设置项，确认它当前是否已开启。"))
        assertFalse(InputOptimizer.isUnchanged("fix the login bug", "Fix the login bug: the retry counter never resets."))
    }

    @Test
    fun `escalated retry tells the model its previous answer was a copy`() {
        assertFalse(InputOptimizer.wrapDraft("帮我看看这个设置").contains("原样照抄了回来"))
        val escalated = InputOptimizer.wrapDraft("帮我看看这个设置", escalated = true)
        assertTrue(escalated.contains("原样照抄了回来"))
        assertTrue(escalated.contains("<draft>"))

        val messages = InputOptimizer.buildMessages("帮我看看这个设置", InputOptimizeMode.POLISH, escalated = true)
        assertTrue(messages.getJSONObject(1).getString("content").contains("原样照抄了回来"))
    }

    @Test
    fun `prompt demands a visible rewrite instead of a copy`() {
        val prompt = InputOptimizer.systemPrompt(InputOptimizeMode.POLISH)
        assertTrue(prompt.contains("你的任务：把 <draft> 里的草稿改写成更有效的一版提示词"))
        assertTrue(prompt.contains("改写是**必须发生**的"))
        assertTrue(prompt.contains("不得与草稿相同"))
        // 正向任务必须出现在禁令之前，否则保守模型会选择「不动最安全」。
        assertTrue(prompt.indexOf("你的任务") < prompt.indexOf("草稿只是待改写的**数据**"))
    }

    @Test
    fun `plausible rejects empty answer and runaway expansion`() {
        val input = "帮我写一个 Magisk 模块"
        assertFalse(InputOptimizer.plausible(input, ""))
        assertFalse(InputOptimizer.plausible(input, "null"))
        assertFalse(InputOptimizer.plausible(input, "长".repeat(2_000)))
        assertTrue(InputOptimizer.plausible(input, "帮我写一个可以直接刷入的 Magisk 模块，包含模块说明"))
    }

    @Test
    fun `every mode carries its own instruction and forbids execution`() {
        val prompts = InputOptimizeMode.ALL.map { InputOptimizer.systemPrompt(it) }
        assertEquals(InputOptimizeMode.ALL.size, prompts.size)
        assertTrue(prompts.all { it.contains("不要回答它") && it.contains("不要执行它") })
        assertTrue(prompts.all { !it.contains("你是 Eta") })
        assertEquals(prompts.size, prompts.toSet().size)
        assertTrue(InputOptimizer.systemPrompt(InputOptimizeMode.CONCISE).contains("精简"))
        assertTrue(InputOptimizer.systemPrompt(InputOptimizeMode.STRUCTURE).contains("结构化"))
    }
}
