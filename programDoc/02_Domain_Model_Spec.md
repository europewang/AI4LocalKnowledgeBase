# 核心领域模型定义 (Domain Model Spec)

本系统的核心领域模型是对接 RAGFlow 实体与业务增强属性的集合。后端 Java 系统将维护这些模型的业务状态，而实际的向量与解析数据存储在 RAGFlow (Elasticsearch/MinIO) 中。

## 0. 租户与鉴权 (Tenant & Auth)
RAGFlow 是多租户系统，每个用户/组织对应一个 `Tenant`。API 调用需要 `Authorization: Bearer <token>`。

| 字段名 | 类型 | 说明 | RAGFlow 映射 |
| :--- | :--- | :--- | :--- |
| `tenant_id` | String | 租户 ID (对应 MySQL `tenant` 表) | `tenant_id` |
| `token` | String | API 访问令牌 | `api_token` 表 |

> **注意**: RAGFlow 的 `api_token` 表默认可能为空。若无 Token，需通过直连 MySQL 插入一条记录来手动生成（参考 `test_ragflow_e2e.py`）。

## 1. 知识库 (KnowledgeBase / Dataset)
管理文档的逻辑集合，对应 RAGFlow 中的 `Dataset`。

| 字段名 | 类型 | 说明 | RAGFlow 映射 |
| :--- | :--- | :--- | :--- |
| `id` | String (UUID) | 知识库唯一标识 | `dataset_id` |
| `name` | String | 知识库名称 (如“工程测量规范”) | `name` |
| `description` | String | 描述信息 | `description` |
| `permission` | Enum | 权限 (PRIVATE/PUBLIC/TEAM) | `permission` |
| `owner_id` | String | 创建者用户ID | `created_by` |
| `parser_config` | JSON | 解析配置 (DeepDoc/Table) | `parser_config` |

## 2. 文档 (Document)
上传的具体文件，是解析与检索的基本单元。

| 字段名 | 类型 | 说明 | RAGFlow 映射 |
| :--- | :--- | :--- | :--- |
| `id` | String (UUID) | 文档唯一标识 | `document_id` |
| `kb_id` | String | 所属知识库 ID | `dataset_id` |
| `name` | String | 文件名 (file.pdf) | `name` |
| `type` | String | 文件类型 (pdf, docx, xlsx) | `source_type` |
| `status` | Enum | 状态 (PENDING, PARSING, SUCCESS, FAIL) | `run_status` |
| `chunk_count` | Integer | 切片数量 | `chunk_num` |
| `upload_time` | Timestamp | 上传时间 | `create_time` |

## 3. 切片 (Chunk)
文档解析后的最小检索单元，包含向量与原文坐标。**这是实现高亮溯源的核心对象。**

| 字段名 | 类型 | 说明 | RAGFlow 映射 |
| :--- | :--- | :--- | :--- |
| `id` | String | 切片 ID | `chunk_id` |
| `content_with_weight`| String | 切片文本内容 | `content_with_weight` |
| `doc_id` | String | 所属文档 ID | `doc_id` |
| `positions` | List<Object> | **位置坐标数据 (BBox)** | `positions` |
| `page_num` | Integer | 页码 (用于跳转) | `page_num` |
| `img_id` | String | 关联图片的 ID (若有) | `img_id` |

> **Position 结构示例**:
> ```json
> [
>   { "page_number": 1, "bbox": [[100.5, 200.0, 300.5, 220.0]] }
> ]
> ```

## 4. 对话 (Conversation)
用户与系统的交互会话上下文。

| 字段名 | 类型 | 说明 | RAGFlow 映射 |
| :--- | :--- | :--- | :--- |
| `id` | String | 会话 ID | `conversation_id` |
| `user_id` | String | 用户 ID | `user_id` |
| `kb_ids` | List<String> | 关联的知识库列表 | `dialog_id` (Assistant) |
| `messages` | List<Message>| 消息历史 | (存储在 RAGFlow DB) |

## 5. 消息与引用 (Message & Reference)
单轮问答的数据结构。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `role` | Enum | USER / ASSISTANT |
| `content` | String | 消息内容 / 回答文本 |
| `quote` | Boolean | 是否包含引用 |
| `references` | List<Ref> | **引用来源列表** (用于高亮) |

> **Reference 结构**:
> *   `chunk_id`: 来源切片 ID
> *   `doc_name`: 来源文档名
> *   `doc_id`: 来源文档 ID
> *   `content`: 切片原文
> *   `positions`: **坐标信息 (用于前端 Canvas 绘制)**

## 6. 用户与鉴权模型（User / RBAC）

为支持“总管理员 / 管理员 / 普通用户”三层权限以及知识库、Skill 授权，业务数据库（`ai4kb`）采用以下核心表。

### 6.1 t_user（用户主表）

> **设计说明**: 系统账号的唯一来源，采用典型的 RBAC（Role-Based Access Control）三层设计。不同角色的能力范围由代码硬编码：`super_admin` 拥有一切权限；`admin` 可建知识库和普通用户；`user` 只能看被授权的内容。密码需做哈希（如 BCrypt）防止拖库泄露。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | BIGINT | 主键 |
| `username` | VARCHAR(64) | 登录账号（唯一） |
| `password_hash` | VARCHAR(128) | BCrypt 密码哈希 |
| `role` | VARCHAR(20) | 角色：`super_admin` / `admin` / `user` |
| `create_time` | DATETIME | 创建时间 |

### 6.2 t_permission（资源授权表）

> **设计说明**: 用于解决“谁能用什么”的问题。在 RAG 系统中，知识库（DATASET）和技能（SKILL）是核心资产。这条表记录了某个用户对某个资产的访问权限。比如，普通用户只有在被 `admin` 写入了一条 `action=READ` 的授权记录后，才能在检索时查到该知识库的内容。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | BIGINT | 主键 |
| `user_id` | BIGINT | 用户 ID（关联 t_user.id） |
| `resource_type` | VARCHAR(20) | 资源类型：`DATASET` / `SKILL` |
| `resource_id` | VARCHAR(64) | 资源 ID（如 dataset_id / skill_code） |
| `action` | VARCHAR(20) | 动作：`READ` / `USE` / `MANAGE` |
| `create_time` | DATETIME | 创建时间 |

### 6.3 t_audit_log（审计日志表）

> **设计说明**: 合规与运营的“黑匣子”。Agent 系统的动作（查询大模型、调用外部接口、修改权限）都可能产生敏感影响。审计表通过记录 `action_type` 和结构化的 `details`，帮助超级管理员追溯问题（比如“谁在昨天下午3点修改了标书金额？”），同时也可用于统计 Token 消耗和工具调用成功率。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | BIGINT | 主键 |
| `user_id` | BIGINT | 操作用户 ID |
| `action_type` | VARCHAR(64) | 行为类型：`LOGIN` / `QUERY` / `TOOL_CALL` / `ADMIN_OP` |
| `resource_id` | VARCHAR(64) | 资源标识（会话ID、知识库ID、Skill名等） |
| `details` | TEXT | 结构化 JSON 文本，记录参数快照与结果摘要 |
| `create_time` | DATETIME | 创建时间 |

## 7. Agent 引擎模型（Engine）

为支持多轮联动、工具审批与恢复执行，引入会话状态与工具调用状态模型。

### 7.1 会话状态（ConversationState）

> **设计说明**: 这是 Engine 编排多轮对话和工作流的“中枢神经”。在 Agent 场景下，一次任务可能需要中途停下来等待用户审批工具参数。`ConversationState` 负责记录当前会话挂起在哪一步（`status: WAITING_APPROVAL`），并在前端带着审批结果（`pending_tool_call_id`）回来时，帮助 Engine 重新接上之前的上下文继续执行。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `conversation_id` | String | 会话唯一 ID |
| `user_id` | Long | 当前会话所属用户 |
| `memory_window` | List<Message> | 最近 N 轮对话记忆（工作记忆，保证对话连贯） |
| `status` | Enum | 状态机：`RUNNING`（正常） / `WAITING_APPROVAL`（挂起等待） / `RESUMED`（唤醒恢复） / `FINISHED` |
| `pending_tool_call_id` | String | 挂起时记录“当前在等哪个工具的审批结果” |
| `updated_at` | Timestamp | 最近更新时间 |

### 7.2 工具调用草稿（ToolCallDraft）

> **设计说明**: 这是系统在真正执行某个工具（如发邮件）之前，与人类进行“确认握手”的凭证。LLM 结合 RAG 初步生成的参数（`draft_args`）可能存在偏差，因此将其固化为草稿，前端据此渲染表单供用户修改。用户确认后的参数作为 `reviewed_args` 传回执行。此模型同时保证了审批的幂等性（一个草稿只能审批一次）。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `tool_call_id` | String | 工具调用唯一 ID |
| `tool_name` | String | Skill 名称 |
| `draft_args` | JSON | LLM 生成的参数草稿（前端用于渲染参数修改表单） |
| `reviewed_args` | JSON | 前端人工审查并修改后传回的最终参数（系统只认此参数执行） |
| `approval_status` | Enum | 审批状态：`PENDING`（待审批） / `APPROVED`（已同意） / `REJECTED`（已拒绝，防重放依据） |
| `created_at` | Timestamp | 创建时间 |

### 7.3 工具执行结果（ToolResult）

> **设计说明**: 用于记录每次工具真实执行的输入和输出摘要。在复杂任务中（比如写标书），某个步骤（查历史价格）的结果可能会在后续步骤（计算报价）被反复用到。将其落库可以避免重复调用耗时的接口，同时为大模型提供“我之前做过什么”的精确上下文参考。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | BIGINT | 主键 |
| `conversation_id` | String | 关联会话 |
| `tool_call_id` | String | 关联工具调用 |
| `tool_name` | String | 工具名称 |
| `args_digest` | JSON | 入参摘要（脱敏后） |
| `result_digest` | JSON | 关键出参/结果摘要（可含资源引用ID） |
| `status` | Enum | `SUCCESS` / `FAIL` |
| `error` | TEXT | 失败原因（可空） |
| `created_at` | DATETIME | 执行时间 |

### 7.4 长期记忆（t_memory）

> **设计说明**: 解决“超长上下文导致大模型遗忘和 Token 爆炸”的问题。Engine 会在后台启动蒸馏任务，将长长的对话记录总结成精炼的结论（DECISION）、事实（FACT）或摘要（SUMMARY）。这些高价值信息会被固化到数据库并向量化（可选）。下次用户再聊类似话题时，系统通过 `conversation_id` 快速召回这些记忆，实现“断点续传”和个性化服务。

| 字段名 | 类型 | 说明 |
| :--- | :--- | :--- |
| `id` | BIGINT | 主键 |
| `conversation_id` | String | 会话 ID |
| `user_id` | BIGINT | 所属用户 |
| `memory_type` | VARCHAR(20) | `SUMMARY` / `DECISION` / `FACT` / `TOOL_RESULT` |
| `content` | TEXT | 记忆内容（短文本/摘要） |
| `embedding` | VECTOR | 向量（可选，便于相似检索） |
| `salience` | FLOAT | 重要性评分（0-1） |
| `pinned` | TINYINT(1) | 是否固定（不被淘汰） |
| `created_at` | DATETIME | 写入时间 |
| `updated_at` | DATETIME | 更新时间 |

### 7.5 缓存键与过期策略（Redis）

> **设计说明**: 性能与一致性的保障。`ConversationState` 和 `ToolCallDraft` 在流转中会频繁被读写，直接打库压力大且容易出现并发问题。因此，把这些高频且生命周期较短（通常在一次任务的几分钟到几天内）的“工作记忆”和“状态机”放在 Redis 中，设置 7 天滚动过期，既保证了读写速度，又实现了自动的垃圾回收。

| 键名 | 含义 | 过期 |
| :--- | :--- | :--- |
| `conv:{conversation_id}:state` | 会话状态对象（ConversationState） | 7d 滚动 |
| `conv:{conversation_id}:wm` | 短期记忆窗口（最近 N 轮消息） | 7d 滚动 |
| `conv:{conversation_id}:tool:recent` | 最近 K 条工具结果摘要 | 7d 滚动 |
