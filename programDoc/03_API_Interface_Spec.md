# API 接口规范 (API Interface Spec)

本系统采用 RESTful 风格接口，包含两组主路径：`/api/v1`（Agent）与 `/api`（Admin/User/RAG）。
后端端口：`8083`（映射到 Docker 内部 8083）。

## 1. 认证鉴权 (Auth)

### 1.1 获取 Token
*   **URL**: `/api/user/auth/login`
*   **Method**: `POST`
*   **Request**:
    ```json
    { "username": "admin", "password": "***" }
    ```
*   **Response**:
    ```json
    {
      "token": "jwt-token-xxx",
      "expires_at": 1774442400,
      "user": { "id": 1, "username": "admin", "role": "admin" }
    }
    ```
*   **鉴权头**: 业务接口统一使用 `Authorization: Bearer <token>`。

## 2. 知识库管理 (Knowledge Base)

### 2.1 创建知识库
*   **URL**: `/api/v1/kb/create`
*   **Method**: `POST`
*   **Request**:
    ```json
    {
      "name": "测绘规范2026",
      "parser_method": "manual" // DeepDoc模式
    }
    ```
*   **Response**: `dataset_id`

### 2.2 知识库列表
*   **URL**: `/api/v1/kb/list`
*   **Method**: `GET`
*   **Response**: `[ { "id": "kb-1", "name": "..." } ]`

## 3. 文档管理 (Document)

### 3.1 上传文件
*   **URL**: `/api/v1/doc/upload`
*   **Method**: `POST` (Multipart/form-data)
*   **Params**:
    *   `file`: (Binary)
    *   `kb_id`: (String)
*   **Response**: `document_id`

### 3.2 获取文档状态
*   **URL**: `/api/v1/doc/status/{docId}`
*   **Method**: `GET`
*   **Response**:
    ```json
    { "status": "SUCCESS", "chunk_count": 120, "progress": 100 }
    ```

## 4. 对话与检索 (Chat)

### 4.1 发起对话 (Streaming)
*   **URL**: `/api/v1/chat/completions`
*   **Method**: `POST`
*   **Request**:
    ```json
    {
      "conversation_id": "conv-123",
      "kb_ids": ["kb-1"],
      "query": "三等水准测量限差是多少？",
      "stream": true
    }
    ```
*   **Response (SSE)**:
    *   `data: { "answer": "...", "reference": { ... } }`

### 4.2 获取引用高亮数据 (Highlight)
*   **URL**: `/api/v1/chat/reference/{chunkId}`
*   **Method**: `GET`
*   **Response**:
    ```json
    {
      "chunk_id": "abc-123",
      "doc_id": "doc-567",
      "page_number": 5,
      "positions": [[100, 200, 300, 220]] // [x, y, w, h]
    }
    ```

## 5. RAGFlow 透传接口
部分高级功能直接透传 RAGFlow API，后端仅做鉴权封装。
*   RAGFlow API Base: `http://ragflow-server:8084`
*   鉴权方式: Header `Authorization: Bearer <RAGFLOW_API_KEY>`

## 7. 业务后端接口 (Business Backend API)
本节定义 Java 后端 (`:8083`) 提供的业务接口。后端采用 **Proxy Shell** 模式，核心数据（如知识库内容）透传 RAGFlow，仅本地存储权限映射。

### 7.1 Admin 管理接口
**Base URL**: `/api/admin`

#### 1. 获取知识库列表 (List Datasets)
直接透传 RAGFlow 接口，获取所有可用知识库。
*   **URL**: `/datasets`
*   **Method**: `GET`
*   **Query**:
    *   `page`: int (default 1)
    *   `page_size`: int (default 100)
*   **Response**:
    ```json
    {
      "code": 0,
      "data": [
        { "id": "kb-001", "name": "测绘规范库", "doc_count": 10 },
        { "id": "kb-002", "name": "内部档案库", "doc_count": 5 }
      ]
    }
    ```

#### 2. 更新知识库 (Update Dataset)
修改知识库名称或描述。透传至 RAGFlow。
*   **URL**: `/datasets/{id}`
*   **Method**: `PUT`
*   **Body**:
    ```json
    {
      "name": "新名称",
      "description": "新的描述备注"
    }
    ```
*   **Response**: RAGFlow 响应 JSON

#### 3. 用户授权 (Grant Permission)
给用户分配知识库访问权限。
*   **URL**: `/permission/grant`
*   **Method**: `POST`
*   **Body**:
    ```json
    {
      "username": "zhangsan",
      "resource_type": "DATASET", // 或 "SKILL"
      "resource_id": "kb-001"
    }
    ```
*   **Response**: `String ("ok" or error message)`

#### 3. 获取用户权限 (Get Permissions)
查看指定用户已拥有的权限列表。
*   **URL**: `/permission/{username}`
*   **Method**: `GET`
*   **Response**:
    ```json
    [
      { "id": 1, "userId": 2, "resourceType": "DATASET", "resourceId": "kb-001", ... }
    ]
    ```

#### 4. 创建用户 (Create User)
简单的用户注册接口。
*   **URL**: `/user`
*   **Method**: `POST`
*   **Body**:
    ```json
    { "username": "lisi", "role": "user" }
    ```
*   **Response**: `User Object`

### 7.2 User 对话接口
**Base URL**: `/api/chat`

#### 1. 发起对话 (Chat Completions)
用户发起问答，后端自动根据权限注入 `dataset_ids`。
*   **URL**: `/completions`
*   **Method**: `POST`
*   **Headers**:
    *   `Authorization`: `Bearer <jwt-token>`（必填）
*   **Body**:
    ```json
    {
      "question": "水准测量限差是多少？",
      "stream": true // 建议开启流式
    }
    ```
*   **Response**:
    *   **Content-Type**: `text/event-stream`
    *   **Data Format**: 透传 RAGFlow 的 SSE 格式
    ```text
    data: {"answer": "...", "reference": {"chunks": [...]}}
    ```

### 7.3 调用示例 (Curl)
```bash
# 1. Admin: 给 zhangsan 分配知识库权限
curl -X POST http://localhost:8083/api/admin/permission/grant \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <admin_token>" \
  -d '{"username": "zhangsan", "resource_type": "DATASET", "resource_id": "你的ragflow_dataset_id"}'

# 2. User: zhangsan 发起提问
curl -X POST http://localhost:8083/api/chat/completions \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer <user_token>" \
  -d '{"question": "你好", "stream": true}'
```

## 8. Agent 工作流接口（意图识别 + 工具调用 + 审批 + 恢复）

本节为新设计接口，面向“多轮联动 + 人工审查工具参数 + 确认后执行”的完整 Agent 流程。

### 8.1 发起 Agent 对话（流式）
*   **URL**: `/api/v1/agent/chat/stream`
*   **Method**: `POST`
*   **Headers**:
    *   `Authorization`: `Bearer <jwt-token>`（必填）
*   **Request**:
    ```json
    {
      "conversationId": "conv-001",
      "query": "根据招标要求生成投标函并发给项目经理",
      "adjustmentInstruction": "把第2步改成先检索规范再执行技能",
      "editedSteps": [],
      "rerunMode": "AUTO",
      "restartFromStep": 1,
      "replanOnly": false
    }
    ```
*   **SSE 事件类型**:
    *   `analysis_plan`: 本轮分析计划（deep_thinking/question_type/steps/summary）
    *   `analysis_step`: 执行过程步骤流（含 waiting_approval、streaming、completed）
    *   `analysis_summary`: 本轮分析汇总（plan_id/version/step_count/final_answer）
    *   `message`: 结构化回答（RAG/CHAT 结果与引用）
    *   `token`: 普通流式文本
    *   `tool_draft`: 识别到 Skill 且给出参数草稿（等待审批）
    *   `tool_result`: 审批执行后的工具结果摘要
    *   `clarify`: 低置信度澄清引导
    *   `error`: 异常事件
    *   `done`: 本轮结束
*   **tool_draft 示例**:
    ```json
    {
      "event": "tool_draft",
      "conversation_id": "conv-001",
      "tool_call_id": "tc-1001",
      "tool_name": "send_email",
      "schema": {
        "type": "object",
        "properties": {
          "to": { "type": "string", "format": "email" },
          "subject": { "type": "string", "minLength": 1 },
          "content": { "type": "string", "minLength": 1 }
        },
        "required": ["to", "content"]
      },
      "param_source": "rag",
      "draft_args": {
        "to": "pm@company.com",
        "subject": "投标函草案",
        "content": "..."
      }
    }
    ```

### 8.2 提交审批并恢复执行（流式）
*   **URL**: `/api/v1/agent/tool/approve`
*   **Method**: `POST`
*   **Headers**:
    *   `Authorization`: `Bearer <jwt-token>`（必填）
*   **Request**:
    ```json
    {
      "conversationId": "conv-001",
      "toolCallId": "tc-1001",
      "reviewedArgs": "{\"to\":\"pm@company.com\",\"subject\":\"请审阅：投标函（修订版）\",\"content\":\"人工修改后的内容\"}"
    }
    ```
*   **Response**:
    *   Content-Type: `text/event-stream`
    *   事件顺序通常为：`tool_result` -> `token/message` -> `done`

### 8.3 工具目录与文件接口（当前实现）
*   **统一要求**: 所有接口都需要 `Authorization: Bearer <jwt-token>`
*   **GET** `/api/v1/agent/tool/catalog`：可用工具清单
*   **POST** `/api/v1/agent/tool/upload`：上传工具输入文件（multipart）
*   **GET** `/api/v1/agent/tool/files?toolCallId=...`：查询已上传文件
*   **GET** `/api/v1/agent/tool/result/{fileId}`：下载工具输出文件

## 9. 用户与权限管理接口（RBAC）

### 9.1 登录
*   **URL**: `/api/user/auth/login`
*   **Method**: `POST`
*   **Request**:
    ```json
    { "username": "admin01", "password": "***" }
    ```
*   **Response**:
    ```json
    {
      "token": "jwt-xxx",
      "expires_at": 1774442400,
      "user": { "id": 2, "username": "admin01", "role": "admin" }
    }
    ```

### 9.2 超级管理员创建管理员
*   **URL**: `/api/admin/users/admin`
*   **Method**: `POST`
*   **权限**: `super_admin`
*   **Request**:
    ```json
    { "username": "admin02", "password": "***", "name": "李四" }
    ```

### 9.3 管理员创建普通用户
*   **URL**: `/api/admin/users/normal`
*   **Method**: `POST`
*   **权限**: `admin` / `super_admin`
*   **Request**:
    ```json
    { "username": "user01", "password": "***", "name": "王五" }
    ```

### 9.4 分配知识库权限
*   **URL**: `/api/admin/permissions/datasets`
*   **Method**: `POST`
*   **Request**:
    ```json
    {
      "target_user_id": 10,
      "dataset_id": "kb-001",
      "action": "READ"
    }
    ```

### 9.5 分配技能权限
*   **URL**: `/api/admin/permissions/skills`
*   **Method**: `POST`
*   **Request**:
    ```json
    {
      "target_user_id": 10,
      "skill_code": "send_email",
      "action": "USE"
    }
    ```

### 9.6 超级管理员审计查询（路由样本）
*   **URL**: `/api/admin/route-samples`
*   **Method**: `GET`
*   **权限**: `super_admin`
*   **Query**:
    *   `limit`: int（默认 100，最大 500）
    *   `userId`: long（可选）
    *   `source`: string（可选）
*   **说明**: 返回路由决策样本，用于审计查询与路由策略复盘。

### 9.7 超级管理员审计来源枚举
*   **URL**: `/api/admin/route-samples/sources`
*   **Method**: `GET`
*   **权限**: `super_admin`
*   **说明**: 动态返回样本来源类型（如 `PLANNER_ONLY`、`LOCAL_DIRECT`）。

### 9.8 超级管理员管理员资产总览
*   **URL**: `/api/admin/super/ownership-overview`
*   **Method**: `GET`
*   **权限**: `super_admin`
*   **说明**: 聚合返回“管理员 -> 其创建/拥有的知识库 -> 文档数量 -> 已授权用户列表（含授权时间） -> 会话总览（含对话记录与记录时间）”视图。

### 9.9 用户会话新建
*   **URL**: `/api/user/conversations`
*   **Method**: `POST`
*   **权限**: 已登录用户
*   **Request**:
    ```json
    { "title": "新对话" }
    ```
*   **说明**: 创建会话并返回会话信息（`id/userId/title/createdAt/updatedAt`）。

### 9.10 用户会话列表
*   **URL**: `/api/user/conversations`
*   **Method**: `GET`
*   **权限**: 已登录用户
*   **说明**: 返回当前用户会话列表（按更新时间倒序）。

### 9.11 用户会话消息写入
*   **URL**: `/api/user/conversations/{conversationId}/messages`
*   **Method**: `POST`
*   **权限**: 已登录用户
*   **Request**:
    ```json
    {
      "role": "user",
      "content": "请解释一下半面积计算",
      "conversationTitle": "半面积问题"
    }
    ```
*   **说明**: 向指定会话追加消息；若会话不存在且 `conversationId` 合法，则自动补建会话并写入消息。

### 9.12 用户会话消息列表
*   **URL**: `/api/user/conversations/{conversationId}/messages`
*   **Method**: `GET`
*   **权限**: 已登录用户
*   **说明**: 返回指定会话消息明细（按消息写入顺序）。

## 10. 权限规则（关键约束）

1. 管理员创建的知识库对普通用户“外层可见”（可见名称与描述）。
2. 普通用户对管理员知识库默认仅可 `READ_META`，不可查看内容（Chunk/原文）且不可修改、删除。
3. 超级管理员可进行全部操作，包括用户角色变更、资源授权、审计查询。
4. 审计查询接口（`/api/admin/route-samples*`）与管理员资产总览接口（`/api/admin/super/ownership-overview`）仅 `super_admin` 可访问。
5. 超级管理员可创建管理员账号；管理员与超级管理员均可创建普通用户并分配资源权限。
6. 所有查询、工具调用、审批操作均写入审计日志 `t_audit_log`。
