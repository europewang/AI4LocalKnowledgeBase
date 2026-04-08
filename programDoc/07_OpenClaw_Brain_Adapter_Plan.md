# OpenClaw 大脑适配实施方案（仅替换大脑，保留现有治理体系）

## 1. 目标边界

本方案只做一件事：将现有后端“大脑能力”改造成可切换架构，支持在以下两种模式间切换：

1. 自研大脑（当前链路）
2. OpenClaw 大脑（新增适配）

以下能力明确不改、直接复用现有实现：

1. 登录鉴权（JWT）
2. RBAC（super_admin/admin/user）
3. 现有租户隔离与资源授权模型
4. 单人记忆与会话持久化
5. 审计与管理端查询入口

## 2. 现状锚点（当前代码）

1. 主编排入口：`backend/src/main/java/com/ai4kb/backend/engine/service/EngineOrchestrator.java`
2. 当前模型网关：`backend/src/main/java/com/ai4kb/backend/engine/service/LlmClient.java`
3. Agent 对外接口：`backend/src/main/java/com/ai4kb/backend/engine/controller/AgentController.java`
4. 管理端审计入口：
   - 路由样本：`backend/src/main/java/com/ai4kb/backend/user/controller/UserAdminController.java`
   - 技能审计：`backend/src/main/java/com/ai4kb/backend/skill/controller/SkillProtocolAdminController.java`
5. 配置锚点：`backend/src/main/resources/application.yml`

## 3. 总体架构（可插拔 Brain）

在 engine 层引入统一大脑接口 `BrainClient`，由编排器依赖接口而不是依赖具体实现。

### 3.1 接口职责

`BrainClient` 统一暴露以下能力：

1. 路由决策（route planning）
2. 对话生成（stream/non-stream）
3. 工具调用抽取（tool call extraction）

### 3.2 两个实现

1. `LocalBrainClient`
   - 封装现有 `LlmClient` 行为
   - 保证默认行为与当前一致
2. `OpenClawBrainClient`
   - 对接 OpenClaw Agent Core API
   - 做协议映射与字段归一化

### 3.3 注入切换

通过 Spring 条件装配 + 配置项切换：

1. `ai4kb.brain.provider=local`
2. `ai4kb.brain.provider=openclaw`

## 4. OpenClaw 适配核心步骤

### 步骤 1：抽象统一模型

在 engine 内定义与现有链路对齐的内部 DTO（不改前端协议）：

1. `BrainChatRequest`
2. `BrainChatChunk`
3. `BrainRouteDecision`
4. `BrainToolCall`

### 步骤 2：替换编排器依赖

`EngineOrchestrator` 中：

1. 保留当前状态机、审批恢复、SSE 事件协议
2. 将直接调用 `LlmClient` 的位置切换为 `BrainClient`
3. 保持 `applyRouteDecision/fallbackRoute` 的策略结构不变

### 步骤 3：落地 Local 实现（零行为变化）

1. 先完成 `LocalBrainClient` 并覆盖当前逻辑
2. 通过现有测试确保行为等价
3. 作为 OpenClaw 接入前的基线版本

### 步骤 4：接入 OpenClaw 实现

1. `OpenClawBrainClient` 负责：
   - 请求头（API Key / Agent 标识）
   - 会话标识映射（conversationId 对齐）
   - 流式事件转换（OpenClaw -> 当前 SSE 事件）
2. 处理工具调用映射：
   - OpenClaw tool invocation -> `ToolCallDraft`
   - 保留既有人工审批与恢复链路

### 步骤 5：配置与回滚

在 `application.yml` 增加：

1. `ai4kb.brain.provider`
2. `ai4kb.brain.openclaw.base-url`
3. `ai4kb.brain.openclaw.api-key`
4. `ai4kb.brain.openclaw.agent-id`
5. `ai4kb.brain.openclaw.timeout-ms`

要求：

1. 默认 `provider=local`
2. 出现异常可一键切回 `local`

## 5. 管理端可见性对齐（不新增治理体系）

你现有审计体系继续复用，仅做映射：

1. 路由样本继续写入 `RouteSampleService`
2. 技能调用继续走 `DynamicSkillAuditService`
3. OpenClaw 的 trace_id/tool_call_id 映射到现有字段
4. 管理员与超级管理员仍使用现有接口查看“每人记忆/技能链路”

## 6. 联调与验收

### 6.1 功能验收

1. `/api/v1/agent/chat/stream` 在 `local/openclaw` 两种 provider 下均可工作
2. `/api/v1/agent/tool/approve` 链路不变
3. 会话与记忆读写不变
4. 管理端审计查询结构不变

### 6.2 回归清单

1. Planner 路由（TOOL/RAG/CHAT）
2. Tool draft + 人工审批 + 恢复执行
3. fallback 计划拆解
4. 分析步骤 SSE 事件完整性

## 7. 分阶段实施计划

### Phase A（低风险重构）

1. 引入 `BrainClient` 接口
2. 完成 `LocalBrainClient`
3. `EngineOrchestrator` 切换到接口调用

### Phase B（OpenClaw 适配）

1. 实现 `OpenClawBrainClient`
2. 完成协议映射与异常处理
3. 在开发环境开启 `provider=openclaw`

### Phase C（灰度与切换）

1. 指定管理员账号灰度
2. 对比 local/openclaw 的链路审计与成功率
3. 满足阈值后全量切换

## 8. 风险与控制

1. 协议差异风险：通过 DTO 映射层隔离
2. 流式事件不一致风险：统一转换为当前 SSE 事件集合
3. 工具调用语义偏差风险：保留人工审批兜底
4. 稳定性风险：保留 `provider=local` 快速回滚

## 9. 下一步执行规划（当前阶段）

1. 完成 OpenClaw `trace_id/tool_call_id` 到现有审计字段的映射增强：
   - 在工具草稿阶段保存上游 trace/tool_call 元信息；
   - 在审批执行阶段透传到审计写入服务；
   - 审计库优先写入上游 id，缺失时回落到本地生成值。
2. 保持切换开关策略不变：
   - `application.yml` 继续保持 `provider=local` 默认值；
   - 通过 Docker 环境变量在部署时切到 `openclaw`。
3. 执行后端回归验证：
   - 编译通过；
   - 单测通过；
   - 诊断无新增错误。
4. 追加过程记录到 `programDoc/05_recordAiOperate.md`，形成可追溯闭环。

## 10. 这么做的原因

1. 先补链路标识映射，再做功能扩展：
   - 先保证审计链路“同一调用可追踪”，避免后续排障无统一定位键。
2. 默认值保持 `local`：
   - 保证线上异常时可快速回退，不影响现有登录、记忆、审批主流程。
3. 仅做最小改动：
   - 避免触碰既有 RBAC、租户隔离与管理端查询结构，降低改动面和回归成本。
4. 先验证再收口：
   - 通过编译、测试、诊断三层校验，确认变更可用后再记录归档，减少“记录已写但代码未稳”的风险。

## 11. 全托管改造清单（原创托管 / OpenClaw托管 双模）

1. 前端增加“托管模式”开关，固定透传 `brainMode` 到 `/api/v1/agent/chat/stream`：
   - `local_hosted`：走现有 Java 编排链路。
   - `openclaw_hosted`：走 OpenClaw 全托管链路。
2. 后端入口统一保持不变，仅在 `AgentController.chatStream` 内按 `brainMode` 分流：
   - `local_hosted` -> `EngineOrchestrator.processAdvanced`。
   - `openclaw_hosted` -> `OpenClawManagedWorkflowService.chatStream`。
3. 将“OpenClaw 作为假大脑代理”的做法改为“OpenClaw 全流程托管”：
   - Java 侧不再承担 OpenClaw 模式下的规划与路由细节。
   - Java 侧只做鉴权、会话上下文、协议透传和结果流式转发。
4. 保持同一对外协议（前端无需拆成两套聊天接口）：
   - 对前端仍是 `/api/v1/agent/chat/stream` SSE。
   - 对 OpenClaw 由 `OpenClawManagedWorkflowService` 负责上游请求组装。
5. 配置项抽离到 `ai4kb.brain.openclaw.*`：
   - `managed-base-url` / `managed-chat-path` / `managed-api-key`
   - `rag-search-url` / `skill-catalog-url` / `skill-execute-url` / `workflow-spec-url`
6. 前端显示层区分“来源”和“托管模式”：
   - 来源：`LOCAL` 或 `OPENCLAW`（部署来源标识）。
   - 托管模式：`原创托管` 或 `OpenClaw托管`（会话执行模式标识）。
7. 回滚策略：
   - 前端切回 `local_hosted` 可立即回退到原创托管。
   - 服务端保留本地编排链路不删，避免 OpenClaw 侧异常导致全局不可用。

## 12. 抽离方案（把“假大脑”和原创框架彻底解耦）

1. 职责边界：
   - `EngineOrchestrator`：仅负责原创托管。
   - `OpenClawManagedWorkflowService`：仅负责 OpenClaw托管。
   - `AgentController`：仅负责模式路由，不承载业务编排。
2. 抽离原则：
   - 不在 `EngineOrchestrator` 中加入 OpenClaw 分支。
   - 不在 OpenClaw 托管链路内复用本地 planner 结果。
   - 两条链路共享的仅是鉴权、会话 ID、SSE 协议壳。
3. 前端策略：
   - 每次发问都带 `brainMode`，避免后端使用隐式会话状态猜测模式。
   - 模式值持久化（localStorage），刷新页面后保持用户选择。
4. 演进策略：
   - 后续若新增第三种大脑，仅新增模式值和服务实现，不改前端主流程。

## 13. 接口契约草案（RAG / Skill 两组）

### 13.1 OpenClaw 托管入口（AI4KB -> OpenClaw）

1. 请求地址：
   - `POST {ai4kb.brain.openclaw.managed-base-url}{ai4kb.brain.openclaw.managed-chat-path}`
2. 请求头：
   - `Authorization: Bearer <managed-api-key 或 api-key>`
   - `Content-Type: application/json`
3. 请求体（JSON）：

```json
{
  "conversation_id": "string",
  "user_id": 123,
  "query": "string",
  "adjustment_instruction": "string",
  "edited_steps": [],
  "rerun_mode": "AUTO|REPLAN|...",
  "restart_from_step": 1,
  "replan_only": false,
  "rag_search_url": "https://.../rag/search",
  "skill_catalog_url": "https://.../skill/catalog",
  "skill_execute_url": "https://.../skill/execute",
  "workflow_spec_url": "https://.../workflow-spec.md",
  "brain_mode": "openclaw_hosted"
}
```

4. 响应体（SSE）：
   - `event: analysis_plan | analysis_step | analysis_summary | tool_draft | clarify | token | error | done`
   - `data: string(json 或 token 文本)`
5. 约束：
   - 失败时必须输出 `error` 事件，最后输出 `done:[DONE]`。
   - `token` 事件可多次，`done` 事件只能一次。

### 13.2 RAG 接口契约（OpenClaw -> 你的 RAG 服务）

1. 请求地址：
   - 由 `rag_search_url` 指定（建议固定为 `/api/rag/search`）。
2. 推荐请求体：

```json
{
  "query": "string",
  "top_k": 5,
  "conversation_id": "string",
  "user_id": 123,
  "filters": {
    "dataset_ids": ["d1", "d2"]
  }
}
```

3. 推荐响应体：

```json
{
  "answer": "string",
  "chunks": [
    {
      "id": "chunk_1",
      "content": "string",
      "score": 0.92,
      "source": "doc-a.pdf",
      "metadata": {
        "page": 3
      }
    }
  ],
  "trace_id": "rag-trace-xxx"
}
```

4. 错误响应：
   - `HTTP 4xx/5xx` + `{"error":"...","trace_id":"..."}`
5. 语义要求：
   - `answer` 允许为空字符串（表示只返回证据，由 LLM 组织答案）。
   - `chunks` 为空时不报错，按“未召回”语义返回。

### 13.3 Skill 接口契约（OpenClaw -> 你的 Skill 服务）

1. Skill 目录查询（`skill_catalog_url`）：
   - `GET /api/skill/catalog`
   - 响应示例：

```json
{
  "tools": [
    {
      "name": "cad_text_extractor_indicator_verification",
      "description": "指标校核",
      "input_schema": {
        "type": "object",
        "properties": {
          "query": { "type": "string" }
        },
        "required": ["query"]
      }
    }
  ]
}
```

2. Skill 执行（`skill_execute_url`）：
   - `POST /api/skill/execute`
   - 请求示例：

```json
{
  "tool_name": "cad_text_extractor_indicator_verification",
  "args": {
    "query": "请做指标校核"
  },
  "conversation_id": "string",
  "user_id": 123,
  "tool_call_id": "tc-001"
}
```

   - 响应示例：

```json
{
  "ok": true,
  "output_text": "校核完成，发现2处异常",
  "artifacts": [
    {
      "file_id": "f-001",
      "file_name": "result.xlsx",
      "download_url": "/api/v1/agent/tool/result/f-001"
    }
  ],
  "trace_id": "skill-trace-xxx"
}
```

3. 错误响应：
   - `HTTP 4xx/5xx` + `{"ok":false,"error":"...","trace_id":"..."}`
4. 语义要求：
   - `tool_call_id` 必须原样回传，便于对齐审批与审计链路。
   - 大文件结果以 `artifacts` 返回，不在 `output_text` 内内联二进制内容。
