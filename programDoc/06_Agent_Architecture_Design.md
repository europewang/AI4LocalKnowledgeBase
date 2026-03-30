# Agent 架构设计说明（意图识别 + 工具调用 + 工作流 + 流式输出）

## 1. 设计目标

在现有 RAGFlow + Java Backend + Frontend 的基础上，构建可生产化的 Agent 系统，满足：

1. 多轮上下文联动（会话不丢失）
2. RAG 命中内容自动填充 Skill 参数
3. 前端人工审查并可编辑工具参数
4. 点击确认后立即调用工具并继续会话
5. 全链路可鉴权、可审计、可治理

## 2. 后端模块边界

### 2.1 rag 模块
职责：仅负责 RAG 相关基础能力（向量检索、召回、重排、上下文片段返回），不承担流程编排。

### 2.2 knowledge 模块
职责：知识库/文档/Chunk 管理，结合权限做“可见性过滤”。

### 2.3 engine 模块
职责：统一编排核心链路，包括查询重写、意图识别、检索拼装、会话状态机、工具调用暂停恢复，并调度 `rag`、`skill` 形成最终结果。

### 2.4 user 模块
职责：统一承载登录鉴权、JWT、RBAC（`super_admin/admin/user`）、用户管理、资源授权、审计查询与会话记忆持久化接口。

### 2.5 admin 模块
职责：已并入 `user` 模块，不再保留独立后端实现。

### 2.6 skill 模块
职责：工具注册、参数 Schema、执行器与调用结果标准化。

## 3. 核心时序（含人工审批）

1. 用户发送问题到 `/api/v1/agent/chat/stream`
2. `engine` 先进行查询重写与意图识别，再按需调度 `rag` 完成检索与上下文拼装
3. 若命中可调用 Skill，则产出 `tool_draft` 事件（包含参数 Schema 与参数草稿）
   - 当工具参数部分缺失且可由检索辅助生成时，`engine` 会在后台自动调用 `rag` 生成相应参数草稿，再一并返回至前端
4. 前端展示审批面板，人工可改参数（基于 Schema 的表单渲染）
5. 前端提交 `/api/v1/agent/tool/approve`
6. 审批通过后，`engine` 执行对应 `skill`（如需补充检索则再次调用 `rag`），执行结果回写 `engine`
7. `engine` 继续后续对话并以 SSE 流式输出最终结果

### 3.1 SSE 事件类型（标准化）
- `analysis_plan`：输出分析计划快照（深度思考、问题类型、步骤、摘要）
- `analysis_step`：执行步骤流式更新（等待审批/执行中/已完成）
- `analysis_summary`：执行完成后的计划汇总（step_count/final_answer 等）
- `message`：结构化回答消息（可附引用）
- `token`：模型流式增量文本
- `tool_draft`：工具草稿（参数 Schema、参数草稿、来源标识如 `param_source: rag|llm|manual`）
- `tool_result`：工具执行结果摘要（审批后）
- `clarify`：低置信度澄清引导
- `done`：一次对话流式完成
- `error`：异常事件（包含错误码与简要说明）

### 3.2 记忆与存储分层（Memory）
- **短期记忆（Working Memory）**：保留最近 N 轮消息与关键工具结果，用于当前轮推理与上下文拼装。建议存于 Redis（`conv:{conversation_id}:wm`），TTL=7d，随会话活跃滚动更新。
- **长期记忆（Long-term Memory）**：将长对话按轮次“蒸馏”为摘要/决定/事实/工具结果快照，落库到 `t_memory` 与 `t_tool_result`，支持检索与审计。
- **检索策略**：
  - 基于最近性优先（recency）+ 重要性分（salience）混合排序，重要性>阈值的消息进入长期记忆；
  - 召回时：优先加载短期窗口，其次按“会话ID + 主题”从长期记忆向量检索补充摘要/结论。
- **写入与治理**：
  - 每轮结束由 `engine` 触发“記憶蒸馏”任务（异步），生成 `summary` 与 `decisions` 条目；
  - 工具执行完成即刻写入 `t_tool_result`（包含入参摘要、关键出参与资源引用），并在短期记忆中缓存最近 K 条；
  - 管理端支持对长期记忆的“固定/解锁”（pin/unpin）与可见性控制（按用户与会话）。

### 3.3 Backend 代码备注索引（维护重点）
- `BackendApplication.java`：后端启动入口，负责组件装配与配置绑定。
- `engine/config/LlmConfigProperties.java`：LLM 网关配置映射（`ai4kb.llm.*`）。
- `config/WebClientConfig.java`：WebClient 全局构建器与内存上限配置。
- `user/client/RagFlowClient.java`：RAGFlow 接口封装（知识库/文档/会话/流式问答）。
- `engine/service/EngineOrchestrator.java`：主编排入口，包含路由决策、计划生成、SSE 事件拼装与审批恢复链路。
- `engine/service/ConversationService.java`：会话状态与工具草稿的 Redis 持久化（7 天 TTL）。
- `engine/service/LlmClient.java`：LLM 网关调用封装，兼容 tool_call 非流式与普通对话流式返回。
- `engine/service/RouteSampleService.java`：路由样本落库与来源枚举查询。
- `engine/controller/AgentController.java`：`/api/v1/agent/*` 接口边界，承载对话、审批、工具文件上传下载。
- `user/controller/UserAdminController.java`：管理端知识库、文档、用户权限、路由样本查询与超级管理员总览入口。
- `user/controller/UserConversationController.java`：用户会话新建、会话列表、消息持久化与历史读取入口。
- `knowledge/controller/DocumentController.java`：文档与图片内容透传下载接口。
- `engine/model/ConversationState.java`：会话态、计划快照、计划步骤的核心模型定义。
- `engine/model/Message.java`：对话消息统一模型，兼容 tool_call 结构。
- `engine/model/openai/OpenAiChatRequest.java`：OpenAI 兼容请求模型（消息、流式开关、工具定义）。
- `engine/model/openai/OpenAiChatResponse.java`：OpenAI 兼容响应模型（流式 delta 与非流式 message）。
- `engine/entity/RouteSample.java`：路由样本持久化实体（本地路由与 Planner 决策快照）。
- `engine/mapper/MemoryMapper.java`：记忆数据访问接口。
- `engine/mapper/RouteSampleMapper.java`：路由样本数据访问接口。
- `engine/mapper/ToolResultMapper.java`：工具结果数据访问接口。
- `user/entity/User.java`：用户实体，承载账号与角色基础信息。
- `user/entity/Permission.java`：用户资源授权实体（DATASET/SKILL）。
- `user/entity/UserConversation.java`：用户会话主表实体（标题、创建/更新时间）。
- `user/entity/UserConversationMessage.java`：用户会话消息实体（角色、内容、记录时间）。
- `user/mapper/UserMapper.java`：用户数据访问接口。
- `user/mapper/PermissionMapper.java`：权限数据访问接口。
- `user/mapper/UserConversationMapper.java`：用户会话数据访问接口。
- `user/mapper/UserConversationMessageMapper.java`：用户会话消息数据访问接口。
- `skill/service/SkillRegistryService.java`：工具目录与权限过滤、关键词匹配、草稿载荷组装。
- `skill/service/SkillExecutionService.java`：工具执行请求编排、结果标准化、输出文件注册。
- `skill/controller/SkillProtocolAdminController.java`：动态技能管理入口（注册、列表、下线、审计查询）。
- `skill/service/DynamicSkillRegistryService.java`：动态技能注册表服务（在线状态、版本、ToolSpec 映射）。
- `skill/service/DynamicSkillProtocolClient.java`：统一 HTTP 协议调用客户端（invoke 请求封装与结果解析）。
- `skill/service/DynamicSkillAuditService.java`：动态技能调用审计服务（开始/成功/失败落库）。
- `skill/service/ToolFileStorageService.java`：工具输入/输出文件落盘与 fileId 资源映射。
- `skill/config/SkillSchemaMigrationRunner.java`：动态技能注册表与审计表启动期迁移。
- `skill/model/ToolExecutionRequest.java`：工具执行请求载荷模型。
- `skill/model/ToolExecutionResult.java`：工具执行结果与产出文件模型。
- `skill/model/ToolSpec.java`：工具规格定义模型（触发词、参数 Schema、输入输出能力）。
- `skill/impl/cad_text_extractor/CadTextExtractorProtocolController.java`：cad_text_extractor 的协议化 manifest/health/invoke 暴露实现。
- `skill/impl/cad_text_extractor/CadTextExtractorSkillAutoRegisterRunner.java`：cad_text_extractor 启动自注册到动态技能表。
- `skill/executor/impl/SendEmailMockSkillExecutor.java`：邮件工具 mock 执行器（联调用）。
- `rag/processor/ChatProcessor.java`：RAG 对话处理器统一接口。
- `rag/processor/impl/RagDirectProcessor.java`：RAGFlow 直连实现，含数据集权限过滤与流式响应归一化。
- `rag/processor/impl/MockAgentProcessor.java`：Agent 模式占位处理器，返回 mock 流式结果。

## 4. 权限策略

1. 管理员创建知识库：普通用户可见名称与描述，不可读内容，不可改删。
2. 普通用户只能使用被授权的知识库和 Skill。
3. 管理员可创建普通用户并分配资源权限。
4. 超级管理员可执行任意操作，并查看全量审计数据。

## 5. 数据模型摘要

1. `t_user`：账号、密码、姓名、角色
2. `t_permission`：用户到知识库/Skill 的授权关系
3. `t_audit_log`：查询、审批、工具调用、管理动作审计
4. 会话态：`conversation_state`（可先用 Redis，后续可落 MySQL）
5. 工具调用草稿：`tool_call_draft`（参数草稿与审批结果）

## 6. Docker 部署策略

1. 保持单一 compose 文件管理（`deploy/docker-compose-ragflow.yml`）。
2. 业务改造后仅重建 backend：
   ```bash
   sudo docker compose -f deploy/docker-compose-ragflow.yml up -d --build backend
   ```
3. 数据库迁移在 `ragflow-mysql` 容器内执行，迁移后重启 backend。

## 7. 分阶段交付建议

### 阶段 1（流程打通）
* SSE 对话 + `tool_draft` 事件
* 审批通过后恢复执行
* 简单审计日志

### 阶段 2（权限治理）
* 三层角色与资源授权
* 管理员后台接口
* 知识库“可见不可读”规则

### 阶段 3（运营可观测）
* 按用户/技能/知识库统计调用量
* 异常调用告警与审计检索
* 成本与吞吐监控面板

## 8. 智能路由升级路线（Planner 化）

### 8.1 目标
1. 将路由从“关键词优先”升级为“结构化决策优先”。
2. 统一输出 `route/confidence/tool_name/reason`，便于审计与评估。
3. 在不破坏现有 SSE 协议（`tool_draft/message/token/done`）的前提下逐步替换旧逻辑。

### 8.2 当前已落地（2026-03-23）
1. `engine` 先调用 LLM Planner 做结构化路由决策，再进入执行链路。
2. Planner 决策支持三类：`TOOL` / `RAG` / `CHAT`。
3. `TOOL` 路由采用可配置阈值：`ai4kb.router.tool-confidence-threshold`（默认 `0.55`）。
4. 低置信度澄清阈值配置化：`ai4kb.router.clarify-confidence-threshold`（默认 `0.45`）。
5. 对 `TOOL/RAG/CHAT` 且低于澄清阈值的请求，返回 `clarify` 事件，不直接执行工具或检索。
6. 对非法 route、未授权 tool 等场景维持统一回退策略，自动回退到兼容链路（关键词匹配、LLM 工具选择、启发式、RAG 回退）。
7. 增加路由审计日志字段：`route/confidence/tool/reason/threshold/fallback_reason`，并新增 `clarify` 命中日志。
8. SSE 事件在原有 `tool_draft/message/token/done` 基础上新增 `clarify`，前端已接入补充意图引导卡片。
9. 新增双通道竞速阈值：`ai4kb.router.race-confidence-threshold`（默认 `0.75`），用于触发 `RAG/CHAT` 并行裁决。
10. 在 `RAG/CHAT` 且 `clarify-threshold <= confidence < race-threshold` 时，触发并行竞速并按可解释性/稳定性规则裁决输出。
11. 新增组合路由开关：`ai4kb.router.local-router-enabled`（默认 `false`），支持本地轻量 Router 先筛，兼容灰度上线。
12. 新增本地直出阈值：`ai4kb.router.local-router-auto-threshold`（默认 `0.90`），低于该阈值或命中 `TOOL` 时进入 Planner 复核。
13. 新增 `route_sample` 审计采样日志，记录 `source/chosen/local/planner` 四元信息，支持阶段D样本沉淀。
14. 新增 `t_route_sample` 持久化落库链路（Entity/Mapper/Service），在路由决策后异步写入样本，支持后续训练数据回放。
15. 新增审计查询接口 `GET /api/admin/route-samples`，支持按 `limit/userId/source` 拉取最新路由样本。
16. 审计查询接口权限收敛为 `super_admin`，`admin` 角色访问会被拒绝。
17. 新增审计来源枚举接口 `GET /api/admin/route-samples/sources`，按数据库样本动态返回 `source` 列表，避免前端硬编码来源值。
18. 前端管理端将“路由样本”重命名为“审计查询”，并限制为 `super_admin` 可见。
19. 新增超级管理员聚合视图接口 `GET /api/admin/super/ownership-overview`，返回管理员名下知识库、文档数与授权明细。
20. 前端新增“管理员总览”页签，展示各管理员创建的 RAG 文档资产与权限分配情况。
21. 本地 Router 判定顺序优化为“闲聊/知识问句优先于工具关键词匹配”，降低“什么是指标校核”这类解释型问句误触发工具的概率。
22. 前端聊天输入区新增“路由示例问题”快捷按钮，便于快速验证 `TOOL/RAG` 分流与澄清行为。

### 8.3 分阶段执行
1. 阶段 A（结构化路由稳定化）
   - 已完成：`TOOL` 置信度阈值配置化（当前默认 0.55）。
   - 已完成：Planner 输出合法性校验（route 枚举归一化、tool_name 白名单约束）。
   - 已完成：路由结果打点（query、route、confidence、threshold、tool、fallback_reason）。
2. 阶段 B（低置信度澄清）
   - 已完成：对 `confidence` 低于 `clarify-confidence-threshold` 的请求进入“澄清问题”分支，而非直接执行工具或检索。
   - 已完成：前端接入 `clarify` 事件展示与建议补充项，用户补充后再次发起规划。
3. 阶段 C（双通道竞速）
   - 已完成：对 `RAG/CHAT` 模糊场景并行触发检索与通用推理。
   - 已完成：基于可解释性与稳定性规则做结果裁决，输出最优答案。
4. 阶段 D（可学习路由）
   - 已完成（基础版）：Router 与 LLM Planner 组合入口，Router 先筛选，Planner 复核高风险样本。
   - 已完成（基础版）：路由样本沉淀日志 `route_sample` 与 `t_route_sample` 落库。
   - 待继续：累积线上样本后训练轻量 Router（分类器或小模型）并替换规则 Router。

### 8.4 验收指标
1. 路由准确率：`TOOL/RAG/CHAT` 标注集准确率持续提升。
2. 工具误触发率：不该调工具却触发的比例下降。
3. 工具漏触发率：应调工具却未触发的比例下降。
4. 回答可用率：用户首轮无需追问即可继续任务的比例提升。
5. 时延：在准确率提升前提下控制首包延迟增长。

### 8.5 Backend 维护参数速查（EngineOrchestrator）
1. `ai4kb.router.tool-confidence-threshold`（默认 `0.55`）：
   - 含义：路由到 TOOL 的最低可信度。
   - 调优建议：提高可降低误触发工具；降低可提升工具召回。
2. `ai4kb.router.clarify-confidence-threshold`（默认 `0.45`）：
   - 含义：低于该值触发 `clarify` 事件。
   - 调优建议：提高会更保守、更多追问；降低会减少澄清轮次。
3. `ai4kb.router.race-confidence-threshold`（默认 `0.75`）：
   - 含义：`RAG/CHAT` 在模糊区间触发并行竞速裁决。
   - 调优建议：提高可减少竞速开销；降低可提升模糊场景命中率。
4. `ai4kb.router.local-router-enabled`（默认 `false`）：
   - 含义：是否启用“本地 Router 先筛 + Planner 复核”混合路由。
5. `ai4kb.router.local-router-auto-threshold`（默认 `0.90`）：
   - 含义：本地 Router 直出阈值。
   - 调优建议：过低会放大本地误判，过高会增加 Planner 调用成本。
