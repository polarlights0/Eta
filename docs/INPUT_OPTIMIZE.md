# 输入框 AI 优化（Input Optimize）

> 功能形态参考 [dsh-prompt-library](https://github.com/master1Sun/dsh-prompt-library) 的 composer「AI 润色」按钮：
> 在输入框工具栏注入一个入口，用当前草稿调用模型做一次改写，结果先在输入框上方预览，
> 用户确认后才写回输入框。Eta 侧按自身结构（Compose + `AgentAppState` + Provider 旁路调用）重新实现。

## 1. 功能入口

| 位置 | 形态 | 说明 |
| --- | --- | --- |
| 对话输入框底部工具栏 | 四角星图标按钮（`Icons.Rounded.AutoAwesome`） | 紧挨「附件」按钮右侧、思考强度胶囊左侧；草稿非空时可点，优化中图标持续旋转，面板展开期间保持主题色高亮 |

- 入口由 `AgentInputOptimizeButton`（`ui/components/AgentInputOptimizePanel.kt`）实现，挂载点在 `AgentChatInputBar` 的工具栏 `Row` 里。
- 编辑既有消息时不显示该按钮（输入框此时承载的是那条消息正文，不允许被优化结果覆盖）。
- 不做独立设置页：默认即为可用，缺少对话模型时面板直接给出配置引导，避免多一层开关。

## 2. 展示位置

```
┌──────────────────────────────────────┐
│            对话消息流（聊天舞台）              │
├──────────────────────────────────────┤
│  ✦ AI 优化输入     42 → 96 字     ✕  │   ← 输入优化面板（本文档新增）
│  [润色] [精简] [补全] [结构化]           │      与「排队消息」「待发送附件」
│  优化结果正文（限高 148dp 内滚动）         │      共用输入框上方的同一块悬浮区域
│              复制  重新生成  采用        │
├──────────────────────────────────────┤
│  ＋  ✦  [思考档位]      [用量] [语音] ➤  │   ← 输入框（✦ = 功能入口）
└──────────────────────────────────────┘
```

- 面板是 `AgentChatInputBar` 的 `Column` 中、输入容器（`Box`/`glassPanel` 胶囊）之前的 `AnimatedVisibility` 区块，从底部推入展开，
  与 `AgentChatQueueStrip`、`PendingImageStrip` 的顺序一致：排队消息 → 文件引用 → 图片 → 编辑提示 → **输入优化面板** → 输入框。
- 面板只占输入框上方的悬浮层，不改变输入框自身位置，也不遮挡消息流；结果正文内部滚动，避免长草稿把输入框顶出屏幕。
- 主题跟随 Miuix 色板（`surfaceContainer` + `squircleSurface`/`squircleBorder`），模式胶囊复用 `EtaChoiceChip`，MIUIX / COUI 双风格一致。

## 3. 交互方式

1. 用户在输入框写好草稿，点工具栏四角星（或在面板内切换模式）。
2. 面板立即展开为「正在优化…」，模型边生成边把增量文字实时推进面板（长草稿不必干等）。
3. 完成后进入结果态：顶部显示「原 N 字 → 优化后 M 字」与模式胶囊，正文是最终结果，底部为「复制 / 重新生成 / 采用」。
4. 点「采用」：结果通过 `input` + `inputSyncVersion` 写回输入框并聚焦，面板收起为「已用优化结果替换输入框 · 撤销 · 关闭」，
   用户可一键撤销回原始草稿；点「重新生成」用同一份草稿重跑；点「✕」随时收起（在途请求被取消，输入框内容不变）。
5. 失败（未配置模型 / 超时 / 返回内容异常 / 草稿过长）不会动输入框，只在面板里给出原因与「重试」。

关键约束：**优化永远不改写输入框，除非用户显式点「采用」**；所有中间态都可回退。

## 4. 实现要点

### 4.1 旁路一次性调用（不进 Agent Loop、不进上下文）

`agent/model/InputOptimizer.kt` 与 `SpeechTranscriptRefiner`、`ConversationTitleGenerator` 同构：

- 复用当前对话模型（`RuntimeConfigRepository.currentRuntimeConfig()`），但强制压到**最低可用思考档位**，避免深度思考拖慢一次改写；
- 请求 `purpose = ProviderRequestPurpose.INPUT_OPTIMIZE`（新增枚举值，`allowsTools = false`），不带工具；
  模型一旦返回工具调用视为协议异常；
- `withTimeout(30s)`，超时/取消时通过 `AgentRunController.cancel()` 中止底层 HTTP；
- 输出清洗 `sanitize()`：去掉模型偶发的 ``` 围栏、`优化后：` 之类前缀、整体包裹的引号，但保留正文里合法的代码块与引号；
- `plausible()` 兜底：空输出、`null`、长度剧烈膨胀（> 输入 6 倍且 > 600 字）判为越权，报错让用户重试。

模式只改提示词，不改调用链：`POLISH`（润色）/ `CONCISE`（精简）/ `DETAIL`（补全）/ `STRUCTURE`（结构化）。
所有模式共享一段铁律：草稿只是数据、不回答问题、不调用工具、同语言输出、
**原样保留代码块 / 命令 / 路径 / URL / 变量名 / 占位符**、不虚构事实、只输出正文。

### 4.1.1 为什么它不会退化成「Agent 思考下一步」

这是一次**文本改写**，不是 Agent 的一轮对话。实测中最容易踩的坑是模型把草稿读成「用户给我派了个活」，
于是输出「先看看设置在哪、接下来我要……」这类计划而不是改写结果。请求侧因此做了四道隔离
（`InputOptimizer.callConfig` / `buildMessages` / `wrapDraft` / `sanitize`，均有单测锁定）：

| 隔离 | 做法 | 不这么做会怎样 |
| --- | --- | --- |
| 人格隔离 | `callConfig` 把 `systemPrompt` 清空 | 服务商设置里那份「你是 Eta，运行在 Android 上的 AI 助手」会经 Responses 的 `instructions` 兜底等路径进入请求，模型按 Agent 身份思考 |
| 能力隔离 | 关闭 terminal / browser / device / subagent 工具、托管联网、自动压缩、搜索建议 | 工具目录会让模型进入「可执行任务」的心态 |
| 思考隔离 | 思考档位压到最低：能关就 `OFF`，模型强制思考时取最低可用档；`capabilities` 为空的自定义服务商也强制 `OFF` | 用户给对话选的 High/Max 会带进来，模型先长篇推理（Kimi/百炼的 `thinking.keep=all` 还会把推理一起返回） |
| 数据隔离 | 草稿包进 `<draft>…</draft>`，明确「只改写它的措辞、不要回答或执行、不要输出分析/计划/步骤」，并以「只输出改写后的正文，第一行就是正文：」收尾 | 直接当普通用户消息发出去时，agentic 模型会把它当成任务开始执行 |

### 4.1.2 「原样照抄」的防护

提示词写得越强调「只输出正文、草稿只是数据」，保守模型就越容易选择「不动最安全」，直接把草稿抄回来
（实测：输出与输入一字不差或只补了个句号）。因此做两层防护：

- **提示词层**：正向改写任务放在最前（`你的任务：把 <draft> 里的草稿改写成更有效的一版提示词`），
  并显式声明「改写是**必须发生**的：输出不得与草稿相同」，禁令改为「只约束是否执行草稿，不构成不改写的理由」。
- **代码层**：`isUnchanged()`（忽略空白与标点比较）检测"没改动"。第一次原样返回时，用加强指令
  `ESCALATION_HINT`（"上一次你把 <draft> 原样照抄了回来……"）重试一次；仍然照抄则报
  `input_optimize_error_unchanged`，绝不把原文当优化结果回填输入框。

输出侧再兜一层：`sanitize` 剥掉 `<thinking>` / `<analysis>` 等思考块、``` 围栏、「改写结果：」类标签与包裹引号。
如果模型**只**返回了思考而没有正文，面板给出专用错误「模型只返回了思考过程，请重试或换一个模型」，
而不是把推理文字当成结果回填输入框。


### 4.2 状态与数据流

```
AgentChatInputBar (本地 textFieldState)
   │ 点击 ✦：onOptimizeInput(draft, mode)          ← 草稿只在点击时随 action 上行
   ▼
AgentHomeAction.OptimizeInput / Retry / Apply / Undo / Dismiss / ModeChanged
   ▼
AgentAppState.optimizeCurrentInput / applyInputOptimize / ...
   │  写入 AgentChatUiState.inputOptimize = InputOptimizeUiState(phase, mode, source, result, runId)
   │  采用时：homeState.copy(input = result, inputSyncVersion + 1, focusInputOnSync = true)
   ▼
AgentChatInputBar 的 LaunchedEffect(inputSyncVersion) 把文本灌回输入框并聚焦
```

- 输入文字**不逐字提到全局状态**（Eta 现有约定：避免每个字符把消息流 / Markdown 一起重组），
  所以优化入口把当前草稿当参数传上来，而不是读 `homeState.input`。
- 回写输入框复用既有 `input` + `inputSyncVersion` + `focusInputOnSync` 机制（与 `acceptSharedContent`、撤回排队消息一致）。
- `InputOptimizeUiState` 是**界面瞬态**：不写会话存储、不进上下文；切换会话后自动回到 `Idle`。
- 每次调用带 `runId`，流式增量与最终结果都按 `runId` 校验，防止旧请求把结果写进新面板或已切换的会话；
  重复点击、采用、撤销、关闭都会取消在途 job。
- 流式增量回调来自 Provider 的 IO 线程，回 `scope.launch`（`viewModelScope` = Main）再更新 Compose 状态。

### 4.3 文件清单

| 文件 | 变更 |
| --- | --- |
| `data/model/InputOptimizeMode.kt` | 新增：四种优化模式枚举 |
| `agent/model/InputOptimizer.kt` | 新增：旁路调用 + 四套提示词 + 输出清洗/兜底 + `InputOptimizeFailure` |
| `ui/model/InputOptimizeUiState.kt` | 新增：面板阶段（Idle/Running/Ready/Failed/Applied）与派生属性 |
| `ui/components/AgentInputOptimizePanel.kt` | 新增：工具栏入口按钮 + 悬浮优化面板 |
| `ui/components/AgentChatInputBar.kt` | 入口按钮 + 面板挂载 + 6 个回调参数 |
| `ui/components/AgentChatBody.kt` | 状态与回调透传（Body → Scaffold → BottomBar → InputBar） |
| `ui/screens/home/AgentHomeScreen.kt` | `state.inputOptimize` 与 `AgentHomeAction` 接线 |
| `ui/app/AgentAppState.kt` | `optimizeCurrentInput` / `applyInputOptimize` / `undoInputOptimize` / `retryInputOptimize` / `dismissInputOptimize` / `changeInputOptimizeMode` |
| `ui/app/AgentAppRoot.kt` | 新增 6 个 action 的分发 |
| `ui/model/AgentUiActions.kt` | 新增 6 个 `AgentHomeAction` |
| `ui/model/AgentChatUiState.kt` | 新增 `inputOptimize` 字段（默认 Idle） |
| `agent/model/AgentProvider.kt` | `ProviderRequestPurpose` 新增 `INPUT_OPTIMIZE` |
| `res/values*/strings.xml` | 19 条文案 × 英 / 简中 / 繁中 |
| `test/.../InputOptimizerTest.kt` | 单测：清洗、越权兜底、模式提示词 |

### 4.4 后续可扩展点

- **自定义指令**：加一个模式，附带一行用户输入的自定义要求（面板已按 `InputOptimizeMode.ALL` 遍历，新增枚举即可出现在工具栏）。
- **独立轻量模型**：仿 `ConversationTitleSettings` 增加 `InputOptimizeSettings`，让改写用小模型跑（`loadConfig` 已是可注入的挂起点）。
- **系统提示词预设 / 提示词库**：把 dsh-prompt-library 的「可复用片段库」接到面板的模式行右侧，作为额外的 `${片段}` 注入。
- **持久化最近一次结果**：当前不落盘是有意为之，避免优化中间态污染会话数据。

## 5. 验证建议

- 单元测试：`./gradlew :app:testDebugUnitTest --tests "*InputOptimizerTest"`（纯 JVM，覆盖清洗与兜底）。
- 手工验证路径：
  1. 空草稿时入口置灰 → 输入文字后可点；
  2. 四个模式分别优化同一段中文命令草稿，确认代码块 / 路径 / URL 未被改写；
  3. 运行中点「✕」应立即停止且输入框内容不变；
  4. 「采用」后输入框替换并聚焦，末行「撤销」可回到原文；
  5. 未配置模型时点入口应出现配置引导，而非崩溃或静默。
