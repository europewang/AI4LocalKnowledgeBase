# AI4KB Backend Service

## 简介
这是 AI4LocalKnowledgeBase 的后端服务，基于 Spring Boot 3 构建。
它作为业务中台，负责用户鉴权、权限控制以及与 RAGFlow 引擎的交互。

## 架构特点
1.  **Proxy Shell 模式**: 不存储知识库具体内容，仅存储“用户-知识库”的权限映射关系。
2.  **Processor 模式**: 核心对话逻辑通过 `ChatProcessor` 接口抽象，目前实现 `RagDirectProcessor` (直通模式)，未来可扩展 `AgentOrchestratorProcessor` (智能体模式)。
3.  **RAGFlow Client**: 封装了与 RAGFlow API 的交互（WebClient 实现）。

## 快速开始

### 1. 环境依赖
*   JDK 17+
*   Maven 3.8+
*   MySQL (已通过 Docker 部署在 3307 端口)
*   RAGFlow (已通过 Docker 部署在 8084 端口)

### 2. 配置
在 `src/main/resources/application.yml` 中配置 RAGFlow 的 API Key：
```yaml
ragflow:
  base-url: http://localhost:8084
  api-key: <YOUR_RAGFLOW_SYSTEM_API_KEY>
```

### 3. 运行
```bash
mvn spring-boot:run
```

## API 接口说明

### Admin 接口
*   `GET /api/admin/datasets`: 获取 RAGFlow 所有知识库列表
*   `POST /api/admin/permission/grant`: 给用户分配权限
    ```json
    {
      "username": "zhangsan",
      "resource_type": "DATASET",
      "resource_id": "kb-uuid"
    }
    ```
*   `GET /api/admin/permission/{username}`: 查看用户权限

### User 接口
*   `POST /api/chat/completions`: 发起对话 (SSE 流式)
    *   **Header**: `X-User-Name: zhangsan`
    *   **Body**:
        ```json
        {
          "question": "水准测量限差是多少？",
          "stream": true
        }
        ```

## 数据库设计
*   `t_user`: 用户表 (仅存储 username/role)
*   `t_permission`: 权限表 (关联 user_id 与 resource_id)
