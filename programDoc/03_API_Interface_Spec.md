# API 接口规范 (API Interface Spec)

本系统采用 RESTful 风格接口，Base URL 为 `/api/v1`。
后端端口：`8083`（映射到 Docker 内部 8083）。

## 1. 认证鉴权 (Auth)

### 1.1 获取 Token
*   **URL**: `/api/v1/auth/login`
*   **Method**: `POST`
*   **Request**:
    ```json
    { "username": "admin", "password": "***" }
    ```
*   **Response**:
    ```json
    { "code": 200, "data": { "token": "jwt-token-xxx" } }
    ```

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

#### 2. 用户授权 (Grant Permission)
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
    *   `X-User-Name`: `zhangsan` (必填，标识当前用户)
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
  -d '{"username": "zhangsan", "resource_type": "DATASET", "resource_id": "你的ragflow_dataset_id"}'

# 2. User: zhangsan 发起提问
curl -X POST http://localhost:8083/api/chat/completions \
  -H "Content-Type: application/json" \
  -H "X-User-Name: zhangsan" \
  -d '{"question": "你好", "stream": true}'
```
