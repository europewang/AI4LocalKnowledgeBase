# 后端接口文档 (API Reference)

本文档详细记录了 `AI4LocalKnowledgeBase` 后端的所有 API 接口。

## 1. 管理接口 (Admin Controller)

**Base URL:** `/api/admin`

### 1.1 数据集管理 (Datasets)

#### 1.1.1 获取数据集列表
- **URL:** `/datasets`
- **Method:** `GET`
- **Parameters:**
  - `page` (int, optional): 页码，默认 1
  - `pageSize` (int, optional): 每页数量，默认 100
- **Response:** JSON (RAGFlow 格式)

#### 1.1.2 创建数据集
- **URL:** `/datasets`
- **Method:** `POST`
- **Body (JSON):**
  ```json
  {
    "name": "dataset_name"
  }
  ```
- **Response:** JSON (RAGFlow 格式)

#### 1.1.3 删除数据集
- **URL:** `/datasets/{id}`
- **Method:** `DELETE`
- **Path Variables:**
  - `id`: 数据集 ID
- **Response:** JSON (RAGFlow 格式)

### 1.2 文档管理 (Documents)

#### 1.2.1 获取文档列表
- **URL:** `/datasets/{id}/documents`
- **Method:** `GET`
- **Path Variables:**
  - `id`: 数据集 ID
- **Parameters:**
  - `page` (int, optional): 页码，默认 1
  - `pageSize` (int, optional): 每页数量，默认 100
- **Response:** JSON (RAGFlow 格式)

#### 1.2.2 上传文档
- **URL:** `/datasets/{id}/documents`
- **Method:** `POST`
- **Content-Type:** `multipart/form-data`
- **Path Variables:**
  - `id`: 数据集 ID
- **Form Data:**
  - `file`: 文件对象
- **Response:** JSON (RAGFlow 格式)

#### 1.2.3 解析/运行文档
- **URL:** `/datasets/{id}/documents/run`
- **Method:** `POST`
- **Path Variables:**
  - `id`: 数据集 ID
- **Body (JSON):**
  ```json
  {
    "doc_ids": ["doc_id_1", "doc_id_2"]
  }
  ```
- **Response:** JSON (RAGFlow 格式)

#### 1.2.4 获取文档分块 (Chunks)
- **URL:** `/datasets/{id}/documents/{docId}/chunks`
- **Method:** `GET`
- **Path Variables:**
  - `id`: 数据集 ID
  - `docId`: 文档 ID
- **Parameters:**
  - `page` (int, optional): 页码，默认 1
  - `pageSize` (int, optional): 每页数量，默认 100
- **Response:** JSON (RAGFlow 格式)

#### 1.2.5 获取文档源文件
- **URL:** `/datasets/{id}/documents/{docId}/file`
- **Method:** `GET`
- **Path Variables:**
  - `id`: 数据集 ID
  - `docId`: 文档 ID
- **Response:** Binary (File Content)

#### 1.2.6 删除文档
- **URL:** `/datasets/{id}/documents`
- **Method:** `DELETE`
- **Path Variables:**
  - `id`: 数据集 ID
- **Body (JSON):**
  ```json
  {
    "ids": ["doc_id_1", "doc_id_2"]
  }
  ```
- **Response:** JSON (RAGFlow 格式)

### 1.3 用户与权限管理 (User & Permission)

#### 1.3.1 获取用户列表
- **URL:** `/users`
- **Method:** `GET`
- **Response:** JSON Array (User Objects)

#### 1.3.2 创建用户
- **URL:** `/user`
- **Method:** `POST`
- **Body (JSON):**
  ```json
  {
    "username": "user1",
    "role": "user"
  }
  ```
- **Response:** JSON (User Object)

#### 1.3.3 同步用户权限
- **URL:** `/permission/sync`
- **Method:** `POST`
- **Body (JSON):**
  ```json
  {
    "username": "user1",
    "dataset_ids": ["dataset_id_1", "dataset_id_2"]
  }
  ```
- **Description:** 全量同步用户的 dataset 权限（删除不在列表中的，添加列表中的）。
- **Response:** JSON (`{"status": "ok"}`)

#### 1.3.4 授予权限 (单条)
- **URL:** `/permission/grant`
- **Method:** `POST`
- **Body (JSON):**
  ```json
  {
    "username": "user1",
    "resource_type": "dataset",
    "resource_id": "dataset_id_1"
  }
  ```
- **Response:** JSON (`{"status": "ok"}`)

#### 1.3.5 获取用户权限
- **URL:** `/permission/{username}`
- **Method:** `GET`
- **Path Variables:**
  - `username`: 用户名
- **Response:** JSON Array (Permission Objects)

---

## 2. 文档预览接口 (Document Controller)

**Base URL:** `/api/document`

#### 2.1 获取图片
- **URL:** `/image/{imageId}`
- **Method:** `GET`
- **Path Variables:**
  - `imageId`: 图片 ID
- **Response:** Image (JPEG)

#### 2.2 获取文档 PDF
- **URL:** `/get/{docId}`
- **Method:** `GET`
- **Path Variables:**
  - `docId`: 文档 ID
- **Response:** PDF File (inline)

---

## 3. 聊天接口 (Chat Controller)

**Base URL:** `/api/chat`

#### 3.1 发送聊天消息 (SSE)
- **URL:** `/completions`
- **Method:** `POST`
- **Headers:**
  - `X-User-Name`: 用户名
- **Body (JSON):**
  ```json
  {
    "question": "用户的问题", // 或 "query"
    "stream": true
  }
  ```
- **Response:** Server-Sent Events (Text Stream)
