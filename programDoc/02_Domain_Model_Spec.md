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
